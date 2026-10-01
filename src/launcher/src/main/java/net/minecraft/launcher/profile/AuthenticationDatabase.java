package net.minecraft.launcher.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.authlib.AuthenticationService;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.launcher.Launcher;

public class AuthenticationDatabase {
   public static final String DEMO_UUID_PREFIX = "demo-";
   private final Map<String, UserAuthentication> authById;
   private final AuthenticationService authenticationService;

   public AuthenticationDatabase(AuthenticationService authenticationService) {
      this(new HashMap<>(), authenticationService);
   }

   public AuthenticationDatabase(Map<String, UserAuthentication> authById, AuthenticationService authenticationService) {
      this.authById = authById;
      this.authenticationService = authenticationService;
   }

   public UserAuthentication getByName(String name) {
      if (name == null) {
         return null;
      }

      for (Entry<String, UserAuthentication> entry : this.authById.entrySet()) {
         GameProfile profile = entry.getValue().getSelectedProfile();
         if (profile != null && profile.getName().equals(name)) {
            return entry.getValue();
         }

         if (profile == null && getUserFromDemoUUID(entry.getKey()).equals(name)) {
            return entry.getValue();
         }
      }

      return null;
   }

   public UserAuthentication getByUUID(String uuid) {
      return this.authById.get(uuid);
   }

   public Collection<String> getKnownNames() {
      List<String> names = new ArrayList<>();

      for (Entry<String, UserAuthentication> entry : this.authById.entrySet()) {
         GameProfile profile = entry.getValue().getSelectedProfile();
         if (profile != null) {
            names.add(profile.getName());
         } else {
            names.add(getUserFromDemoUUID(entry.getKey()));
         }
      }

      return names;
   }

   public void register(String uuid, UserAuthentication authentication) {
      this.authById.put(uuid, authentication);
   }

   public Set<String> getknownUUIDs() {
      return this.authById.keySet();
   }

   public void removeUUID(String uuid) {
      this.authById.remove(uuid);
   }

   public AuthenticationService getAuthenticationService() {
      return this.authenticationService;
   }

   public static String getUserFromDemoUUID(String uuid) {
      return uuid.startsWith("demo-") && uuid.length() > "demo-".length() ? "Demo User " + uuid.substring("demo-".length()) : "Demo User";
   }

   public static class Serializer implements JsonDeserializer<AuthenticationDatabase>, JsonSerializer<AuthenticationDatabase> {
      private final Launcher launcher;

      public Serializer(Launcher launcher) {
         this.launcher = launcher;
      }

      public AuthenticationDatabase deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
         Map<String, UserAuthentication> services = new HashMap<>();
         Map<String, Map<String, Object>> credentials = this.deserializeCredentials((JsonObject)json, context);
         YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(
            this.launcher.getLauncher().getProxy(), this.launcher.getClientToken().toString()
         );

         for (Entry<String, Map<String, Object>> entry : credentials.entrySet()) {
            Map<String, Object> stored = entry.getValue();
            UserAuthentication auth;
            if ("true".equalsIgnoreCase(String.valueOf(stored.get("offline")))) {
               auth = OfflineUserAuthentication.fromStorage(authService, stored);
            } else {
               auth = authService.createUserAuthentication(this.launcher.getLauncher().getAgent());
               auth.loadFromStorage(stored);
            }
            services.put(entry.getKey(), auth);
         }

         return new AuthenticationDatabase(services, authService);
      }

      protected Map<String, Map<String, Object>> deserializeCredentials(JsonObject json, JsonDeserializationContext context) {
         Map<String, Map<String, Object>> result = new LinkedHashMap<>();

         for (Entry<String, JsonElement> authEntry : json.entrySet()) {
            Map<String, Object> credentials = new LinkedHashMap<>();

            for (Entry<String, JsonElement> credentialsEntry : ((JsonObject)authEntry.getValue()).entrySet()) {
               credentials.put(credentialsEntry.getKey(), this.deserializeCredential(credentialsEntry.getValue()));
            }

            result.put(authEntry.getKey(), credentials);
         }

         return result;
      }

      private Object deserializeCredential(JsonElement element) {
         if (element instanceof JsonObject) {
            Map<String, Object> result = new LinkedHashMap<>();

            for (Entry<String, JsonElement> entry : ((JsonObject)element).entrySet()) {
               result.put(entry.getKey(), this.deserializeCredential(entry.getValue()));
            }

            return result;
         } else {
            if (!(element instanceof JsonArray)) {
               return element.getAsString();
            }

            List<Object> result = new ArrayList<>();

            for (JsonElement entry : (JsonArray)element) {
               result.add(this.deserializeCredential(entry));
            }

            return result;
         }
      }

      public JsonElement serialize(AuthenticationDatabase src, Type typeOfSrc, JsonSerializationContext context) {
         Map<String, UserAuthentication> services = src.authById;
         Map<String, Map<String, Object>> credentials = new HashMap<>();

         for (Entry<String, UserAuthentication> entry : services.entrySet()) {
            credentials.put(entry.getKey(), entry.getValue().saveForStorage());
         }

         return context.serialize(credentials);
      }
   }
}
