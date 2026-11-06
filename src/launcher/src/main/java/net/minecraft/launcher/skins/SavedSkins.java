package net.minecraft.launcher.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The player's saved skins: looks they can switch back to with one click. Kept on this computer in
 * diode_saved_skins.json in the launcher folder, with the skin pictures inside it, so they're
 * shared by every account.
 */
public class SavedSkins {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private final File file;
   private final List<SavedSkin> skins = new ArrayList<>();

   public SavedSkins(File workingDirectory) {
      this.file = new File(workingDirectory, "diode_saved_skins.json");
      this.load();
   }

   public List<SavedSkin> getSkins() {
      return Collections.unmodifiableList(this.skins);
   }

   /** Adds a look at the front of the list and saves. The skin must be a 64x64 or 64x32 picture. */
   public SavedSkin add(String name, BufferedImage skin, boolean slim, String capeId) throws IOException {
      SavedSkin saved = new SavedSkin();
      saved.id = UUID.randomUUID().toString();
      saved.name = name;
      saved.slim = slim;
      saved.capeId = capeId;
      saved.created = System.currentTimeMillis();
      saved.skin = Base64.getEncoder().encodeToString(toPng(skin));
      saved.image = skin;
      this.skins.add(0, saved);
      this.save();
      return saved;
   }

   public void rename(SavedSkin skin, String name) throws IOException {
      skin.name = name;
      this.save();
   }

   public void remove(SavedSkin skin) throws IOException {
      this.skins.remove(skin);
      this.save();
   }

   private void load() {
      if (!this.file.isFile()) {
         return;
      }

      try {
         Stored stored = GSON.fromJson(new String(Files.readAllBytes(this.file.toPath()), StandardCharsets.UTF_8), Stored.class);
         if (stored == null || stored.skins == null) {
            return;
         }
         for (SavedSkin skin : stored.skins) {
            if (skin == null || skin.skin == null) {
               continue;
            }
            try {
               skin.image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(skin.skin)));
            } catch (IOException | IllegalArgumentException e) {
               skin.image = null;
            }
            if (skin.image != null) {
               if (skin.name == null || skin.name.trim().isEmpty()) {
                  skin.name = "Skin";
               }
               this.skins.add(skin);
            }
         }
      } catch (IOException | JsonParseException e) {
         LOGGER.warn("Couldn't read saved skins from " + this.file, e);
      }
   }

   private void save() throws IOException {
      Stored stored = new Stored();
      stored.skins = this.skins;
      File temp = new File(this.file.getPath() + ".tmp");
      Files.write(temp.toPath(), GSON.toJson(stored).getBytes(StandardCharsets.UTF_8));
      Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
   }

   public static byte[] toPng(BufferedImage image) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
   }

   private static class Stored {
      private int version = 1;
      private List<SavedSkin> skins;
   }

   public static class SavedSkin {
      private String id;
      private String name;
      private boolean slim;
      private String capeId;
      private long created;
      private String skin;
      private transient BufferedImage image;

      public String getName() {
         return this.name;
      }

      public boolean isSlim() {
         return this.slim;
      }

      /** The cape worn with this look, or null for no cape. */
      public String getCapeId() {
         return this.capeId;
      }

      public BufferedImage getImage() {
         return this.image;
      }

      public byte[] getPng() {
         return Base64.getDecoder().decode(this.skin);
      }

      @Override
      public String toString() {
         return this.name;
      }
   }
}
