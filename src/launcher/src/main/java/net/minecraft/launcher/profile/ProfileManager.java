package net.minecraft.launcher.profile;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.launcher.updater.DateTypeAdapter;
import com.mojang.launcher.updater.FileTypeAdapter;
import com.mojang.launcher.updater.LowerCaseEnumTypeAdapterFactory;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.LauncherConstants;
import org.apache.commons.io.FileUtils;

public class ProfileManager {
   public static final String DEFAULT_PROFILE_NAME = "(Default)";
   private final Launcher launcher;
   private final JsonParser parser = new JsonParser();
   private final Gson gson;
   private final Map<String, Profile> profiles = new HashMap<>();
   private final File profileFile;
   private final List<RefreshedProfilesListener> refreshedProfilesListeners = Collections.synchronizedList(new ArrayList<>());
   private final List<UserChangedListener> userChangedListeners = Collections.synchronizedList(new ArrayList<>());
   private String selectedProfile;
   private String selectedUser;
   private AuthenticationDatabase authDatabase;

   public ProfileManager(Launcher launcher) {
      this.launcher = launcher;
      this.profileFile = new File(launcher.getLauncher().getWorkingDirectory(), "launcher_profiles.json");
      GsonBuilder builder = new GsonBuilder();
      builder.registerTypeAdapterFactory(new LowerCaseEnumTypeAdapterFactory());
      builder.registerTypeAdapter(Date.class, new DateTypeAdapter());
      builder.registerTypeAdapter(File.class, new FileTypeAdapter());
      builder.registerTypeAdapter(AuthenticationDatabase.class, new AuthenticationDatabase.Serializer(launcher));
      builder.registerTypeAdapter(ProfileManager.RawProfileList.class, new ProfileManager.RawProfileList.Serializer(launcher));
      builder.setPrettyPrinting();
      this.gson = builder.create();
      this.authDatabase = new AuthenticationDatabase(
         new YggdrasilAuthenticationService(launcher.getLauncher().getProxy(), launcher.getClientToken().toString())
      );
   }

   public void saveProfiles() throws IOException {
      ProfileManager.RawProfileList rawProfileList = new ProfileManager.RawProfileList(
         this.profiles, this.getSelectedProfile().getName(), this.selectedUser, this.launcher.getClientToken(), this.authDatabase
      );
      FileUtils.writeStringToFile(this.profileFile, this.gson.toJson(rawProfileList));
   }

   public boolean loadProfiles() throws IOException {
      this.profiles.clear();
      this.selectedProfile = null;
      this.selectedUser = null;
      if (this.profileFile.isFile()) {
         JsonObject object = this.parser.parse(FileUtils.readFileToString(this.profileFile)).getAsJsonObject();
         if (object.has("clientToken")) {
            this.launcher.setClientToken(this.gson.fromJson(object.get("clientToken"), UUID.class));
         }

         ProfileManager.RawProfileList rawProfileList = this.gson.fromJson(object, ProfileManager.RawProfileList.class);
         this.profiles.putAll(rawProfileList.profiles);
         this.selectedProfile = rawProfileList.selectedProfile;
         this.selectedUser = rawProfileList.selectedUser;
         this.authDatabase = rawProfileList.authenticationDatabase;
         this.fireRefreshEvent();
         this.fireUserChangedEvent();
         return true;
      } else {
         // First portable launch: create a real default profile file beside the
         // standalone launcher instead of leaving the UI with an empty model.
         this.getSelectedProfile();
         try {
            this.saveProfiles();
         } catch (IOException e) {
            // Loading a new launcher must still continue if the folder happens
            // to be read-only; the in-memory default profile remains usable.
         }
         this.fireRefreshEvent();
         this.fireUserChangedEvent();
         return false;
      }
   }

   public void fireRefreshEvent() {
      for (RefreshedProfilesListener listener : Lists.newArrayList(this.refreshedProfilesListeners)) {
         listener.onProfilesRefreshed(this);
      }
   }

   public void fireUserChangedEvent() {
      for (UserChangedListener listener : Lists.newArrayList(this.userChangedListeners)) {
         listener.onUserChanged(this);
      }
   }

   public Profile getSelectedProfile() {
      if (this.selectedProfile == null || !this.profiles.containsKey(this.selectedProfile)) {
         if (this.profiles.get("(Default)") != null) {
            this.selectedProfile = "(Default)";
         } else if (this.profiles.size() > 0) {
            this.selectedProfile = this.profiles.values().iterator().next().getName();
         } else {
            this.selectedProfile = "(Default)";
            this.profiles.put("(Default)", new Profile(this.selectedProfile));
         }
      }

      return this.profiles.get(this.selectedProfile);
   }

   public Map<String, Profile> getProfiles() {
      return this.profiles;
   }

   public void addRefreshedProfilesListener(RefreshedProfilesListener listener) {
      this.refreshedProfilesListeners.add(listener);
   }

   public void addUserChangedListener(UserChangedListener listener) {
      this.userChangedListeners.add(listener);
   }

   public void setSelectedProfile(String selectedProfile) {
      boolean update = !this.selectedProfile.equals(selectedProfile);
      this.selectedProfile = selectedProfile;
      if (update) {
         this.fireRefreshEvent();
      }
   }

   public String getSelectedUser() {
      return this.selectedUser;
   }

   public void setSelectedUser(String selectedUser) {
      boolean update = !Objects.equal(this.selectedUser, selectedUser);
      if (update) {
         this.selectedUser = selectedUser;
         this.fireUserChangedEvent();
      }
   }

   public AuthenticationDatabase getAuthDatabase() {
      return this.authDatabase;
   }

   private static class RawProfileList {
      public Map<String, Profile> profiles = new HashMap<>();
      public String selectedProfile;
      public String selectedUser;
      public UUID clientToken = UUID.randomUUID();
      public AuthenticationDatabase authenticationDatabase;

      private RawProfileList(
         Map<String, Profile> profiles, String selectedProfile, String selectedUser, UUID clientToken, AuthenticationDatabase authenticationDatabase
      ) {
         this.profiles = profiles;
         this.selectedProfile = selectedProfile;
         this.selectedUser = selectedUser;
         this.clientToken = clientToken;
         this.authenticationDatabase = authenticationDatabase;
      }

      public static class Serializer implements JsonDeserializer<ProfileManager.RawProfileList>, JsonSerializer<ProfileManager.RawProfileList> {
         private final Launcher launcher;

         public Serializer(Launcher launcher) {
            this.launcher = launcher;
         }

         public ProfileManager.RawProfileList deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject object = (JsonObject)json;
            Map<String, Profile> profiles = Maps.newHashMap();
            if (object.has("profiles")) {
               profiles = context.deserialize(object.get("profiles"), (new TypeToken<Map<String, Profile>>() {}).getType());
            }

            String selectedProfile = null;
            if (object.has("selectedProfile")) {
               selectedProfile = object.getAsJsonPrimitive("selectedProfile").getAsString();
            }

            UUID clientToken = UUID.randomUUID();
            if (object.has("clientToken")) {
               clientToken = context.deserialize(object.get("clientToken"), UUID.class);
            }

            AuthenticationDatabase database = new AuthenticationDatabase(
               new YggdrasilAuthenticationService(this.launcher.getLauncher().getProxy(), this.launcher.getClientToken().toString())
            );
            if (object.has("authenticationDatabase")) {
               database = context.deserialize(object.get("authenticationDatabase"), AuthenticationDatabase.class);
            }

            String selectedUser = null;
            if (object.has("selectedUser")) {
               selectedUser = object.getAsJsonPrimitive("selectedUser").getAsString();
            } else if (selectedProfile != null && profiles.containsKey(selectedProfile) && profiles.get(selectedProfile).getPlayerUUID() != null) {
               selectedUser = profiles.get(selectedProfile).getPlayerUUID();
            } else if (!database.getknownUUIDs().isEmpty()) {
               selectedUser = database.getknownUUIDs().iterator().next();
            }

            for (Profile profile : profiles.values()) {
               profile.setPlayerUUID(null);
            }

            return new ProfileManager.RawProfileList(profiles, selectedProfile, selectedUser, clientToken, database);
         }

         public JsonElement serialize(ProfileManager.RawProfileList src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject version = new JsonObject();
            version.addProperty("name", LauncherConstants.getVersionName());
            version.addProperty("format", 17);
            JsonObject object = new JsonObject();
            object.add("profiles", context.serialize(src.profiles));
            object.add("selectedProfile", context.serialize(src.selectedProfile));
            object.add("clientToken", context.serialize(src.clientToken));
            object.add("authenticationDatabase", context.serialize(src.authenticationDatabase));
            object.add("selectedUser", context.serialize(src.selectedUser));
            object.add("launcherVersion", version);
            return object;
         }
      }
   }
}
