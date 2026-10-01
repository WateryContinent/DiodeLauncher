package net.minecraft.launcher;

import com.mojang.launcher.OperatingSystem;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
   private static final Logger LOGGER = LogManager.getLogger();

   public static void main(final String[] args) {
      LOGGER.debug("main() called!");
      Thread thread = new Thread(new Runnable() {
         @Override
         public void run() {
            Main.startLauncher(args);
         }
      });
      thread.start();

      try {
         thread.join();
      } catch (InterruptedException var3) {
      }
   }

   private static void startLauncher(String[] args) {
      OptionParser parser = new OptionParser();
      parser.allowsUnrecognizedOptions();
      parser.accepts("winTen");
      OptionSpec<Void> offlineOption = parser.accepts("offline");
      OptionSpec<String> offlineUserOption = parser.accepts("offlineUser").withRequiredArg().defaultsTo("Player");
      OptionSpec<String> proxyHostOption = parser.accepts("proxyHost").withRequiredArg();
      OptionSpec<Integer> proxyPortOption = parser.accepts("proxyPort").withRequiredArg().defaultsTo("8080").ofType(Integer.class);
      OptionSpec<File> workDirOption = parser.accepts("workDir").withRequiredArg().ofType(File.class).defaultsTo(getWorkingDirectory());
      OptionSpec<String> nonOption = parser.nonOptions();
      final OptionSet optionSet = parser.parse(args);
      final List<String> leftoverArgs = new ArrayList<>(optionSet.valuesOf(nonOption));
      if (optionSet.has(offlineOption) || optionSet.has(offlineUserOption)) {
         leftoverArgs.add("--offline");
         leftoverArgs.add("--offlineUser");
         leftoverArgs.add(optionSet.valueOf(offlineUserOption));
      }
      String hostName = optionSet.valueOf(proxyHostOption);
      Proxy proxy = Proxy.NO_PROXY;
      if (hostName != null) {
         try {
            proxy = new Proxy(Type.SOCKS, new InetSocketAddress(hostName, optionSet.valueOf(proxyPortOption)));
         } catch (Exception var12) {
         }
      }

      final File workingDirectory = optionSet.valueOf(workDirOption);
      workingDirectory.mkdirs();
      LOGGER.debug("About to create JFrame.");
      final Proxy finalProxy = proxy;
      SwingUtilities.invokeLater(new Runnable() {
         @Override
         public void run() {
            JFrame frame = new JFrame();
            frame.setTitle("Minecraft Launcher " + LauncherConstants.getVersionName());
            frame.setPreferredSize(new Dimension(900, 580));

            try {
               InputStream in = Launcher.class.getResourceAsStream("/favicon.png");
               if (in != null) {
                  frame.setIconImage(ImageIO.read(in));
               }
            } catch (IOException var3) {
            }

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (optionSet.has("winTen")) {
               System.setProperty("os.name", "Windows 10");
               System.setProperty("os.version", "10.0");
            }

            Main.LOGGER.debug("Starting up launcher.");
            Launcher launcher = new Launcher(frame, workingDirectory, finalProxy, null, leftoverArgs.toArray(new String[leftoverArgs.size()]), 100);
            if (optionSet.has("winTen")) {
               launcher.setWinTenHack();
            }

            frame.setLocationRelativeTo(null);
         }
      });
      LOGGER.debug("End of main.");
   }

   public static File getWorkingDirectory() {
      // When launcher.jar is executed directly, make it portable by default:
      // profiles, versions, libraries, assets and Mojang runtimes all live
      // beside the distributed JAR. --workDir still overrides this.
      File portableDirectory = getPortableJarDirectory();
      if (portableDirectory != null) {
         return portableDirectory;
      }

      String userHome = System.getProperty("user.home", ".");
      File workingDirectory;
      switch (OperatingSystem.getCurrentPlatform()) {
         case LINUX:
            workingDirectory = new File(userHome, ".minecraft/");
            break;
         case WINDOWS:
            String applicationData = System.getenv("APPDATA");
            String folder = applicationData != null ? applicationData : userHome;
            workingDirectory = new File(folder, ".minecraft/");
            break;
         case OSX:
            workingDirectory = new File(userHome, "Library/Application Support/minecraft");
            break;
         default:
            workingDirectory = new File(userHome, "minecraft/");
      }

      return workingDirectory;
   }

   private static File getPortableJarDirectory() {
      try {
         if (Main.class.getProtectionDomain() == null
            || Main.class.getProtectionDomain().getCodeSource() == null
            || Main.class.getProtectionDomain().getCodeSource().getLocation() == null) {
            return null;
         }

         URI location = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
         File source = new File(location);
         if (source.isFile() && source.getName().toLowerCase().endsWith(".jar")) {
            File parent = source.getAbsoluteFile().getParentFile();
            if (parent != null) {
               LOGGER.info("Standalone launcher detected; using portable working directory {}", new Object[]{parent});
               return parent;
            }
         }
      } catch (URISyntaxException | SecurityException e) {
         LOGGER.warn("Couldn't determine launcher JAR directory; using normal Minecraft directory", e);
      }
      return null;
   }
}
