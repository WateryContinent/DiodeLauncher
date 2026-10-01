package net.minecraft.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.launcher.Http;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.updater.download.Sha1Downloadable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import net.minecraft.launcher.updater.CompleteMinecraftVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves and installs the Mojang-managed Java runtime requested by a modern
 * Minecraft version JSON.  The directory layout mirrors the official launcher:
 *
 *   runtime/<component>/<platform>/<component>/bin/java[.exe]
 */
public final class MinecraftRuntimeManager {
   private static final Logger LOGGER = LogManager.getLogger();

   // Mojang's launcher runtime product catalog.  The product hash is the one
   // used by current launcher-compatible runtime installers and includes the
   // Java 8/16/17/21/25 runtime families.
   private static final String RUNTIME_CATALOG_URL =
      "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

   private final File workingDirectory;
   private final Proxy proxy;

   public MinecraftRuntimeManager(File workingDirectory, Proxy proxy) {
      this.workingDirectory = workingDirectory;
      this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
   }

   /**
    * Returns the Java executable that should be used for the selected version.
    * If the version does not request a Mojang runtime, the JVM running the
    * launcher is used as before.
    */
   public String resolveJavaExecutable(CompleteMinecraftVersion version) throws IOException {
      CompleteMinecraftVersion.JavaVersion requested = version == null ? null : version.getJavaVersion();
      if (requested == null) {
         return OperatingSystem.getCurrentPlatform().getJavaDir();
      }

      String component = requested.getComponent();
      if (component == null || component.trim().isEmpty()) {
         component = componentForMajor(requested.getMajorVersion());
      }
      if (component == null) {
         return this.fallbackToCurrentJava(version, requested, null);
      }

      String platform = this.getPlatformKey();
      File runtimeHome = this.getRuntimeHome(component, platform);
      File javaExecutable = this.getJavaExecutable(runtimeHome);

      // Runtimes installed by this launcher get a completion marker. Avoid
      // trusting only bin/java because an interrupted first download could
      // leave that executable present while DLLs/modules are still missing.
      File completionMarker = new File(runtimeHome, ".minecraft-runtime");
      if (javaExecutable.isFile() && completionMarker.isFile()) {
         LOGGER.info("Using cached Minecraft Java runtime {} at {}", component, javaExecutable);
         return javaExecutable.getAbsolutePath();
      }

      LOGGER.info(
         "Minecraft {} requests Java {} runtime component '{}'; installing Mojang runtime for {}",
         version.getId(),
         requested.getMajorVersion(),
         component,
         platform
      );

      try {
         this.installRuntime(component, platform, runtimeHome);
      } catch (IOException | RuntimeException e) {
         if (javaExecutable.isFile()) {
            LOGGER.warn("Runtime update failed, but a local Java runtime is available at " + javaExecutable, e);
            return javaExecutable.getAbsolutePath();
         }
         if (this.currentJavaMajor() == requested.getMajorVersion()) {
            LOGGER.warn("Couldn't install Mojang runtime; launcher Java is the exact requested major version, falling back to it", e);
            return OperatingSystem.getCurrentPlatform().getJavaDir();
         }
         throw new IOException(
            "Minecraft " + version.getId() + " requires Java " + requested.getMajorVersion()
               + " (" + component + ") and the Mojang runtime could not be installed",
            e
         );
      }

      if (!javaExecutable.isFile()) {
         return this.fallbackToCurrentJava(version, requested, component);
      }

      LOGGER.info("Using Minecraft Java runtime {} at {}", component, javaExecutable);
      return javaExecutable.getAbsolutePath();
   }

   private String fallbackToCurrentJava(
      CompleteMinecraftVersion version,
      CompleteMinecraftVersion.JavaVersion requested,
      String component
   ) throws IOException {
      int current = this.currentJavaMajor();
      if (requested.getMajorVersion() <= 0 || current == requested.getMajorVersion()) {
         LOGGER.warn(
            "No usable Mojang runtime was found for {}; falling back to launcher Java {}",
            version == null ? "selected version" : version.getId(),
            current
         );
         return OperatingSystem.getCurrentPlatform().getJavaDir();
      }

      throw new IOException(
         "Minecraft " + (version == null ? "version" : version.getId()) + " requires Java " + requested.getMajorVersion()
            + (component == null ? "" : " (" + component + ")")
            + ", but the launcher is running on Java " + current + " and no Mojang runtime was available"
      );
   }

   private void installRuntime(String component, String platform, File runtimeHome) throws IOException {
      JsonObject catalog = this.parseObject(Http.performGet(new URL(RUNTIME_CATALOG_URL), this.proxy), "runtime catalog");
      JsonObject platformObject = this.getObject(catalog, platform);
      if (platformObject == null) {
         throw new IOException("Mojang runtime catalog has no entry for platform '" + platform + "'");
      }

      JsonElement componentElement = platformObject.get(component);
      if (componentElement == null || componentElement.isJsonNull()) {
         throw new IOException("Mojang runtime catalog has no component '" + component + "' for " + platform);
      }

      JsonObject runtimeEntry = this.selectRuntimeEntry(componentElement);
      JsonObject manifest = this.getObject(runtimeEntry, "manifest");
      if (manifest == null || !manifest.has("url")) {
         throw new IOException("Runtime component '" + component + "' does not contain a manifest URL");
      }

      String manifestUrl = manifest.get("url").getAsString();
      String manifestSha1 = manifest.has("sha1") ? manifest.get("sha1").getAsString() : "";
      JsonObject runtimeManifest = this.parseObject(Http.performGet(new URL(manifestUrl), this.proxy), "runtime manifest");
      JsonObject files = this.getObject(runtimeManifest, "files");
      if (files == null) {
         throw new IOException("Runtime manifest for '" + component + "' does not contain files");
      }

      if (!runtimeHome.isDirectory() && !runtimeHome.mkdirs() && !runtimeHome.isDirectory()) {
         throw new IOException("Could not create runtime directory " + runtimeHome);
      }

      // Directories first so links/files always have a parent available.
      for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
         JsonObject descriptor = entry.getValue().getAsJsonObject();
         if ("directory".equals(this.string(descriptor, "type"))) {
            File target = this.safeRuntimeTarget(runtimeHome, entry.getKey());
            if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
               throw new IOException("Could not create runtime directory " + target);
            }
         }
      }

      int downloadedFiles = 0;
      for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
         JsonObject descriptor = entry.getValue().getAsJsonObject();
         String type = this.string(descriptor, "type");
         File target = this.safeRuntimeTarget(runtimeHome, entry.getKey());

         if ("file".equals(type)) {
            JsonObject downloads = this.getObject(descriptor, "downloads");
            JsonObject raw = downloads == null ? null : this.getObject(downloads, "raw");
            if (raw == null || !raw.has("url")) {
               throw new IOException("Runtime file has no raw download: " + entry.getKey());
            }

            String url = raw.get("url").getAsString();
            String sha1 = this.string(raw, "sha1");
            long size = raw.has("size") ? raw.get("size").getAsLong() : 0L;
            this.downloadRuntimeFile(new URL(url), target, sha1, size);
            downloadedFiles++;

            if (descriptor.has("executable") && descriptor.get("executable").getAsBoolean()) {
               if (!target.setExecutable(true, false) && !target.canExecute()) {
                  LOGGER.warn("Couldn't mark runtime file executable: {}", new Object[]{target});
               }
            }
         } else if ("link".equals(type)) {
            this.createRuntimeLink(runtimeHome, target, this.string(descriptor, "target"));
         }
      }

      // A tiny marker is useful when inspecting a portable installation and is
      // harmless if Mojang updates the component later; file SHA-1s are still
      // checked whenever an incomplete runtime has to be reinstalled.
      File marker = new File(runtimeHome, ".minecraft-runtime");
      String markerText = "component=" + component + "\nplatform=" + platform + "\nmanifest=" + manifestUrl
         + "\nsha1=" + manifestSha1 + "\nfiles=" + downloadedFiles + "\n";
      try (FileOutputStream output = new FileOutputStream(marker)) {
         output.write(markerText.getBytes(StandardCharsets.UTF_8));
      }
   }

   private void downloadRuntimeFile(URL url, File target, String sha1, long size) throws IOException {
      Throwable lastFailure = null;
      for (int attempt = 1; attempt <= 5; attempt++) {
         try {
            Sha1Downloadable downloadable = new Sha1Downloadable(this.proxy, url, target, sha1, size, false);
            String result = downloadable.download();
            LOGGER.info("Runtime file {}: {}", target, result);
            return;
         } catch (Throwable t) {
            lastFailure = t;
            LOGGER.warn("Couldn't download runtime file " + url + " (attempt " + attempt + "/5)", t);
         }
      }

      if (lastFailure instanceof IOException) {
         throw (IOException)lastFailure;
      }
      throw new IOException("Could not download runtime file " + url, lastFailure);
   }

   private void createRuntimeLink(File runtimeHome, File target, String linkTarget) throws IOException {
      if (linkTarget == null || linkTarget.isEmpty()) {
         return;
      }

      if (target.getParentFile() != null && !target.getParentFile().isDirectory()) {
         target.getParentFile().mkdirs();
      }

      Path targetPath = target.toPath();
      if (Files.exists(targetPath) || Files.isSymbolicLink(targetPath)) {
         return;
      }

      try {
         Path relativeTarget = Paths.get(linkTarget);
         Files.createSymbolicLink(targetPath, relativeTarget);
      } catch (UnsupportedOperationException | SecurityException e) {
         LOGGER.warn("Couldn't create runtime symlink " + target + " -> " + linkTarget, e);
      }
   }

   private JsonObject selectRuntimeEntry(JsonElement element) throws IOException {
      if (element.isJsonObject()) {
         return element.getAsJsonObject();
      }
      if (!element.isJsonArray()) {
         throw new IOException("Unexpected runtime catalog entry type");
      }

      JsonArray entries = element.getAsJsonArray();
      if (entries.size() == 0) {
         throw new IOException("Runtime catalog entry is empty");
      }

      // Mojang normally publishes one active item. If multiple are present,
      // prefer the first one with a completed availability record and otherwise
      // fall back to the first manifest-bearing entry.
      JsonObject fallback = null;
      for (JsonElement candidateElement : entries) {
         if (!candidateElement.isJsonObject()) {
            continue;
         }
         JsonObject candidate = candidateElement.getAsJsonObject();
         if (this.getObject(candidate, "manifest") == null) {
            continue;
         }
         if (fallback == null) {
            fallback = candidate;
         }
         JsonObject availability = this.getObject(candidate, "availability");
         if (availability != null && availability.has("progress") && availability.get("progress").getAsInt() >= 100) {
            return candidate;
         }
      }

      if (fallback != null) {
         return fallback;
      }
      throw new IOException("Runtime catalog entry has no usable manifest");
   }

   private File getRuntimeHome(String component, String platform) {
      return new File(new File(new File(new File(this.workingDirectory, "runtime"), component), platform), component);
   }

   private File getJavaExecutable(File runtimeHome) {
      if (OperatingSystem.getCurrentPlatform() == OperatingSystem.WINDOWS) {
         File javaw = new File(runtimeHome, "bin/javaw.exe");
         if (javaw.isFile()) {
            return javaw;
         }
         return new File(runtimeHome, "bin/java.exe");
      }
      return new File(runtimeHome, "bin/java");
   }

   private File safeRuntimeTarget(File runtimeHome, String relativePath) throws IOException {
      File target = new File(runtimeHome, relativePath.replace('/', File.separatorChar));
      String root = runtimeHome.getCanonicalPath();
      String child = target.getCanonicalPath();
      if (!child.equals(root) && !child.startsWith(root + File.separator)) {
         throw new IOException("Runtime manifest attempted to escape runtime directory: " + relativePath);
      }
      return target;
   }

   private String getPlatformKey() throws IOException {
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
      boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64");
      boolean x86 = arch.equals("x86") || arch.contains("i386") || arch.contains("i486") || arch.contains("i586") || arch.contains("i686");

      switch (OperatingSystem.getCurrentPlatform()) {
         case WINDOWS:
            if (arm64) return "windows-arm64";
            if (x64) return "windows-x64";
            if (x86) return "windows-x86";
            break;
         case OSX:
            return arm64 ? "mac-os-arm64" : "mac-os";
         case LINUX:
            return x86 ? "linux-i386" : "linux";
         default:
            break;
      }
      throw new IOException("Unsupported Java runtime platform: " + System.getProperty("os.name") + " / " + arch);
   }

   private int currentJavaMajor() {
      String version = System.getProperty("java.specification.version", "0");
      try {
         if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2));
         }
         int dot = version.indexOf('.');
         return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
      } catch (NumberFormatException e) {
         return 0;
      }
   }

   private String componentForMajor(int major) {
      if (major <= 8) return "jre-legacy";
      if (major == 16) return "java-runtime-alpha";
      if (major == 17) return "java-runtime-gamma";
      if (major == 21) return "java-runtime-delta";
      if (major >= 25) return "java-runtime-epsilon";
      return null;
   }

   private JsonObject parseObject(String json, String description) throws IOException {
      try {
         JsonElement element = new JsonParser().parse(json);
         if (!element.isJsonObject()) {
            throw new IOException("Mojang " + description + " was not a JSON object");
         }
         return element.getAsJsonObject();
      } catch (RuntimeException e) {
         throw new IOException("Could not parse Mojang " + description, e);
      }
   }

   private JsonObject getObject(JsonObject parent, String key) {
      if (parent == null) return null;
      JsonElement element = parent.get(key);
      return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
   }

   private String string(JsonObject object, String key) {
      if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
         return null;
      }
      return object.get(key).getAsString();
   }
}
