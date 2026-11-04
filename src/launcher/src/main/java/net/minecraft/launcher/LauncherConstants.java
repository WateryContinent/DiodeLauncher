package net.minecraft.launcher;

import java.net.URI;
import java.net.URISyntaxException;

public class LauncherConstants {
   public static final int VERSION_FORMAT = 17;
   // Diode's Azure app registration (public client; not a secret). It must be
   // approved by Mojang (aka.ms/mce-reviewappid) before Minecraft accepts it.
   // -Ddiode.msaClientId=<id> overrides it, e.g. for a fork's own app.
   public static final String MICROSOFT_CLIENT_ID = "c53d746c-e71c-4257-9c36-f4936cc433ff";
   public static final URI URL_REGISTER = constantURI("https://signup.live.com/");
   public static final URI URL_LOGIN_HELP = constantURI("https://help.minecraft.net/");
   public static final String URL_DOWNLOAD_BASE = "https://s3.amazonaws.com/Minecraft.Download/";
   public static final String URL_VERSIONS_JSON = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
   public static final String URL_RESOURCE_BASE = "http://resources.download.minecraft.net/";
   public static final String URL_LIBRARY_BASE = "https://libraries.minecraft.net/";
   public static final String URL_BLOG = "http://mcupdate.tumblr.com";
   public static final String URL_SUPPORT = "http://help.mojang.com/?ref=launcher";
   public static final String URL_STATUS_CHECKER = "http://status.mojang.com/check";
   public static final int UNVERSIONED_BOOTSTRAP_VERSION = 0;
   public static final int MINIMUM_BOOTSTRAP_SUPPORTED = 4;
   public static final int SUPER_COOL_BOOTSTRAP_VERSION = 100;
   public static final String URL_BOOTSTRAP_DOWNLOAD = "https://mojang.com/2013/06/minecraft-1-6-pre-release/";
   public static final String[] BOOTSTRAP_OUT_OF_DATE_BUTTONS = new String[]{"Go to URL", "Close"};
   public static final String[] CONFIRM_PROFILE_DELETION_OPTIONS = new String[]{"Delete profile", "Cancel"};
   public static final URI URL_FORGOT_USERNAME = constantURI("http://help.mojang.com/customer/portal/articles/1233873?ref=launcher");
   public static final URI URL_FORGOT_PASSWORD_MINECRAFT = constantURI(
      "http://help.mojang.com/customer/portal/articles/329524-change-or-forgot-password?ref=launcher"
   );
   public static final URI URL_FORGOT_MIGRATED_EMAIL = constantURI(
      "http://help.mojang.com/customer/portal/articles/1205055-minecraft-launcher-error---migrated-account?ref=launcher"
   );
   public static final URI URL_DEMO_HELP = constantURI("https://help.mojang.com/customer/portal/articles/1218766-can-only-play-minecraft-demo?ref=launcher");
   public static final URI URL_UPGRADE_WINDOWS = constantURI("https://launcher.mojang.com/download/MinecraftInstaller.msi");
   public static final URI URL_UPGRADE_OSX = constantURI("https://launcher.mojang.com/download/Minecraft.dmg");
   public static final int MAX_NATIVES_LIFE_IN_SECONDS = 3600;
   public static final int MAX_SKIN_LIFE_IN_SECONDS = 604800;

   public static URI constantURI(String input) {
      try {
         return new URI(input);
      } catch (URISyntaxException e) {
         throw new Error(e);
      }
   }

   public static String getMicrosoftClientId() {
      return System.getProperty("diode.msaClientId", MICROSOFT_CLIENT_ID);
   }

   public static String getVersionName() {
      return LauncherConstants.class.getPackage().getImplementationVersion();
   }
}
