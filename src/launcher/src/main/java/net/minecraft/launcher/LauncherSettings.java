package net.minecraft.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diode-specific launcher settings.
 *
 * <p>These live in their own file rather than launcher_profiles.json so the
 * official launcher (which shares that file in a normal .minecraft folder)
 * can't strip or trip over them.</p>
 */
public class LauncherSettings {
   private static final Logger LOGGER = LogManager.getLogger();
   public static final String FILE_NAME = "diode_settings.json";
   private final File file;
   private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
   private LauncherSettings.Data data = new LauncherSettings.Data();

   public LauncherSettings(File workingDirectory) {
      this.file = new File(workingDirectory, FILE_NAME);
      this.load();
   }

   private void load() {
      if (!this.file.isFile()) {
         return;
      }

      try {
         LauncherSettings.Data loaded = this.gson.fromJson(FileUtils.readFileToString(this.file, Charsets.UTF_8), LauncherSettings.Data.class);
         if (loaded != null) {
            this.data = loaded;
         }
      } catch (IOException | JsonParseException e) {
         LOGGER.warn("Couldn't read " + this.file + "; using default launcher settings", e);
      }
   }

   public synchronized void save() {
      try {
         FileUtils.writeStringToFile(this.file, this.gson.toJson(this.data), Charsets.UTF_8);
      } catch (IOException e) {
         LOGGER.warn("Couldn't save launcher settings to " + this.file, e);
      }
   }

   public synchronized boolean isCheckForUpdates() {
      return this.data.checkForUpdates;
   }

   public synchronized void setCheckForUpdates(boolean checkForUpdates) {
      this.data.checkForUpdates = checkForUpdates;
      this.save();
   }

   public synchronized String getSkippedUpdateVersion() {
      return this.data.skippedUpdateVersion;
   }

   public synchronized void setSkippedUpdateVersion(String skippedUpdateVersion) {
      this.data.skippedUpdateVersion = skippedUpdateVersion;
      this.save();
   }

   private static class Data {
      private boolean checkForUpdates = true;
      private String skippedUpdateVersion;
   }
}
