import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds Diode's launcher.jar and packages it, with a bundled Java runtime,
 * as an installer for the current OS.
 *
 * <p>Run the "Build Installer" configuration in IntelliJ, or from the project
 * folder with a JDK 21:</p>
 *
 * <pre>java installer/src/BuildInstaller.java [--type TYPE] [--use-existing-jar] [--wix-dir FOLDER]</pre>
 *
 * <ul>
 *   <li>Windows: {@code exe} (default) or {@code msi}, both need WiX Toolset 3.14.</li>
 *   <li>macOS: {@code dmg} (default) or {@code pkg}.</li>
 *   <li>Linux: {@code deb} or {@code rpm}.</li>
 *   <li>Any OS: {@code app-image}, a portable folder zipped up.</li>
 * </ul>
 *
 * <p>jpackage can only build for the OS it runs on, so Windows installers are
 * made on Windows and macOS ones on a Mac. The release workflow in
 * .github/workflows/release.yml builds all of them on GitHub's machines.</p>
 *
 * <p>Output goes to dist/launcher.jar and dist/installer/.</p>
 */
public final class BuildInstaller {
   private static final String APP_NAME = "Diode Launcher";
   private static final String FILE_PREFIX = "Diode-Launcher";
   private static final String VENDOR = "WateryContinent";
   private static final String DESCRIPTION = "Diode - Minecraft Legacy Launcher";
   // Never change this: Windows uses it to recognise new installers as upgrades.
   private static final String WINDOWS_UPGRADE_UUID = "b08dff95-e019-49ec-ab7a-948fd1df0012";
   private static final String MAC_BUNDLE_ID = "io.github.waterycontinent.diode";
   // Modules the launcher, its old libraries and the downloaded JavaFX WebView need.
   private static final String RUNTIME_MODULES = String.join(",",
      "java.se", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.jsobject", "jdk.xml.dom",
      "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.charsets", "jdk.localedata", "jdk.zipfs",
      "jdk.net", "jdk.management", "jdk.accessibility", "jdk.naming.dns"
   );

   private final Path root;
   private final Path build;
   private final Path dist;
   private final Path launcherJar;
   private final Path javaHome = Path.of(System.getProperty("java.home"));
   private final String os;
   private final String arch;

   private BuildInstaller(Path root) {
      this.root = root;
      this.build = root.resolve("build/installer");
      this.dist = root.resolve("dist");
      this.launcherJar = this.dist.resolve("launcher.jar");
      String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      this.os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
      String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
      this.arch = osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "x64";
   }

   public static void main(String[] args) throws Exception {
      String type = null;
      boolean useExistingJar = false;
      String wixDir = null;
      for (int i = 0; i < args.length; i++) {
         switch (args[i]) {
            case "--type":
               type = args[++i];
               break;
            case "--use-existing-jar":
               useExistingJar = true;
               break;
            case "--wix-dir":
               wixDir = args[++i];
               break;
            default:
               fail("Unknown option " + args[i] + "\nUsage: java installer/src/BuildInstaller.java [--type TYPE] [--use-existing-jar] [--wix-dir FOLDER]");
         }
      }

      try {
         new BuildInstaller(findProjectRoot()).run(type, useExistingJar, wixDir);
      } catch (BuildException e) {
         fail(e.getMessage());
      }
   }

   private void run(String requestedType, boolean useExistingJar, String wixDir) throws Exception {
      String type = requestedType != null ? requestedType : this.defaultType();
      List<String> supported = this.supportedTypes();
      if (!supported.contains(type)) {
         throw new BuildException("Can't build '" + type + "' on " + this.os + ". Choose one of " + supported
            + (type.equals("dmg") || type.equals("pkg") ? ". macOS installers have to be built on a Mac (the GitHub release workflow does this)." : "")
            + (type.equals("exe") || type.equals("msi") ? ". Windows installers have to be built on Windows (the GitHub release workflow does this)." : ""));
      }

      for (String tool : new String[]{"javac", "jar", "jlink", "jpackage"}) {
         if (!Files.isRegularFile(this.tool(tool))) {
            throw new BuildException("Run this with a full JDK 21 (couldn't find " + this.tool(tool) + ").");
         }
      }

      if (Runtime.version().feature() != 21) {
         System.out.println("Warning: running on Java " + Runtime.version().feature() + "; the bundled runtime will be that version, not Java 21.");
      }

      String version = this.readVersion();
      System.out.println("Packaging " + APP_NAME + " " + version + " (" + type + ", " + this.os + " " + this.arch + ")");
      System.out.println("  JDK: " + this.javaHome);
      deleteRecursively(this.build);
      Files.createDirectories(this.build);
      Files.createDirectories(this.dist);

      if (useExistingJar) {
         if (!Files.isRegularFile(this.launcherJar)) {
            throw new BuildException("--use-existing-jar was given but " + this.launcherJar + " doesn't exist.");
         }
         System.out.println("Using existing " + this.launcherJar);
      } else {
         this.buildLauncherJar();
      }

      System.out.println("Creating bundled Java runtime...");
      Path runtime = this.build.resolve("runtime");
      // Native commands (bin/java) are kept on purpose: the self-updater and
      // older Minecraft versions without a Mojang runtime both need them.
      this.exec(null, null, this.tool("jlink").toString(),
         "--add-modules", RUNTIME_MODULES, "--strip-debug", "--no-man-pages", "--no-header-files",
         "--compress=zip-6", "--output", runtime.toString());

      Path input = this.build.resolve("input");
      Files.createDirectories(input);
      Files.copy(this.launcherJar, input.resolve("launcher.jar"));
      Path icon = this.writeIcon();

      Path jpackageOut = this.build.resolve("out");
      List<String> command = new ArrayList<>(Arrays.asList(
         this.tool("jpackage").toString(),
         "--type", type,
         "--name", APP_NAME,
         "--app-version", version,
         "--vendor", VENDOR,
         "--description", DESCRIPTION,
         "--input", input.toString(),
         "--main-jar", "launcher.jar",
         "--main-class", "net.minecraft.launcher.Main",
         "--runtime-image", runtime.toString(),
         "--icon", icon.toString(),
         "--dest", jpackageOut.toString()
      ));
      if (!type.equals("app-image")) {
         // Uninstalling deletes the install folder, so installed copies keep
         // game data in the user's profile instead (see Main.getWorkingDirectory).
         command.addAll(Arrays.asList("--java-options", "-Ddiode.installed=true"));
      }

      Map<String, String> environment = null;
      if (this.os.equals("windows") && !type.equals("app-image")) {
         command.addAll(Arrays.asList(
            "--win-per-user-install",
            "--win-menu", "--win-menu-group", "Diode",
            "--win-shortcut", "--win-shortcut-prompt",
            "--win-upgrade-uuid", WINDOWS_UPGRADE_UUID
         ));
         environment = this.withWixOnPath(wixDir);
      } else if (this.os.equals("macos")) {
         command.addAll(Arrays.asList("--mac-package-identifier", MAC_BUNDLE_ID, "--mac-package-name", APP_NAME));
      } else if (this.os.equals("linux") && !type.equals("app-image")) {
         command.addAll(Arrays.asList("--linux-shortcut", "--linux-menu-group", "Game"));
      }

      System.out.println("Running jpackage...");
      this.exec(null, environment, command.toArray(new String[0]));

      Path output = this.dist.resolve("installer");
      Files.createDirectories(output);
      Path result;
      if (type.equals("app-image")) {
         result = output.resolve(FILE_PREFIX + "-" + version + "-" + this.os + "-" + this.arch + "-portable.zip");
         Files.deleteIfExists(result);
         this.zipAppImage(jpackageOut, result);
      } else {
         Path built;
         try (Stream<Path> files = Files.list(jpackageOut)) {
            built = files.filter(p -> p.getFileName().toString().endsWith("." + type)).findFirst()
               .orElseThrow(() -> new BuildException("jpackage didn't produce a ." + type + " file"));
         }
         result = output.resolve(FILE_PREFIX + "-" + version + "-" + this.os + "-" + this.arch + "." + type);
         Files.move(built, result, StandardCopyOption.REPLACE_EXISTING);
      }

      System.out.println("Done: " + this.launcherJar);
      System.out.println("      " + result);
   }

   private String defaultType() {
      switch (this.os) {
         case "windows":
            return "exe";
         case "macos":
            return "dmg";
         default:
            return "app-image";
      }
   }

   private List<String> supportedTypes() {
      switch (this.os) {
         case "windows":
            return Arrays.asList("exe", "msi", "app-image");
         case "macos":
            return Arrays.asList("dmg", "pkg", "app-image");
         default:
            return Arrays.asList("deb", "rpm", "app-image");
      }
   }

   private String readVersion() throws IOException {
      String manifest = Files.readString(this.root.resolve("src/launcher/src/main/resources/META-INF/MANIFEST.MF"));
      Matcher matcher = Pattern.compile("Implementation-Version:[^\\r\\n]*?(\\d+(\\.\\d+){0,2})").matcher(manifest);
      if (!matcher.find()) {
         throw new BuildException("Couldn't read Implementation-Version from the launcher MANIFEST.MF");
      }

      return matcher.group(1);
   }

   /** Same contents as IntelliJ's "launcher-release" artifact. */
   private void buildLauncherJar() throws Exception {
      System.out.println("Compiling launcher...");
      Path dependencies = this.root.resolve("src/launcher/lib/original-launcher-dependencies.jar");
      Path classes = this.build.resolve("classes");
      Path contents = this.build.resolve("jar");
      Files.createDirectories(classes);
      Files.createDirectories(contents);

      // javac argument files treat backslashes as escapes, so use forward slashes.
      List<String> sources = new ArrayList<>();
      try (Stream<Path> files = Files.walk(this.root.resolve("src/launcher/src/main/java"))) {
         files.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add('"' + p.toString().replace('\\', '/') + '"'));
      }
      Path sourceList = this.build.resolve("sources.txt");
      Files.write(sourceList, sources, StandardCharsets.UTF_8);
      this.exec(null, null, this.tool("javac").toString(), "-nowarn", "-encoding", "UTF-8", "-d", classes.toString(),
         "-cp", dependencies.toString(), "@" + sourceList);

      System.out.println("Assembling launcher.jar...");
      this.exec(contents.toFile(), null, this.tool("jar").toString(), "xf", dependencies.toString());
      copyTree(classes, contents);
      copyTree(this.root.resolve("src/launcher/src/main/resources"), contents);
      try (Stream<Path> files = Files.list(contents.resolve("META-INF"))) {
         for (Path file : (Iterable<Path>)files::iterator) {
            String name = file.getFileName().toString().toUpperCase(Locale.ROOT);
            if (name.equals("MANIFEST.MF") || name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) {
               Files.delete(file);
            }
         }
      }

      Files.deleteIfExists(this.launcherJar);
      this.exec(null, null, this.tool("jar").toString(), "cfm", this.launcherJar.toString(),
         this.root.resolve("src/launcher/src/main/resources/META-INF/MANIFEST.MF").toString(), "-C", contents.toString(), ".");
      System.out.println("Built " + this.launcherJar);
   }

   /** jpackage wants .ico on Windows and .icns on macOS; both can wrap the PNG favicon. */
   private Path writeIcon() throws IOException {
      byte[] png = Files.readAllBytes(this.root.resolve("src/launcher/src/main/resources/favicon.png"));
      int size = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      Path icon;
      if (this.os.equals("windows")) {
         icon = this.build.resolve("diode.ico");
         // ICO header + one directory entry pointing at the embedded PNG (little-endian).
         out.write(new byte[]{0, 0, 1, 0, 1, 0});
         out.writeByte(size >= 256 ? 0 : size);
         out.writeByte(size >= 256 ? 0 : size);
         out.write(new byte[]{0, 0, 1, 0, 32, 0});
         out.write(littleEndian(png.length));
         out.write(littleEndian(22));
         out.write(png);
      } else if (this.os.equals("macos")) {
         icon = this.build.resolve("diode.icns");
         Map<Integer, String> types = new HashMap<>();
         types.put(16, "icp4");
         types.put(32, "icp5");
         types.put(64, "icp6");
         types.put(128, "ic07");
         types.put(256, "ic08");
         types.put(512, "ic09");
         types.put(1024, "ic10");
         String type = types.get(size);
         if (type == null) {
            throw new BuildException("favicon.png must be 16, 32, 64, 128, 256, 512 or 1024 pixels square for a macOS icon (it is " + size + ")");
         }
         out.writeBytes("icns");
         out.writeInt(8 + 8 + png.length);
         out.writeBytes(type);
         out.writeInt(8 + png.length);
         out.write(png);
      } else {
         icon = this.build.resolve("diode.png");
         out.write(png);
      }

      Files.write(icon, bytes.toByteArray());
      return icon;
   }

   /** Puts WiX 3's candle.exe/light.exe on jpackage's PATH if they aren't already. */
   private Map<String, String> withWixOnPath(String wixDir) throws Exception {
      Map<String, String> environment = new HashMap<>(System.getenv());
      String pathKey = environment.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
      String path = environment.getOrDefault(pathKey, "");
      for (String entry : path.split(File.pathSeparator)) {
         if (!entry.isEmpty() && Files.isRegularFile(Path.of(entry, "candle.exe"))) {
            return environment;
         }
      }

      List<String> candidates = new ArrayList<>();
      candidates.add(wixDir);
      // WIX is set by the WiX installer, or by hand for the binaries zip.
      // Terminals and IDEs opened before it was set won't have it, so also ask the registry.
      for (String wix : new String[]{System.getenv("WIX"), this.readUserEnvironmentVariable("WIX")}) {
         if (wix != null) {
            candidates.add(wix);
            candidates.add(Path.of(wix, "bin").toString());
         }
      }

      String programFiles = System.getenv("ProgramFiles(x86)");
      if (programFiles != null && Files.isDirectory(Path.of(programFiles))) {
         try (Stream<Path> dirs = Files.list(Path.of(programFiles))) {
            dirs.filter(p -> p.getFileName().toString().startsWith("WiX Toolset v3"))
               .sorted(Comparator.reverseOrder())
               .forEach(p -> candidates.add(p.resolve("bin").toString()));
         }
      }

      for (String candidate : candidates) {
         if (candidate != null && Files.isRegularFile(Path.of(candidate, "candle.exe"))) {
            System.out.println("  WiX: " + candidate);
            environment.put(pathKey, candidate + File.pathSeparator + path);
            return environment;
         }
      }

      throw new BuildException("WiX Toolset 3.14 is needed for Windows installers but wasn't found.\n"
         + "Install it from https://github.com/wixtoolset/wix3/releases and put its bin folder on PATH,\n"
         + "set the WIX environment variable to its folder, or pass --wix-dir <folder>.\n"
         + "JDK 21's jpackage doesn't support WiX 4 or newer. To skip the installer, use --type app-image.");
   }

   private String readUserEnvironmentVariable(String name) {
      try {
         Process process = new ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", name).redirectErrorStream(true).start();
         String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
         process.waitFor();
         Matcher matcher = Pattern.compile(name + "\\s+REG_(?:EXPAND_)?SZ\\s+(.+)").matcher(output);
         return matcher.find() ? matcher.group(1).trim() : null;
      } catch (IOException | InterruptedException e) {
         return null;
      }
   }

   private void zipAppImage(Path jpackageOut, Path zip) throws Exception {
      Path appImage;
      try (Stream<Path> files = Files.list(jpackageOut)) {
         appImage = files.findFirst().orElseThrow(() -> new BuildException("jpackage didn't produce an app image"));
      }

      if (this.os.equals("macos")) {
         // ditto keeps the .app's executable permissions, which java.util.zip would drop.
         this.exec(null, null, "ditto", "-c", "-k", "--keepParent", appImage.toString(), zip.toString());
         return;
      }

      try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)); Stream<Path> files = Files.walk(appImage)) {
         for (Path file : (Iterable<Path>)files::iterator) {
            String name = jpackageOut.relativize(file).toString().replace('\\', '/');
            if (Files.isDirectory(file)) {
               out.putNextEntry(new ZipEntry(name + "/"));
            } else {
               out.putNextEntry(new ZipEntry(name));
               Files.copy(file, out);
            }
            out.closeEntry();
         }
      }
   }

   private Path tool(String name) {
      return this.javaHome.resolve("bin").resolve(this.os.equals("windows") ? name + ".exe" : name);
   }

   private void exec(File directory, Map<String, String> environment, String... command) throws Exception {
      ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
      if (directory != null) {
         builder.directory(directory);
      }

      if (environment != null) {
         builder.environment().clear();
         builder.environment().putAll(environment);
      }

      int code = builder.start().waitFor();
      if (code != 0) {
         throw new BuildException(Path.of(command[0]).getFileName() + " failed with exit code " + code);
      }
   }

   private static Path findProjectRoot() {
      for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
         if (Files.isDirectory(dir.resolve("src/launcher/src/main/java"))) {
            return dir;
         }
      }

      throw new BuildException("Run this from the DiodeLauncher project folder.");
   }

   private static void copyTree(Path from, Path to) throws IOException {
      try (Stream<Path> files = Files.walk(from)) {
         for (Path file : (Iterable<Path>)files::iterator) {
            Path target = to.resolve(from.relativize(file).toString());
            if (Files.isDirectory(file)) {
               Files.createDirectories(target);
            } else {
               Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
         }
      }
   }

   private static void deleteRecursively(Path path) throws IOException {
      if (!Files.exists(path)) {
         return;
      }

      try (Stream<Path> files = Files.walk(path)) {
         for (Path file : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) {
            Files.delete(file);
         }
      }
   }

   private static byte[] littleEndian(int value) {
      return new byte[]{(byte)value, (byte)(value >> 8), (byte)(value >> 16), (byte)(value >> 24)};
   }

   private static void fail(String message) {
      System.err.println(message);
      System.exit(1);
   }

   private static class BuildException extends RuntimeException {
      BuildException(String message) {
         super(message);
      }
   }
}
