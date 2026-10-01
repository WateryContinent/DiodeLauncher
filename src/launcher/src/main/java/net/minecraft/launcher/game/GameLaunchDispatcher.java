package net.minecraft.launcher.game;

import com.google.common.base.Objects;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.mojang.authlib.UserAuthentication;
import com.mojang.launcher.game.GameInstanceStatus;
import com.mojang.launcher.game.runner.GameRunner;
import com.mojang.launcher.game.runner.GameRunnerListener;
import com.mojang.launcher.updater.VersionSyncInfo;
import java.io.File;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.profile.Profile;
import net.minecraft.launcher.profile.ProfileManager;

public class GameLaunchDispatcher implements GameRunnerListener {
   private final Launcher launcher;
   private final String[] additionalLaunchArgs;
   private final ReentrantLock lock = new ReentrantLock();
   private final BiMap<UserAuthentication, MinecraftGameRunner> instances = HashBiMap.create();
   private boolean downloadInProgress = false;

   public GameLaunchDispatcher(Launcher launcher, String[] additionalLaunchArgs) {
      this.launcher = launcher;
      this.additionalLaunchArgs = additionalLaunchArgs;
   }

   public GameLaunchDispatcher.PlayStatus getStatus() {
      ProfileManager profileManager = this.launcher.getProfileManager();
      Profile profile = profileManager.getProfiles().isEmpty() ? null : profileManager.getSelectedProfile();
      UserAuthentication user = profileManager.getSelectedUser() == null ? null : profileManager.getAuthDatabase().getByUUID(profileManager.getSelectedUser());
      if (user != null
         && user.isLoggedIn()
         && profile != null
         && !this.launcher.getLauncher().getVersionManager().getVersions(profile.getVersionFilter()).isEmpty()) {
         this.lock.lock();

         try {
            if (this.downloadInProgress) {
               return GameLaunchDispatcher.PlayStatus.DOWNLOADING;
            }

            if (this.instances.containsKey(user)) {
               return GameLaunchDispatcher.PlayStatus.ALREADY_PLAYING;
            }
         } finally {
            this.lock.unlock();
         }

         if (user.getSelectedProfile() == null) {
            return GameLaunchDispatcher.PlayStatus.CAN_PLAY_DEMO;
         } else {
            return user.canPlayOnline() ? GameLaunchDispatcher.PlayStatus.CAN_PLAY_ONLINE : GameLaunchDispatcher.PlayStatus.CAN_PLAY_OFFLINE;
         }
      } else {
         return GameLaunchDispatcher.PlayStatus.LOADING;
      }
   }

   public GameInstanceStatus getInstanceStatus() {
      ProfileManager profileManager = this.launcher.getProfileManager();
      UserAuthentication user = profileManager.getSelectedUser() == null ? null : profileManager.getAuthDatabase().getByUUID(profileManager.getSelectedUser());
      this.lock.lock();

      try {
         GameRunner gameRunner = this.instances.get(user);
         if (gameRunner != null) {
            return gameRunner.getStatus();
         }
      } finally {
         this.lock.unlock();
      }

      return GameInstanceStatus.IDLE;
   }

   public void play() {
      ProfileManager profileManager = this.launcher.getProfileManager();
      final Profile profile = profileManager.getSelectedProfile();
      UserAuthentication user = profileManager.getSelectedUser() == null ? null : profileManager.getAuthDatabase().getByUUID(profileManager.getSelectedUser());
      final String lastVersionId = profile.getLastVersionId();
      final MinecraftGameRunner gameRunner = new MinecraftGameRunner(this.launcher, this.additionalLaunchArgs);
      gameRunner.setStatus(GameInstanceStatus.PREPARING);
      this.lock.lock();

      label45: {
         try {
            if (!this.instances.containsKey(user) && !this.downloadInProgress) {
               this.instances.put(user, gameRunner);
               this.downloadInProgress = true;
               break label45;
            }
         } finally {
            this.lock.unlock();
         }

         return;
      }

      this.launcher.getLauncher().getVersionManager().getExecutorService().execute(new Runnable() {
         @Override
         public void run() {
            gameRunner.setVisibility(Objects.firstNonNull(profile.getLauncherVisibilityOnGameClose(), Profile.DEFAULT_LAUNCHER_VISIBILITY));
            VersionSyncInfo syncInfo = null;
            if (lastVersionId != null) {
               syncInfo = GameLaunchDispatcher.this.launcher.getLauncher().getVersionManager().getVersionSyncInfo(lastVersionId);
            }

            if (syncInfo == null || syncInfo.getLatestVersion() == null) {
               syncInfo = GameLaunchDispatcher.this.launcher.getLauncher().getVersionManager().getVersions(profile.getVersionFilter()).get(0);
            }

            gameRunner.setStatus(GameInstanceStatus.IDLE);
            gameRunner.addListener(GameLaunchDispatcher.this);
            gameRunner.playGame(syncInfo);
         }
      });
   }

   @Override
   public void onGameInstanceChangedState(GameRunner runner, GameInstanceStatus status) {
      this.lock.lock();

      try {
         if (status == GameInstanceStatus.IDLE) {
            this.instances.inverse().remove(runner);
         }

         this.downloadInProgress = false;

         for (GameRunner instance : this.instances.values()) {
            if (instance.getStatus() != GameInstanceStatus.PLAYING) {
               this.downloadInProgress = true;
               break;
            }
         }

         this.launcher.getUserInterface().updatePlayState();
      } finally {
         this.lock.unlock();
      }
   }

   public boolean isRunningInSameFolder() {
      this.lock.lock();

      try {
         File currentGameDir = Objects.firstNonNull(
            this.launcher.getProfileManager().getSelectedProfile().getGameDir(), this.launcher.getLauncher().getWorkingDirectory()
         );

         for (MinecraftGameRunner runner : this.instances.values()) {
            Profile profile = runner.getSelectedProfile();
            if (profile != null) {
               File otherGameDir = Objects.firstNonNull(profile.getGameDir(), this.launcher.getLauncher().getWorkingDirectory());
               if (currentGameDir.equals(otherGameDir)) {
                  return true;
               }
            }
         }
      } finally {
         this.lock.unlock();
      }

      return false;
   }

   public enum PlayStatus {
      LOADING("Loading...", false),
      CAN_PLAY_DEMO("Play Demo", true),
      CAN_PLAY_ONLINE("Play", true),
      CAN_PLAY_OFFLINE("Play Offline", true),
      ALREADY_PLAYING("Already Playing...", false),
      DOWNLOADING("Installing...", false);

      private final String name;
      private final boolean canPlay;

      PlayStatus(String name, boolean canPlay) {
         this.name = name;
         this.canPlay = canPlay;
      }

      public String getName() {
         return this.name;
      }

      public boolean canPlay() {
         return this.canPlay;
      }
   }
}
