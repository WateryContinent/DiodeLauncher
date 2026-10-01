package com.mojang.launcher.versions;

import com.mojang.launcher.OperatingSystem;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompatibilityRule {
   private CompatibilityRule.Action action = CompatibilityRule.Action.ALLOW;
   private CompatibilityRule.OSRestriction os;

   public CompatibilityRule() {
   }

   public CompatibilityRule(CompatibilityRule compatibilityRule) {
      this.action = compatibilityRule.action;
      if (compatibilityRule.os != null) {
         this.os = new CompatibilityRule.OSRestriction(compatibilityRule.os);
      }
   }

   public CompatibilityRule.Action getAppliedAction() {
      return this.os != null && !this.os.isCurrentOperatingSystem() ? null : this.action;
   }

   public CompatibilityRule.Action getAction() {
      return this.action;
   }

   public CompatibilityRule.OSRestriction getOs() {
      return this.os;
   }

   @Override
   public String toString() {
      return "Rule{action=" + this.action + ", os=" + this.os + '}';
   }

   public enum Action {
      ALLOW,
      DISALLOW;
   }

   public class OSRestriction {
      private OperatingSystem name;
      private String version;
      private String arch;

      public OSRestriction() {
      }

      public OperatingSystem getName() {
         return this.name;
      }

      public String getVersion() {
         return this.version;
      }

      public String getArch() {
         return this.arch;
      }

      public OSRestriction(CompatibilityRule.OSRestriction osRestriction) {
         this.name = osRestriction.name;
         this.version = osRestriction.version;
         this.arch = osRestriction.arch;
      }

      public boolean isCurrentOperatingSystem() {
         if (this.name != null && this.name != OperatingSystem.getCurrentPlatform()) {
            return false;
         }

         if (this.version != null) {
            try {
               Pattern pattern = Pattern.compile(this.version);
               Matcher matcher = pattern.matcher(System.getProperty("os.version"));
               if (!matcher.matches()) {
                  return false;
               }
            } catch (Throwable ignored) {
            }
         }

         if (this.arch != null) {
            try {
               Pattern pattern = Pattern.compile(this.arch);
               Matcher matcher = pattern.matcher(System.getProperty("os.arch"));
               if (!matcher.matches()) {
                  return false;
               }
            } catch (Throwable ignored) {
            }
         }

         return true;
      }

      @Override
      public String toString() {
         return "OSRestriction{name=" + this.name + ", version='" + this.version + '\'' + ", arch='" + this.arch + '\'' + '}';
      }
   }
}
