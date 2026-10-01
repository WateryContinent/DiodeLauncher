package net.minecraft.launcher.ui.tabs.website;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads a modern JavaFX runtime for the embedded web tab.
 *
 * JavaFX stopped shipping inside the JDK after Java 8. To keep this launcher
 * portable on JDK 21, the required OpenJFX jars are cached beside the normal
 * launcher data on first use and loaded in an isolated class loader.
 */
public final class JavaFxRuntime {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final String JAVAFX_VERSION = "21.0.12";
   private static final String MAVEN_BASE = "https://repo.maven.apache.org/maven2/org/openjfx/";
   private static final String[] MODULES = new String[]{
      "javafx-base",
      "javafx-graphics",
      "javafx-controls",
      "javafx-media",
      "javafx-web",
      "javafx-swing"
   };

   private JavaFxRuntime() {
   }

   public static ClassLoader findOrInstall(File workingDirectory, ClassLoader parent) {
      try {
         Class.forName("javafx.embed.swing.JFXPanel", false, parent);
         Class.forName("javafx.scene.web.WebView", false, parent);
         return parent;
      } catch (ClassNotFoundException ignored) {
      }

      String classifier = getPlatformClassifier();
      if (classifier == null) {
         LOGGER.info("No downloadable JavaFX WebView runtime is configured for this platform");
         return null;
      }

      File runtimeDir = new File(workingDirectory, "runtime/javafx/" + JAVAFX_VERSION + "/" + classifier);
      if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs()) {
         LOGGER.warn("Could not create JavaFX runtime directory {}", new Object[]{runtimeDir});
         return null;
      }

      List<URL> urls = new ArrayList<URL>();
      for (String module : MODULES) {
         String fileName = module + "-" + JAVAFX_VERSION + "-" + classifier + ".jar";
         File target = new File(runtimeDir, fileName);
         if (!target.isFile() || target.length() < 1024L) {
            String url = MAVEN_BASE + module + "/" + JAVAFX_VERSION + "/" + fileName;
            try {
               download(url, target);
            } catch (Exception e) {
               LOGGER.warn("Could not download JavaFX component " + module + " from " + url, e);
               return null;
            }
         }

         try {
            urls.add(target.toURI().toURL());
         } catch (Exception e) {
            LOGGER.warn("Could not add JavaFX component " + target, e);
            return null;
         }
      }

      try {
         URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
         Class.forName("javafx.embed.swing.JFXPanel", true, loader);
         Class.forName("javafx.scene.web.WebView", true, loader);
         return loader;
      } catch (Throwable e) {
         LOGGER.warn("Downloaded JavaFX runtime could not be loaded", e);
         return null;
      }
   }

   private static void download(String url, File target) throws Exception {
      File temp = new File(target.getParentFile(), target.getName() + ".part");
      LOGGER.info("Downloading embedded browser component {}", new Object[]{target.getName()});

      HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(60000);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "MinecraftLauncher/1.6.44-custom");

      int response = connection.getResponseCode();
      if (response < 200 || response >= 300) {
         connection.disconnect();
         throw new IllegalStateException("HTTP " + response);
      }

      try (InputStream in = new BufferedInputStream(connection.getInputStream());
           FileOutputStream out = new FileOutputStream(temp)) {
         byte[] buffer = new byte[65536];
         int read;
         while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
               out.write(buffer, 0, read);
            }
         }
      } finally {
         connection.disconnect();
      }

      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
   }

   private static String getPlatformClassifier() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
      boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64");

      if (os.contains("win")) {
         if (arm64) {
            return "win-aarch64";
         }
         if (x64) {
            return "win";
         }
      } else if (os.contains("mac")) {
         return arm64 ? "mac-aarch64" : "mac";
      } else if (os.contains("linux")) {
         return arm64 ? "linux-aarch64" : "linux";
      }

      return null;
   }
}
