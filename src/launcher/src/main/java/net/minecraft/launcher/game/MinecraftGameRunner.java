package net.minecraft.launcher.game;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.UserType;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import com.mojang.launcher.LegacyPropertyMapSerializer;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.game.GameInstanceStatus;
import com.mojang.launcher.game.process.GameProcess;
import com.mojang.launcher.game.process.GameProcessBuilder;
import com.mojang.launcher.game.process.GameProcessFactory;
import com.mojang.launcher.game.process.GameProcessRunnable;
import com.mojang.launcher.game.process.direct.DirectGameProcessFactory;
import com.mojang.launcher.game.runner.AbstractGameRunner;
import com.mojang.launcher.updater.DateTypeAdapter;
import com.mojang.launcher.updater.VersionSyncInfo;
import com.mojang.launcher.updater.download.Downloadable;
import com.mojang.launcher.updater.download.assets.AssetIndex;
import com.mojang.launcher.versions.ExtractRules;
import com.mojang.util.UUIDTypeAdapter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.LauncherConstants;
import net.minecraft.launcher.profile.LauncherVisibilityRule;
import net.minecraft.launcher.profile.OfflineUserAuthentication;
import net.minecraft.launcher.profile.Profile;
import net.minecraft.launcher.updater.CompleteMinecraftVersion;
import net.minecraft.launcher.updater.Library;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;

public class MinecraftGameRunner extends AbstractGameRunner implements GameProcessRunnable {
   private static final String CRASH_IDENTIFIER_MAGIC = "#@!@#";
   // Mojang added --offlineDeveloperMode in snapshot 25w31a (2025-07-29).
   // It tells modern clients to use the built-in offline UserApiService instead
   // of treating an offline launcher session as a restricted Microsoft account.
   private static final long OFFLINE_DEVELOPER_MODE_INTRODUCED = 1753747200000L;
   private final Gson gson = new Gson();
   private final DateTypeAdapter dateAdapter = new DateTypeAdapter();
   private final Launcher minecraftLauncher;
   private final String[] additionalLaunchArgs;
   private final GameProcessFactory processFactory = new DirectGameProcessFactory();
   private File nativeDir;
   private LauncherVisibilityRule visibilityRule = LauncherVisibilityRule.CLOSE_LAUNCHER;
   private UserAuthentication auth;
   private Profile selectedProfile;

   public MinecraftGameRunner(Launcher minecraftLauncher, String[] additionalLaunchArgs) {
      this.minecraftLauncher = minecraftLauncher;
      this.additionalLaunchArgs = additionalLaunchArgs;
   }

   @Override
   protected void setStatus(GameInstanceStatus status) {
      synchronized (this.lock) {
         if (this.nativeDir != null && status == GameInstanceStatus.IDLE) {
            LOGGER.info("Deleting " + this.nativeDir);
            if (this.nativeDir.isDirectory() && !FileUtils.deleteQuietly(this.nativeDir)) {
               LOGGER.warn("Couldn't delete " + this.nativeDir + " - scheduling for deletion upon exit");

               try {
                  FileUtils.forceDeleteOnExit(this.nativeDir);
               } catch (Throwable var5) {
               }
            } else {
               this.nativeDir = null;
            }
         }

         super.setStatus(status);
      }
   }

   @Override
   protected com.mojang.launcher.Launcher getLauncher() {
      return this.minecraftLauncher.getLauncher();
   }

   @Override
   protected void downloadRequiredFiles(VersionSyncInfo syncInfo) {
      this.migrateOldAssets();
      super.downloadRequiredFiles(syncInfo);
   }

   @Override
   protected void launchGame() throws IOException {
      LOGGER.info("Launching game");
      this.selectedProfile = this.minecraftLauncher.getProfileManager().getSelectedProfile();
      this.auth = this.minecraftLauncher.getProfileManager().getAuthDatabase().getByUUID(this.minecraftLauncher.getProfileManager().getSelectedUser());
      if (this.getVersion() == null) {
         LOGGER.error("Aborting launch; version is null?");
      } else {
         this.nativeDir = new File(
            this.getLauncher().getWorkingDirectory(),
            "versions/" + this.getVersion().getId() + "/" + this.getVersion().getId() + "-natives-" + System.nanoTime()
         );
         if (!this.nativeDir.isDirectory()) {
            this.nativeDir.mkdirs();
         }

         LOGGER.info("Unpacking natives to " + this.nativeDir);

         try {
            this.unpackNatives(this.nativeDir);
         } catch (IOException e) {
            LOGGER.error("Couldn't unpack natives!", e);
            return;
         }

         File assetsDir;
         try {
            assetsDir = this.reconstructAssets();
         } catch (IOException e) {
            LOGGER.error("Couldn't unpack natives!", e);
            return;
         }

         File gameDirectory = this.selectedProfile.getGameDir() == null ? this.getLauncher().getWorkingDirectory() : this.selectedProfile.getGameDir();
         LOGGER.info("Launching in " + gameDirectory);
         if (!gameDirectory.exists()) {
            if (!gameDirectory.mkdirs()) {
               LOGGER.error("Aborting launch; couldn't create game directory");
               return;
            }
         } else if (!gameDirectory.isDirectory()) {
            LOGGER.error("Aborting launch; game directory is not actually a directory");
            return;
         }

         File serverResourcePacksDir = new File(gameDirectory, "server-resource-packs");
         if (!serverResourcePacksDir.exists()) {
            serverResourcePacksDir.mkdirs();
         }

         String javaPath = this.selectedProfile.getJavaPath();
         if (javaPath == null || javaPath.trim().isEmpty()) {
            javaPath = new MinecraftRuntimeManager(this.getLauncher().getWorkingDirectory(), this.getLauncher().getProxy())
               .resolveJavaExecutable(this.getVersion());
         }
         LOGGER.info("Using Java executable: {}", new Object[]{javaPath});
         GameProcessBuilder processBuilder = new GameProcessBuilder(javaPath);
         processBuilder.withSysOutFilter(new Predicate<String>() {
            public boolean apply(String input) {
               return input.contains("#@!@#");
            }
         });
         processBuilder.directory(gameDirectory);
         processBuilder.withLogProcessor(this.minecraftLauncher.getUserInterface().showGameOutputTab(this));
         OperatingSystem os = OperatingSystem.getCurrentPlatform();

         // Memory is a first-class per-profile setting now. Older launcher profiles
         // stored -Xmx and CMS collector flags inside javaArgs; we deliberately
         // strip those managed/obsolete flags below so old profiles still launch
         // cleanly on Java 21/25+.
         Integer configuredMemory = this.selectedProfile.getMemoryMb();
         int memoryMb = configuredMemory == null ? 2048 : configuredMemory;
         memoryMb = Math.max(512, Math.min(65536, memoryMb));
         processBuilder.withArguments("-Xmx" + memoryMb + "M");

         String profileArgs = this.selectedProfile.getJavaArgs();
         String[] safeProfileArgs = this.sanitizeProfileJvmArguments(profileArgs);
         if (safeProfileArgs.length > 0) {
            processBuilder.withArguments(safeProfileArgs);
         }

         if (this.minecraftLauncher.usesWinTenHack()) {
            processBuilder.withArguments("-Dos.name=Windows 10");
            processBuilder.withArguments("-Dos.version=10.0");
         }

         String classPath = this.constructClassPath(this.getVersion());
         if (this.getVersion().getJvmArguments() != null) {
            processBuilder.withArguments(this.getModernJvmArguments(this.getVersion(), classPath));
         } else {
            if (os.equals(OperatingSystem.OSX)) {
               processBuilder.withArguments("-Xdock:icon=" + this.getAssetObject("icons/minecraft.icns").getAbsolutePath(), "-Xdock:name=Minecraft");
            } else if (os.equals(OperatingSystem.WINDOWS)) {
               processBuilder.withArguments("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            }
            processBuilder.withArguments("-Djava.library.path=" + this.nativeDir.getAbsolutePath());
            processBuilder.withArguments("-cp", classPath);
         }

         processBuilder.withArguments(this.getVersion().getMainClass());
         LOGGER.info("Half command: " + StringUtils.join(processBuilder.getFullCommands(), " "));
         String[] args = this.getMinecraftArguments(this.getVersion(), this.selectedProfile, gameDirectory, assetsDir, this.auth);
         if (args != null) {
            processBuilder.withArguments(args);

            // The custom launcher can create a real local/offline profile. Modern
            // Minecraft (25w31a / 1.21.9 and newer) has an official launcher flag
            // for exactly this case. Without it, the client asks the Microsoft
            // user-properties service with our offline token and greys out the
            // Multiplayer button. Offline developer mode uses Mojang's built-in
            // offline UserApiService, which allows LAN/local multiplayer.
            if (this.auth instanceof OfflineUserAuthentication && this.supportsOfflineDeveloperMode(this.getVersion())) {
               LOGGER.info("Offline profile detected; enabling Minecraft offline developer mode for local/LAN multiplayer");
               processBuilder.withArguments("--offlineDeveloperMode");
            }

            Proxy proxy = this.getLauncher().getProxy();
            PasswordAuthentication proxyAuth = this.getLauncher().getProxyAuth();
            if (!proxy.equals(Proxy.NO_PROXY)) {
               InetSocketAddress address = (InetSocketAddress)proxy.address();
               processBuilder.withArguments("--proxyHost", address.getHostName());
               processBuilder.withArguments("--proxyPort", Integer.toString(address.getPort()));
               if (proxyAuth != null) {
                  processBuilder.withArguments("--proxyUser", proxyAuth.getUserName());
                  processBuilder.withArguments("--proxyPass", new String(proxyAuth.getPassword()));
               }
            }

            processBuilder.withArguments(this.additionalLaunchArgs);
            if (!this.getVersion().hasModernArguments()) {
               if (this.auth == null || this.auth.getSelectedProfile() == null) {
                  processBuilder.withArguments("--demo");
               }

               if (this.selectedProfile.getResolution() != null) {
                  processBuilder.withArguments("--width", String.valueOf(this.selectedProfile.getResolution().getWidth()));
                  processBuilder.withArguments("--height", String.valueOf(this.selectedProfile.getResolution().getHeight()));
               }
            }

            try {
               LOGGER.debug("Running " + StringUtils.join(processBuilder.getFullCommands(), " "));
               GameProcess process = this.processFactory.startGame(processBuilder);
               process.setExitRunnable(this);
               this.setStatus(GameInstanceStatus.PLAYING);
               if (this.visibilityRule != LauncherVisibilityRule.DO_NOTHING) {
                  this.minecraftLauncher.getUserInterface().setVisible(false);
               }
            } catch (IOException e) {
               LOGGER.error("Couldn't launch game", e);
               this.setStatus(GameInstanceStatus.IDLE);
               return;
            }

            this.minecraftLauncher.performCleanups();
         }
      }
   }

   private String[] sanitizeProfileJvmArguments(String profileArgs) {
      if (profileArgs == null || profileArgs.trim().isEmpty()) {
         return new String[0];
      }

      List<String> result = new ArrayList<String>();
      String[] split = profileArgs.trim().split("\\s+");
      for (String arg : split) {
         if (arg.regionMatches(true, 0, "-Xmx", 0, 4)) {
            LOGGER.info("Ignoring profile JVM memory flag {} because RAM is managed by the profile Memory setting", new Object[]{arg});
            continue;
         }

         // CMS was removed from modern Java. Old launcher 1.6.44 profiles often
         // persisted these defaults, causing Java 14+ (including Java 25) to
         // abort before Minecraft even starts.
         if (arg.indexOf("UseConcMarkSweepGC") >= 0 || arg.indexOf("CMS") >= 0) {
            LOGGER.info("Ignoring obsolete CMS JVM option {}", new Object[]{arg});
            continue;
         }

         result.add(arg);
      }

      return result.toArray(new String[result.size()]);
   }

   private boolean supportsOfflineDeveloperMode(CompleteMinecraftVersion version) {
      if (version == null) {
         return false;
      }

      Date releaseTime = version.getReleaseTime();
      return releaseTime != null && releaseTime.getTime() >= OFFLINE_DEVELOPER_MODE_INTRODUCED;
   }

   protected CompleteMinecraftVersion getVersion() {
      return (CompleteMinecraftVersion)this.version;
   }

   private File getAssetObject(String name) throws IOException {
      File assetsDir = new File(this.getLauncher().getWorkingDirectory(), "assets");
      File indexDir = new File(assetsDir, "indexes");
      File objectsDir = new File(assetsDir, "objects");
      String assetVersion = this.getVersion().getAssetIndexId();
      File indexFile = new File(indexDir, assetVersion + ".json");
      AssetIndex index = this.gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);
      String hash = index.getFileMap().get(name).getHash();
      return new File(objectsDir, hash.substring(0, 2) + "/" + hash);
   }

   private File reconstructAssets() throws IOException {
      File assetsDir = new File(this.getLauncher().getWorkingDirectory(), "assets");
      File indexDir = new File(assetsDir, "indexes");
      File objectDir = new File(assetsDir, "objects");
      String assetVersion = this.getVersion().getAssetIndexId();
      File indexFile = new File(indexDir, assetVersion + ".json");
      File virtualRoot = new File(new File(assetsDir, "virtual"), assetVersion);
      if (!indexFile.isFile()) {
         LOGGER.warn("No assets index file " + virtualRoot + "; can't reconstruct assets");
         return virtualRoot;
      }

      AssetIndex index = this.gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);
      if (index.isVirtual()) {
         LOGGER.info("Reconstructing virtual assets folder at " + virtualRoot);

         for (Entry<String, AssetIndex.AssetObject> entry : index.getFileMap().entrySet()) {
            File target = new File(virtualRoot, entry.getKey());
            File original = new File(new File(objectDir, entry.getValue().getHash().substring(0, 2)), entry.getValue().getHash());
            if (!target.isFile()) {
               FileUtils.copyFile(original, target, false);
            }
         }

         FileUtils.writeStringToFile(new File(virtualRoot, ".lastused"), this.dateAdapter.serializeToString(new Date()));
      }

      return virtualRoot;
   }

   private String[] getMinecraftArguments(
      CompleteMinecraftVersion version, Profile selectedProfile, File gameDirectory, File assetsDirectory, UserAuthentication authentication
   ) {
      Map<String, String> map = this.createArgumentMap(version, selectedProfile, gameDirectory, assetsDirectory, authentication);
      StrSubstitutor substitutor = new StrSubstitutor(map);

      if (version.hasModernArguments()) {
         Map<String, Boolean> features = new HashMap<>();
         features.put("is_demo_user", authentication == null || authentication.getSelectedProfile() == null);
         features.put("has_custom_resolution", selectedProfile.getResolution() != null);
         features.put("has_quick_plays_support", false);
         features.put("is_quick_play_singleplayer", false);
         features.put("is_quick_play_multiplayer", false);
         features.put("is_quick_play_realms", false);
         List<String> args = this.expandModernArguments(version.getGameArguments(), substitutor, features);
         if (authentication instanceof OfflineUserAuthentication) {
            // An offline/local launcher profile should never be turned into a
            // client-side multiplayer lock by launcher-supplied flags.
            args.remove("--disableMultiplayer");
            args.remove("--disableChat");
         }
         return args.toArray(new String[args.size()]);
      }

      if (version.getMinecraftArguments() == null) {
         LOGGER.error("Can't run version, missing both minecraftArguments and modern arguments.game");
         this.setStatus(GameInstanceStatus.IDLE);
         return null;
      }

      String[] split = version.getMinecraftArguments().split(" ");
      for (int i = 0; i < split.length; i++) {
         split[i] = substitutor.replace(split[i]);
      }
      return split;
   }

   private String[] getModernJvmArguments(CompleteMinecraftVersion version, String classPath) {
      Map<String, String> map = new HashMap<>();
      map.put("natives_directory", this.nativeDir.getAbsolutePath());
      map.put("launcher_name", "legacy-minecraft-launcher");
      String launcherVersion = LauncherConstants.getVersionName();
      map.put("launcher_version", launcherVersion == null ? "1.6.44-custom" : launcherVersion);
      map.put("classpath", classPath);
      map.put("classpath_separator", File.pathSeparator);
      map.put("library_directory", new File(this.getLauncher().getWorkingDirectory(), "libraries").getAbsolutePath());
      StrSubstitutor substitutor = new StrSubstitutor(map);
      List<String> result = this.expandModernArguments(version.getJvmArguments(), substitutor, new HashMap<String, Boolean>());

      CompleteMinecraftVersion.LoggingConfiguration logging = version.getClientLogging();
      if (logging != null && logging.getArgument() != null && logging.getFile() != null) {
         String name = logging.getFile().getId() == null || logging.getFile().getId().isEmpty() ? "client.xml" : logging.getFile().getId();
         Map<String, String> loggingMap = new HashMap<>();
         loggingMap.put("path", new File(this.getLauncher().getWorkingDirectory(), "assets/log_configs/" + name).getAbsolutePath());
         result.add(new StrSubstitutor(loggingMap).replace(logging.getArgument()));
      }

      return result.toArray(new String[result.size()]);
   }

   private Map<String, String> createArgumentMap(
      CompleteMinecraftVersion version, Profile selectedProfile, File gameDirectory, File assetsDirectory, UserAuthentication authentication
   ) {
      Map<String, String> map = new HashMap<>();
      String accessToken = authentication == null || authentication.getAuthenticatedToken() == null ? "" : authentication.getAuthenticatedToken();
      if (authentication instanceof OfflineUserAuthentication) {
         // Older clients predate --offlineDeveloperMode. A deliberately invalid
         // token makes their authlib fall back to its offline service rather than
         // interpreting the local profile as a restricted Microsoft account.
         accessToken = "0";
      }
      map.put("auth_access_token", accessToken);
      map.put("clientid", "");
      map.put("auth_xuid", "");

      PropertyMap properties = authentication == null ? new PropertyMap() : authentication.getUserProperties();
      map.put("user_properties", new GsonBuilder().registerTypeAdapter(PropertyMap.class, new LegacyPropertyMapSerializer()).create().toJson(properties));
      map.put("user_property_map", new GsonBuilder().registerTypeAdapter(PropertyMap.class, new PropertyMap.Serializer()).create().toJson(properties));

      if (authentication == null || !authentication.isLoggedIn() || !authentication.canPlayOnline()) {
         map.put("auth_session", "-");
      } else if (authentication instanceof YggdrasilUserAuthentication && authentication.getSelectedProfile() != null) {
         map.put("auth_session", String.format("token:%s:%s", accessToken, UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId())));
      } else {
         map.put("auth_session", accessToken);
      }

      if (authentication != null && authentication.getSelectedProfile() != null) {
         map.put("auth_player_name", authentication.getSelectedProfile().getName());
         map.put("auth_uuid", UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId()));
         map.put("user_type", authentication.getUserType() == null ? UserType.LEGACY.getName() : authentication.getUserType().getName());
      } else {
         map.put("auth_player_name", "Player");
         map.put("auth_uuid", new UUID(0L, 0L).toString());
         map.put("user_type", UserType.LEGACY.getName());
      }

      map.put("profile_name", selectedProfile.getName());
      map.put("version_name", version.getId());
      map.put("version_type", version.getType() == null ? "release" : version.getType().getName());
      map.put("game_directory", gameDirectory.getAbsolutePath());
      map.put("game_assets", assetsDirectory.getAbsolutePath());
      map.put("assets_root", new File(this.getLauncher().getWorkingDirectory(), "assets").getAbsolutePath());
      map.put("assets_index_name", version.getAssetIndexId());
      if (selectedProfile.getResolution() != null) {
         map.put("resolution_width", String.valueOf(selectedProfile.getResolution().getWidth()));
         map.put("resolution_height", String.valueOf(selectedProfile.getResolution().getHeight()));
      } else {
         map.put("resolution_width", "854");
         map.put("resolution_height", "480");
      }
      return map;
   }

   private List<String> expandModernArguments(List<JsonElement> raw, StrSubstitutor substitutor, Map<String, Boolean> features) {
      List<String> result = new ArrayList<>();
      if (raw == null) {
         return result;
      }
      for (JsonElement element : raw) {
         if (element == null || element.isJsonNull()) {
            continue;
         }
         if (element.isJsonPrimitive()) {
            result.add(substitutor.replace(element.getAsString()));
            continue;
         }
         if (!element.isJsonObject()) {
            continue;
         }
         JsonObject object = element.getAsJsonObject();
         if (!this.rulesAllow(object.get("rules"), features)) {
            continue;
         }
         JsonElement value = object.get("value");
         if (value == null || value.isJsonNull()) {
            continue;
         }
         if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
               result.add(substitutor.replace(item.getAsString()));
            }
         } else {
            result.add(substitutor.replace(value.getAsString()));
         }
      }
      return result;
   }

   private boolean rulesAllow(JsonElement rawRules, Map<String, Boolean> features) {
      if (rawRules == null || rawRules.isJsonNull()) {
         return true;
      }
      JsonArray rules = rawRules.getAsJsonArray();
      boolean allowed = false;
      for (JsonElement rawRule : rules) {
         if (!rawRule.isJsonObject()) {
            continue;
         }
         JsonObject rule = rawRule.getAsJsonObject();
         if (!this.ruleMatches(rule, features)) {
            continue;
         }
         String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
         allowed = "allow".equalsIgnoreCase(action);
      }
      return allowed;
   }

   private boolean ruleMatches(JsonObject rule, Map<String, Boolean> features) {
      if (rule.has("os") && rule.get("os").isJsonObject()) {
         JsonObject os = rule.getAsJsonObject("os");
         if (os.has("name") && !OperatingSystem.getCurrentPlatform().getName().equalsIgnoreCase(os.get("name").getAsString())) {
            return false;
         }
         if (os.has("version") && !Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version", "")).matches()) {
            return false;
         }
         if (os.has("arch") && !Pattern.compile(os.get("arch").getAsString()).matcher(System.getProperty("os.arch", "")).matches()) {
            return false;
         }
      }

      if (rule.has("features") && rule.get("features").isJsonObject()) {
         for (Entry<String, JsonElement> feature : rule.getAsJsonObject("features").entrySet()) {
            boolean actual = Boolean.TRUE.equals(features.get(feature.getKey()));
            if (actual != feature.getValue().getAsBoolean()) {
               return false;
            }
         }
      }
      return true;
   }

   private void migrateOldAssets() {
      File sourceDir = new File(this.getLauncher().getWorkingDirectory(), "assets");
      File objectsDir = new File(sourceDir, "objects");
      if (sourceDir.isDirectory()) {
         IOFileFilter migratableFilter = FileFilterUtils.notFileFilter(
            FileFilterUtils.or(
               FileFilterUtils.nameFileFilter("indexes"),
               FileFilterUtils.nameFileFilter("objects"),
               FileFilterUtils.nameFileFilter("virtual"),
               FileFilterUtils.nameFileFilter("skins")
            )
         );

         for (File file : new TreeSet<>(FileUtils.listFiles(sourceDir, TrueFileFilter.TRUE, migratableFilter))) {
            String hash = Downloadable.getDigest(file, "SHA-1", 40);
            File destinationFile = new File(objectsDir, hash.substring(0, 2) + "/" + hash);
            if (!destinationFile.exists()) {
               LOGGER.info("Migrated old asset {} into {}", file, destinationFile);

               try {
                  FileUtils.copyFile(file, destinationFile);
               } catch (IOException e) {
                  LOGGER.error("Couldn't migrate old asset", e);
               }
            }

            FileUtils.deleteQuietly(file);
         }

         File[] assets = sourceDir.listFiles();
         if (assets != null) {
            for (File file : assets) {
               if (!file.getName().equals("indexes")
                  && !file.getName().equals("objects")
                  && !file.getName().equals("virtual")
                  && !file.getName().equals("skins")) {
                  LOGGER.info("Cleaning up old assets directory {} after migration", new Object[]{file});
                  FileUtils.deleteQuietly(file);
               }
            }
         }
      }
   }

   private void unpackNatives(File targetDir) throws IOException {
      OperatingSystem os = OperatingSystem.getCurrentPlatform();

      for (Library library : this.getVersion().getRelevantLibraries()) {
         Map<OperatingSystem, String> nativesPerOs = library.getNatives();
         if (nativesPerOs != null && nativesPerOs.get(os) != null) {
            File file = new File(this.getLauncher().getWorkingDirectory(), "libraries/" + library.getArtifactPath(nativesPerOs.get(os)));
            ZipFile zip = new ZipFile(file);
            ExtractRules extractRules = library.getExtractRules();

            try {
               Enumeration<? extends ZipEntry> entries = zip.entries();

               while (entries.hasMoreElements()) {
                  ZipEntry entry = entries.nextElement();
                  if (extractRules == null || extractRules.shouldExtract(entry.getName())) {
                     File targetFile = new File(targetDir, entry.getName());
                     if (targetFile.getParentFile() != null) {
                        targetFile.getParentFile().mkdirs();
                     }

                     if (!entry.isDirectory()) {
                        BufferedInputStream inputStream = new BufferedInputStream(zip.getInputStream(entry));
                        byte[] buffer = new byte[2048];
                        FileOutputStream outputStream = new FileOutputStream(targetFile);
                        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);

                        int length;
                        try {
                           while ((length = inputStream.read(buffer, 0, buffer.length)) != -1) {
                              bufferedOutputStream.write(buffer, 0, length);
                           }
                        } finally {
                           Downloadable.closeSilently(bufferedOutputStream);
                           Downloadable.closeSilently(outputStream);
                           Downloadable.closeSilently(inputStream);
                        }
                     }
                  }
               }
            } finally {
               zip.close();
            }
         }
      }
   }

   private String constructClassPath(CompleteMinecraftVersion version) {
      StringBuilder result = new StringBuilder();
      Collection<File> classPath = version.getClassPath(OperatingSystem.getCurrentPlatform(), this.getLauncher().getWorkingDirectory());
      String separator = System.getProperty("path.separator");

      for (File file : classPath) {
         if (!file.isFile()) {
            throw new RuntimeException("Classpath file not found: " + file);
         }

         if (result.length() > 0) {
            result.append(separator);
         }

         result.append(file.getAbsolutePath());
      }

      return result.toString();
   }

   @Override
   public void onGameProcessEnded(GameProcess process) {
      int exitCode = process.getExitCode();
      if (exitCode == 0) {
         LOGGER.info("Game ended with no troubles detected (exit code " + exitCode + ")");
         if (this.visibilityRule == LauncherVisibilityRule.CLOSE_LAUNCHER) {
            LOGGER.info("Following visibility rule and exiting launcher as the game has ended");
            this.getLauncher().shutdownLauncher();
         } else if (this.visibilityRule == LauncherVisibilityRule.HIDE_LAUNCHER) {
            LOGGER.info("Following visibility rule and showing launcher as the game has ended");
            this.minecraftLauncher.getUserInterface().setVisible(true);
         }
      } else {
         LOGGER.error("Game ended with bad state (exit code " + exitCode + ")");
         LOGGER.info("Ignoring visibility rule and showing launcher due to a game crash");
         this.minecraftLauncher.getUserInterface().setVisible(true);
         String errorText = null;
         Collection<String> sysOutLines = process.getSysOutLines();
         String[] sysOut = sysOutLines.toArray(new String[sysOutLines.size()]);

         for (int i = sysOut.length - 1; i >= 0; i--) {
            String line = sysOut[i];
            int pos = line.lastIndexOf("#@!@#");
            if (pos >= 0 && pos < line.length() - "#@!@#".length() - 1) {
               errorText = line.substring(pos + "#@!@#".length()).trim();
               break;
            }
         }

         if (errorText != null) {
            File file = new File(errorText);
            if (file.isFile()) {
               LOGGER.info("Crash report detected, opening: " + errorText);
               InputStream inputStream = null;

               try {
                  inputStream = new FileInputStream(file);
                  BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                  StringBuilder result = new StringBuilder();

                  String line;
                  while ((line = reader.readLine()) != null) {
                     if (result.length() > 0) {
                        result.append("\n");
                     }

                     result.append(line);
                  }

                  reader.close();
                  this.minecraftLauncher.getUserInterface().showCrashReport(this.getVersion(), file, result.toString());
               } catch (IOException e) {
                  LOGGER.error("Couldn't open crash report", e);
               } finally {
                  Downloadable.closeSilently(inputStream);
               }
            } else {
               LOGGER.error("Crash report detected, but unknown format: " + errorText);
            }
         }
      }

      this.setStatus(GameInstanceStatus.IDLE);
   }

   public void setVisibility(LauncherVisibilityRule visibility) {
      this.visibilityRule = visibility;
   }

   public UserAuthentication getAuth() {
      return this.auth;
   }

   public Profile getSelectedProfile() {
      return this.selectedProfile;
   }
}
