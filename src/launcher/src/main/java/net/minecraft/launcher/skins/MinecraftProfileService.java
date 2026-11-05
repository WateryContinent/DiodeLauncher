package net.minecraft.launcher.skins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Talks to Mojang's Minecraft profile API (the same one minecraft.net uses) to read and change
 * the signed-in player's skin and cape. Every call needs the account's Minecraft access token.
 */
public class MinecraftProfileService {
   /** Overridable for testing against a local server. */
   private static final String BASE_URL = System.getProperty("diode.servicesUrl", "https://api.minecraftservices.com");
   private static final int TIMEOUT_MS = 15000;
   private static final Map<String, BufferedImage> TEXTURE_CACHE = new ConcurrentHashMap<>();
   private final Proxy proxy;

   public MinecraftProfileService(Proxy proxy) {
      this.proxy = proxy;
   }

   public Profile getProfile(String accessToken) throws ProfileException {
      return Profile.parse(this.request("GET", "/minecraft/profile", accessToken, null, null));
   }

   /** Uploads a skin file (a 64x64 or 64x32 PNG). */
   public Profile uploadSkin(String accessToken, File file, SkinModel model) throws ProfileException {
      byte[] png;
      try {
         png = Files.readAllBytes(file.toPath());
      } catch (IOException e) {
         throw new ProfileException("Couldn't read " + file.getName() + ": " + e.getMessage(), e);
      }

      String boundary = "----DiodeSkin" + UUID.randomUUID().toString().replace("-", "");
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try {
         body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + model.apiName() + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
         body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
         body.write(png);
         body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
         throw new ProfileException("Couldn't prepare the skin upload", e);
      }

      return Profile.parse(this.request("POST", "/minecraft/profile/skins", accessToken, "multipart/form-data; boundary=" + boundary, body.toByteArray()));
   }

   /** Switches the current skin between the classic and slim arm models, keeping the same picture. */
   public Profile changeModel(String accessToken, String skinUrl, SkinModel model) throws ProfileException {
      JsonObject json = new JsonObject();
      json.addProperty("variant", model.apiName());
      json.addProperty("url", skinUrl);
      return Profile.parse(this.request("POST", "/minecraft/profile/skins", accessToken, "application/json", json.toString().getBytes(StandardCharsets.UTF_8)));
   }

   /** Goes back to a default skin. */
   public Profile resetSkin(String accessToken) throws ProfileException {
      return Profile.parse(this.request("DELETE", "/minecraft/profile/skins/active", accessToken, null, null));
   }

   /** Shows one of the player's capes, or hides the cape when {@code capeId} is null. */
   public Profile setCape(String accessToken, String capeId) throws ProfileException {
      if (capeId == null) {
         return Profile.parse(this.request("DELETE", "/minecraft/profile/capes/active", accessToken, null, null));
      }

      JsonObject json = new JsonObject();
      json.addProperty("capeId", capeId);
      return Profile.parse(this.request("PUT", "/minecraft/profile/capes/active", accessToken, "application/json", json.toString().getBytes(StandardCharsets.UTF_8)));
   }

   /** Downloads a skin or cape picture (cached, since they never change at a given URL). */
   public BufferedImage downloadTexture(String url) throws ProfileException {
      if (url == null) {
         return null;
      }

      BufferedImage cached = TEXTURE_CACHE.get(url);
      if (cached != null) {
         return cached;
      }

      try {
         // Mojang hands out http:// texture links; their server works over https too.
         String secureUrl = url.startsWith("http://textures.minecraft.net/") ? "https://" + url.substring("http://".length()) : url;
         HttpURLConnection connection = (HttpURLConnection)new URL(secureUrl).openConnection(this.proxy);
         connection.setConnectTimeout(TIMEOUT_MS);
         connection.setReadTimeout(TIMEOUT_MS);
         try (InputStream in = connection.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
               throw new ProfileException("Mojang sent a picture Diode couldn't read");
            }

            TEXTURE_CACHE.put(url, image);
            return image;
         }
      } catch (IOException e) {
         throw new ProfileException("Couldn't download the skin picture: " + e.getMessage(), e);
      }
   }

   private JsonObject request(String method, String path, String accessToken, String contentType, byte[] body) throws ProfileException {
      HttpURLConnection connection;
      int status;
      String response;
      try {
         connection = (HttpURLConnection)new URL(BASE_URL + path).openConnection(this.proxy);
         connection.setRequestMethod(method);
         connection.setConnectTimeout(TIMEOUT_MS);
         connection.setReadTimeout(TIMEOUT_MS);
         connection.setUseCaches(false);
         connection.setRequestProperty("Authorization", "Bearer " + accessToken);
         connection.setRequestProperty("Accept", "application/json");
         if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = connection.getOutputStream()) {
               out.write(body);
            }
         }

         status = connection.getResponseCode();
         InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
         response = in == null ? "" : readFully(in);
      } catch (IOException e) {
         throw new ProfileException("Couldn't reach Minecraft's servers: " + e.getMessage(), e);
      }

      JsonObject json = parse(response);
      if (status == 401) {
         throw new ProfileException.Unauthorized();
      }
      if (status == 429) {
         throw new ProfileException("Mojang only lets you change your skin or cape a few times a minute. Wait a moment and try again.");
      }
      if (status == 404 && path.equals("/minecraft/profile")) {
         throw new ProfileException("This Microsoft account doesn't own Minecraft: Java Edition, so it has no skin to change.");
      }
      if (status >= 400) {
         String message = json == null ? null : firstString(json, "errorMessage", "error", "developerMessage");
         throw new ProfileException("Minecraft's servers said no (" + status + (message == null ? "" : ": " + message) + ").");
      }
      if (json == null) {
         throw new ProfileException("Minecraft's servers sent an answer Diode couldn't read.");
      }

      return json;
   }

   private static String readFully(InputStream in) throws IOException {
      try (InputStream stream = in) {
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         byte[] buffer = new byte[8192];
         int read;
         while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
         }

         return new String(out.toByteArray(), StandardCharsets.UTF_8);
      }
   }

   private static JsonObject parse(String text) {
      if (text == null || text.trim().isEmpty()) {
         return null;
      }

      try {
         JsonElement element = new JsonParser().parse(text);
         return element.isJsonObject() ? element.getAsJsonObject() : null;
      } catch (RuntimeException e) {
         return null;
      }
   }

   private static String firstString(JsonObject json, String... keys) {
      for (String key : keys) {
         if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return json.get(key).getAsString();
         }
      }

      return null;
   }

   public enum SkinModel {
      CLASSIC("classic", "Classic (Steve)"),
      SLIM("slim", "Slim (Alex)");

      private final String apiName;
      private final String label;

      SkinModel(String apiName, String label) {
         this.apiName = apiName;
         this.label = label;
      }

      public String apiName() {
         return this.apiName;
      }

      public String label() {
         return this.label;
      }

      static SkinModel fromApi(String variant) {
         return "slim".equalsIgnoreCase(variant) ? SLIM : CLASSIC;
      }
   }

   public static class Cape {
      private final String id;
      private final String name;
      private final String url;

      Cape(String id, String name, String url) {
         this.id = id;
         this.name = name;
         this.url = url;
      }

      public String getId() {
         return this.id;
      }

      public String getName() {
         return this.name;
      }

      public String getUrl() {
         return this.url;
      }

      @Override
      public String toString() {
         return this.name;
      }
   }

   public static class Profile {
      private final String name;
      private final String skinUrl;
      private final SkinModel skinModel;
      private final List<Cape> capes;
      private final Cape activeCape;

      private Profile(String name, String skinUrl, SkinModel skinModel, List<Cape> capes, Cape activeCape) {
         this.name = name;
         this.skinUrl = skinUrl;
         this.skinModel = skinModel;
         this.capes = capes;
         this.activeCape = activeCape;
      }

      static Profile parse(JsonObject json) {
         String name = json.has("name") ? json.get("name").getAsString() : "Player";
         String skinUrl = null;
         SkinModel model = SkinModel.CLASSIC;
         JsonArray skins = json.has("skins") && json.get("skins").isJsonArray() ? json.getAsJsonArray("skins") : new JsonArray();
         for (JsonElement element : skins) {
            JsonObject skin = element.getAsJsonObject();
            if (skinUrl == null || isActive(skin)) {
               skinUrl = skin.has("url") ? skin.get("url").getAsString() : null;
               model = SkinModel.fromApi(skin.has("variant") ? skin.get("variant").getAsString() : null);
            }
         }

         List<Cape> capes = new ArrayList<>();
         Cape active = null;
         JsonArray capeArray = json.has("capes") && json.get("capes").isJsonArray() ? json.getAsJsonArray("capes") : new JsonArray();
         for (JsonElement element : capeArray) {
            JsonObject capeJson = element.getAsJsonObject();
            String alias = capeJson.has("alias") ? capeJson.get("alias").getAsString() : "Cape";
            Cape cape = new Cape(capeJson.get("id").getAsString(), prettyCapeName(alias), capeJson.has("url") ? capeJson.get("url").getAsString() : null);
            capes.add(cape);
            if (isActive(capeJson)) {
               active = cape;
            }
         }

         return new Profile(name, skinUrl, model, Collections.unmodifiableList(capes), active);
      }

      private static boolean isActive(JsonObject json) {
         return json.has("state") && "ACTIVE".equalsIgnoreCase(json.get("state").getAsString());
      }

      /** Mojang's cape aliases look like "MIGRATOR" or "Minecon2016"; make them read nicely. */
      private static String prettyCapeName(String alias) {
         String spaced = alias.replace('_', ' ').trim();
         if (spaced.equals(spaced.toUpperCase())) {
            spaced = spaced.toLowerCase();
         }

         StringBuilder result = new StringBuilder();
         for (String word : spaced.split(" ")) {
            if (!word.isEmpty()) {
               if (result.length() > 0) {
                  result.append(' ');
               }
               result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
         }

         return result.toString();
      }

      public String getName() {
         return this.name;
      }

      public String getSkinUrl() {
         return this.skinUrl;
      }

      public SkinModel getSkinModel() {
         return this.skinModel;
      }

      public List<Cape> getCapes() {
         return this.capes;
      }

      public Cape getActiveCape() {
         return this.activeCape;
      }
   }

   public static class ProfileException extends Exception {
      public ProfileException(String message) {
         super(message);
      }

      public ProfileException(String message, Throwable cause) {
         super(message, cause);
      }

      /** The access token was rejected; logging in again usually fixes it. */
      public static class Unauthorized extends ProfileException {
         public Unauthorized() {
            super("Your login has expired. Log in again from Switch User.");
         }
      }
   }
}
