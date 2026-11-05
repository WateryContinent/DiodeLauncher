package net.minecraft.launcher.updater;

import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.versions.CompatibilityRule;
import com.mojang.launcher.versions.ExtractRules;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;

public class Library {
   private static final StrSubstitutor SUBSTITUTOR = new StrSubstitutor(new HashMap<String, String>() {
      {
         this.put("arch", System.getProperty("os.arch").contains("64") ? "64" : "32");
      }
   });
   private String name;
   private List<CompatibilityRule> rules;
   private Map<OperatingSystem, String> natives;
   private ExtractRules extract;
   private String url;
   private LibraryDownloads downloads;

   public Library() {
   }

   public Library(String name) {
      if (name != null && name.length() != 0) {
         this.name = name;
      } else {
         throw new IllegalArgumentException("Library name cannot be null or empty");
      }
   }

   public Library(Library library) {
      this.name = library.name;
      this.url = library.url;
      this.downloads = library.downloads;
      if (library.extract != null) {
         this.extract = new ExtractRules(library.extract);
      }

      if (library.rules != null) {
         this.rules = new ArrayList<>();
         for (CompatibilityRule compatibilityRule : library.rules) {
            this.rules.add(new CompatibilityRule(compatibilityRule));
         }
      }

      if (library.natives != null) {
         this.natives = new LinkedHashMap<>();
         for (Entry<OperatingSystem, String> entry : library.getNatives().entrySet()) {
            this.natives.put(entry.getKey(), entry.getValue());
         }
      }
   }

   public String getName() {
      return this.name;
   }

   /**
    * Old modded versions (Forge for 1.6 and 1.7.2) ask for launchwrapper 1.11 or older, which
    * crashes on Java 8 with a ConcurrentModificationException in Launch.launch. Mojang doesn't
    * offer Java 7 any more, so use launchwrapper 1.12 instead: it fixes that bug and works with
    * the same tweakers (MultiMC and Prism Launcher do the same).
    */
   public Library withJava8Fixes() {
      String prefix = "net.minecraft:launchwrapper:";
      if (this.name == null || !this.name.startsWith(prefix)) {
         return this;
      }

      String[] version = this.name.substring(prefix.length()).split("\\.");
      try {
         int major = Integer.parseInt(version[0]);
         int minor = version.length > 1 ? Integer.parseInt(version[1]) : 0;
         if (major > 1 || major == 1 && minor >= 12) {
            return this;
         }
      } catch (NumberFormatException e) {
         return this;
      }

      Library fixed = new Library(this);
      fixed.name = prefix + "1.12";
      fixed.url = null;
      fixed.downloads = null;
      return fixed;
   }

   public Library addNative(OperatingSystem operatingSystem, String name) {
      if (operatingSystem == null || !operatingSystem.isSupported()) {
         throw new IllegalArgumentException("Cannot add native for unsupported OS");
      }
      if (name == null || name.length() == 0) {
         throw new IllegalArgumentException("Cannot add native for null or empty name");
      }
      if (this.natives == null) {
         this.natives = new EnumMap<>(OperatingSystem.class);
      }
      this.natives.put(operatingSystem, name);
      return this;
   }

   public List<CompatibilityRule> getCompatibilityRules() {
      return this.rules;
   }

   public boolean appliesToCurrentEnvironment() {
      if (this.rules == null) {
         return true;
      }
      CompatibilityRule.Action lastAction = CompatibilityRule.Action.DISALLOW;
      for (CompatibilityRule compatibilityRule : this.rules) {
         CompatibilityRule.Action action = compatibilityRule.getAppliedAction();
         if (action != null) {
            lastAction = action;
         }
      }
      return lastAction == CompatibilityRule.Action.ALLOW;
   }

   public Map<OperatingSystem, String> getNatives() {
      return this.natives;
   }

   public ExtractRules getExtractRules() {
      return this.extract;
   }

   public Library setExtractRules(ExtractRules rules) {
      this.extract = rules;
      return this;
   }

   private String[] getCoordinateParts() {
      if (this.name == null) {
         throw new IllegalStateException("Cannot get artifact path of empty/blank artifact");
      }
      String[] parts = this.name.split(":");
      if (parts.length < 3) {
         throw new IllegalStateException("Invalid library coordinate: " + this.name);
      }
      return parts;
   }

   public String getArtifactBaseDir() {
      String[] parts = this.getCoordinateParts();
      return String.format("%s/%s/%s", parts[0].replaceAll("\\.", "/"), parts[1], parts[2]);
   }

   public String getArtifactPath() {
      DownloadInfo info = this.getDownloadInfo(null);
      if (info != null && info.getPath() != null && !info.getPath().isEmpty()) {
         return info.getPath();
      }
      return this.getArtifactPath(null);
   }

   public String getArtifactPath(String classifier) {
      DownloadInfo info = this.getDownloadInfo(classifier);
      if (info != null && info.getPath() != null && !info.getPath().isEmpty()) {
         return info.getPath();
      }
      return String.format("%s/%s", this.getArtifactBaseDir(), this.getArtifactFilename(classifier));
   }

   public String getArtifactFilename(String classifier) {
      String[] parts = this.getCoordinateParts();
      String coordinateClassifier = parts.length >= 4 ? parts[3] : null;
      String effectiveClassifier = StringUtils.isEmpty(classifier) ? coordinateClassifier : classifier;
      String result = String.format("%s-%s%s.jar", parts[1], parts[2], StringUtils.isEmpty(effectiveClassifier) ? "" : "-" + effectiveClassifier);
      return SUBSTITUTOR.replace(result);
   }

   public DownloadInfo getDownloadInfo(String classifier) {
      if (this.downloads == null) {
         return null;
      }
      if (StringUtils.isEmpty(classifier)) {
         return this.downloads.artifact;
      }
      return this.downloads.classifiers == null ? null : this.downloads.classifiers.get(SUBSTITUTOR.replace(classifier));
   }

   public boolean hasArtifact() {
      if (this.downloads != null) {
         return this.downloads.artifact != null;
      }
      // Old-style version files list native libraries (lwjgl-platform, jinput-platform) with only
      // a natives jar, so there's no plain jar to download or put on the classpath.
      return this.name != null && this.natives == null;
   }

   @Override
   public String toString() {
      return "Library{name='" + this.name + '\'' + ", rules=" + this.rules + ", natives=" + this.natives + ", extract=" + this.extract + '}';
   }

   public boolean hasCustomUrl() {
      return this.url != null;
   }

   public String getDownloadUrl() {
      return this.url != null ? this.url : "https://libraries.minecraft.net/";
   }

   public static class LibraryDownloads {
      private DownloadInfo artifact;
      private Map<String, DownloadInfo> classifiers;
   }

   public static class DownloadInfo {
      private String id;
      private String path;
      private String sha1;
      private long size;
      private String url;

      public String getId() {
         return this.id;
      }

      public String getPath() {
         return this.path;
      }

      public String getSha1() {
         return this.sha1;
      }

      public long getSize() {
         return this.size;
      }

      public String getUrl() {
         return this.url;
      }
   }
}
