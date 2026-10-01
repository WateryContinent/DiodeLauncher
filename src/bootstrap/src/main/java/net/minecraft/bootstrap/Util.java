package net.minecraft.bootstrap;

import java.io.File;

public class Util {
   public static final String APPLICATION_NAME = "minecraft";

   public static Util.OS getPlatform() {
      String osName = System.getProperty("os.name").toLowerCase();
      if (osName.contains("win")) {
         return Util.OS.WINDOWS;
      } else if (osName.contains("mac")) {
         return Util.OS.MACOS;
      } else if (osName.contains("linux")) {
         return Util.OS.LINUX;
      } else {
         return osName.contains("unix") ? Util.OS.LINUX : Util.OS.UNKNOWN;
      }
   }

   public static File getWorkingDirectory() {
      String userHome = System.getProperty("user.home", ".");
      File workingDirectory;
      switch (getPlatform()) {
         case LINUX:
         case SOLARIS:
            workingDirectory = new File(userHome, ".minecraft/");
            break;
         case WINDOWS:
            String applicationData = System.getenv("APPDATA");
            String folder = applicationData != null ? applicationData : userHome;
            workingDirectory = new File(folder, ".minecraft/");
            break;
         case MACOS:
            workingDirectory = new File(userHome, "Library/Application Support/minecraft");
            break;
         default:
            workingDirectory = new File(userHome, "minecraft/");
      }

      return workingDirectory;
   }

   public enum OS {
      WINDOWS,
      MACOS,
      SOLARIS,
      LINUX,
      UNKNOWN;
   }
}
