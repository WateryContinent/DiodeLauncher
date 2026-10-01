package net.minecraft.launcher.updater;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.updater.VersionSyncInfo;
import com.mojang.launcher.updater.download.ChecksummedDownloadable;
import com.mojang.launcher.updater.download.Downloadable;
import com.mojang.launcher.updater.download.Sha1Downloadable;
import com.mojang.launcher.versions.CompatibilityRule;
import com.mojang.launcher.versions.CompleteVersion;
import com.mojang.launcher.versions.ReleaseType;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CompleteMinecraftVersion implements CompleteVersion {
   private static final Logger LOGGER = LogManager.getLogger();
   private String inheritsFrom;
   private String id;
   private Date time;
   private Date releaseTime;
   private ReleaseType type;
   private String minecraftArguments;
   private ModernArguments arguments;
   private List<Library> libraries;
   private String mainClass;
   private int minimumLauncherVersion;
   private String incompatibilityReason;
   private String assets;
   private AssetIndexInfo assetIndex;
   private Map<String, Library.DownloadInfo> downloads;
   private Map<String, LoggingConfiguration> logging;
   private JavaVersion javaVersion;
   private List<CompatibilityRule> compatibilityRules;
   private String jar;
   private CompleteMinecraftVersion savableVersion;
   private transient boolean synced = false;

   public CompleteMinecraftVersion() {
   }

   public CompleteMinecraftVersion(CompleteMinecraftVersion version) {
      this.inheritsFrom = version.inheritsFrom;
      this.id = version.id;
      this.time = version.time;
      this.releaseTime = version.releaseTime;
      this.type = version.type;
      this.minecraftArguments = version.minecraftArguments;
      this.arguments = version.arguments;
      this.mainClass = version.mainClass;
      this.minimumLauncherVersion = version.minimumLauncherVersion;
      this.incompatibilityReason = version.incompatibilityReason;
      this.assets = version.assets;
      this.assetIndex = version.assetIndex;
      this.downloads = version.downloads;
      this.logging = version.logging;
      this.javaVersion = version.javaVersion;
      this.jar = version.jar;
      if (version.libraries != null) {
         this.libraries = Lists.newArrayList();
         for (Library library : version.getLibraries()) {
            this.libraries.add(new Library(library));
         }
      }

      if (version.compatibilityRules != null) {
         this.compatibilityRules = Lists.newArrayList();
         for (CompatibilityRule compatibilityRule : version.compatibilityRules) {
            this.compatibilityRules.add(new CompatibilityRule(compatibilityRule));
         }
      }
   }

   @Override
   public String getId() {
      return this.id;
   }

   @Override
   public ReleaseType getType() {
      return this.type;
   }

   @Override
   public Date getUpdatedTime() {
      return this.time;
   }

   @Override
   public Date getReleaseTime() {
      return this.releaseTime;
   }

   public List<Library> getLibraries() {
      return this.libraries == null ? new ArrayList<Library>() : this.libraries;
   }

   public String getMainClass() {
      return this.mainClass;
   }

   public String getJar() {
      return this.jar == null ? this.id : this.jar;
   }

   public void setType(ReleaseType type) {
      if (type == null) {
         throw new IllegalArgumentException("Release type cannot be null");
      }
      this.type = type;
   }

   public Collection<Library> getRelevantLibraries() {
      List<Library> result = new ArrayList<>();
      if (this.libraries == null) {
         return result;
      }
      for (Library library : this.libraries) {
         if (library.appliesToCurrentEnvironment()) {
            result.add(library);
         }
      }
      return result;
   }

   public Collection<File> getClassPath(OperatingSystem os, File base) {
      Collection<File> result = new ArrayList<>();
      for (Library library : this.getRelevantLibraries()) {
         if (library.hasArtifact()) {
            result.add(new File(base, "libraries/" + library.getArtifactPath()));
         }
      }
      result.add(new File(base, "versions/" + this.getJar() + "/" + this.getJar() + ".jar"));
      return result;
   }

   public Set<String> getRequiredFiles(OperatingSystem os) {
      Set<String> neededFiles = new HashSet<>();
      for (Library library : this.getRelevantLibraries()) {
         if (library.hasArtifact()) {
            neededFiles.add("libraries/" + library.getArtifactPath());
         }
         if (library.getNatives() != null) {
            String natives = library.getNatives().get(os);
            if (natives != null) {
               neededFiles.add("libraries/" + library.getArtifactPath(natives));
            }
         }
      }
      return neededFiles;
   }

   private void addLibraryDownload(Set<Downloadable> neededFiles, Library library, String classifier, Proxy proxy, File targetDirectory, boolean ignoreLocalFiles) throws MalformedURLException {
      Library.DownloadInfo info = library.getDownloadInfo(classifier);
      String file = library.getArtifactPath(classifier);
      File local = new File(targetDirectory, "libraries/" + file);

      if (info != null && info.getUrl() != null && !info.getUrl().isEmpty()) {
         neededFiles.add(new Sha1Downloadable(proxy, new URL(info.getUrl()), local, info.getSha1(), info.getSize(), ignoreLocalFiles));
      } else {
         URL url = new URL(library.getDownloadUrl() + file);
         neededFiles.add(new ChecksummedDownloadable(proxy, url, local, ignoreLocalFiles));
      }
   }

   public Set<Downloadable> getRequiredDownloadables(OperatingSystem os, Proxy proxy, File targetDirectory, boolean ignoreLocalFiles) throws MalformedURLException {
      Set<Downloadable> neededFiles = new HashSet<>();
      for (Library library : this.getRelevantLibraries()) {
         if (library.hasArtifact()) {
            this.addLibraryDownload(neededFiles, library, null, proxy, targetDirectory, ignoreLocalFiles);
         }
         if (library.getNatives() != null) {
            String natives = library.getNatives().get(os);
            if (natives != null) {
               this.addLibraryDownload(neededFiles, library, natives, proxy, targetDirectory, ignoreLocalFiles);
            }
         }
      }
      return neededFiles;
   }

   @Override
   public String toString() {
      return "CompleteVersion{id='" + this.id + '\'' + ", updatedTime=" + this.time + ", releasedTime=" + this.time + ", type=" + this.type
         + ", libraries=" + this.libraries + ", mainClass='" + this.mainClass + '\'' + ", jar='" + this.jar + '\''
         + ", minimumLauncherVersion=" + this.minimumLauncherVersion + '}';
   }

   public String getMinecraftArguments() {
      return this.minecraftArguments;
   }

   public boolean hasModernArguments() {
      return this.arguments != null && this.arguments.game != null;
   }

   public List<JsonElement> getGameArguments() {
      return this.arguments == null ? null : this.arguments.game;
   }

   public List<JsonElement> getJvmArguments() {
      return this.arguments == null ? null : this.arguments.jvm;
   }

   public Library.DownloadInfo getClientDownload() {
      return this.downloads == null ? null : this.downloads.get("client");
   }

   public AssetIndexInfo getAssetIndex() {
      return this.assetIndex;
   }

   public String getAssetIndexId() {
      if (this.assetIndex != null && this.assetIndex.getId() != null && !this.assetIndex.getId().isEmpty()) {
         return this.assetIndex.getId();
      }
      return this.assets == null ? "legacy" : this.assets;
   }

   public LoggingConfiguration getClientLogging() {
      return this.logging == null ? null : this.logging.get("client");
   }

   public JavaVersion getJavaVersion() {
      return this.javaVersion;
   }

   @Override
   public int getMinimumLauncherVersion() {
      return this.minimumLauncherVersion;
   }

   @Override
   public boolean appliesToCurrentEnvironment() {
      if (this.compatibilityRules == null) {
         return true;
      }
      CompatibilityRule.Action lastAction = CompatibilityRule.Action.DISALLOW;
      for (CompatibilityRule compatibilityRule : this.compatibilityRules) {
         CompatibilityRule.Action action = compatibilityRule.getAppliedAction();
         if (action != null) {
            lastAction = action;
         }
      }
      return lastAction == CompatibilityRule.Action.ALLOW;
   }

   @Override
   public String getIncompatibilityReason() {
      return this.incompatibilityReason;
   }

   @Override
   public boolean isSynced() {
      return this.synced;
   }

   @Override
   public void setSynced(boolean synced) {
      this.synced = synced;
   }

   public String getAssets() {
      return this.assets;
   }

   public String getInheritsFrom() {
      return this.inheritsFrom;
   }

   public CompleteMinecraftVersion resolve(MinecraftVersionManager versionManager) throws IOException {
      return this.resolve(versionManager, Sets.newHashSet());
   }

   protected CompleteMinecraftVersion resolve(MinecraftVersionManager versionManager, Set<String> resolvedSoFar) throws IOException {
      if (this.inheritsFrom == null) {
         return this;
      }
      if (!resolvedSoFar.add(this.id)) {
         throw new IllegalStateException("Circular dependency detected");
      }

      VersionSyncInfo parentSync = versionManager.getVersionSyncInfo(this.inheritsFrom);
      CompleteMinecraftVersion parent = versionManager.getLatestCompleteVersion(parentSync).resolve(versionManager, resolvedSoFar);
      CompleteMinecraftVersion result = new CompleteMinecraftVersion(parent);
      if (!parentSync.isInstalled() || !parentSync.isUpToDate() || parentSync.getLatestSource() != VersionSyncInfo.VersionSource.LOCAL) {
         versionManager.installVersion(parent);
      }

      result.savableVersion = this;
      result.inheritsFrom = null;
      result.id = this.id;
      result.time = this.time;
      result.releaseTime = this.releaseTime;
      result.type = this.type;
      if (this.minecraftArguments != null) result.minecraftArguments = this.minecraftArguments;
      if (this.arguments != null) result.arguments = this.arguments;
      if (this.mainClass != null) result.mainClass = this.mainClass;
      if (this.incompatibilityReason != null) result.incompatibilityReason = this.incompatibilityReason;
      if (this.assets != null) result.assets = this.assets;
      if (this.assetIndex != null) result.assetIndex = this.assetIndex;
      if (this.downloads != null) result.downloads = this.downloads;
      if (this.logging != null) result.logging = this.logging;
      if (this.javaVersion != null) result.javaVersion = this.javaVersion;
      if (this.jar != null) result.jar = this.jar;

      if (this.libraries != null) {
         List<Library> newLibraries = Lists.newArrayList();
         for (Library library : this.libraries) newLibraries.add(new Library(library));
         if (result.libraries != null) {
            for (Library library : result.libraries) newLibraries.add(library);
         }
         result.libraries = newLibraries;
      }

      if (this.compatibilityRules != null) {
         if (result.compatibilityRules == null) result.compatibilityRules = Lists.newArrayList();
         for (CompatibilityRule compatibilityRule : this.compatibilityRules) {
            result.compatibilityRules.add(new CompatibilityRule(compatibilityRule));
         }
      }
      return result;
   }

   public CompleteMinecraftVersion getSavableVersion() {
      return Objects.firstNonNull(this.savableVersion, this);
   }

   public static class ModernArguments {
      private List<JsonElement> game;
      private List<JsonElement> jvm;
   }

   public static class AssetIndexInfo extends Library.DownloadInfo {
      private long totalSize;

      public long getTotalSize() {
         return this.totalSize;
      }
   }

   public static class LoggingConfiguration {
      private String argument;
      private Library.DownloadInfo file;
      private String type;

      public String getArgument() {
         return this.argument;
      }

      public Library.DownloadInfo getFile() {
         return this.file;
      }

      public String getType() {
         return this.type;
      }
   }

   public static class JavaVersion {
      private String component;
      private int majorVersion;

      public String getComponent() {
         return this.component;
      }

      public int getMajorVersion() {
         return this.majorVersion;
      }
   }
}
