package net.minecraft.launcher.auth;

import com.mojang.authlib.AuthenticationService;
import com.mojang.authlib.BaseUserAuthentication;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.UserType;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.util.UUIDTypeAdapter;
import java.net.Proxy;
import java.util.Map;
import net.minecraft.launcher.profile.AuthenticationDatabase;

/**
 * A Microsoft account signed in through {@link MicrosoftAuthenticator}.
 *
 * <p>The Minecraft access token lasts about a day; the Microsoft refresh token
 * is kept so {@link #logIn()} can get a new one without asking the player.</p>
 */
public class MicrosoftUserAuthentication extends BaseUserAuthentication {
   public static final String STORAGE_TYPE = "msa";
   private static final String STORAGE_KEY_TYPE = "type";
   private static final String STORAGE_KEY_REFRESH_TOKEN = "refreshToken";
   private static final String STORAGE_KEY_ACCESS_TOKEN = "accessToken";
   private static final String STORAGE_KEY_EXPIRES_AT = "accessTokenExpiresAt";
   private static final String STORAGE_KEY_XUID = "xuid";
   // Refresh a little early so a game started now doesn't get a token that dies mid-session.
   private static final long EXPIRY_MARGIN_MS = 10L * 60L * 1000L;
   private final Proxy proxy;
   private String refreshToken;
   private String accessToken;
   private long accessTokenExpiresAt;
   private String xuid;

   public MicrosoftUserAuthentication(AuthenticationService authenticationService, Proxy proxy) {
      super(authenticationService);
      this.proxy = proxy;
   }

   public static MicrosoftUserAuthentication fromStorage(AuthenticationService authenticationService, Proxy proxy, Map<String, Object> credentials) {
      MicrosoftUserAuthentication auth = new MicrosoftUserAuthentication(authenticationService, proxy);
      auth.loadFromStorage(credentials);
      return auth;
   }

   public static boolean isStoredMicrosoftAccount(Map<String, Object> credentials) {
      return STORAGE_TYPE.equals(String.valueOf(credentials.get(STORAGE_KEY_TYPE)));
   }

   /** Applies a finished sign-in (new or refreshed). */
   public void applySession(MicrosoftAuthenticator.Session session) {
      if (session.xuid != null) {
         this.xuid = session.xuid;
      }

      // BaseUserAuthentication refuses username changes while online, so
      // update the identity before installing the new token.
      this.accessToken = null;
      this.setUserType(UserType.MSA);
      this.setUserid(this.xuid != null ? this.xuid : session.userHash);
      if (session.profileId != null) {
         this.setUsername(session.profileName);
         this.setSelectedProfile(new GameProfile(UUIDTypeAdapter.fromString(session.profileId), session.profileName));
      } else {
         this.setUsername(this.getUserID());
         this.setSelectedProfile(null);
      }

      this.refreshToken = session.refreshToken;
      this.accessToken = session.accessToken;
      this.accessTokenExpiresAt = session.accessTokenExpiresAt;
   }

   /** The key this account is stored under in the {@link AuthenticationDatabase}. */
   public String getStorageId() {
      GameProfile profile = this.getSelectedProfile();
      return profile == null ? AuthenticationDatabase.DEMO_UUID_PREFIX + this.getUserID() : UUIDTypeAdapter.fromUUID(profile.getId());
   }

   @Override
   public boolean canLogIn() {
      return this.refreshToken != null && !this.canPlayOnline();
   }

   @Override
   public void logIn() throws AuthenticationException {
      if (this.refreshToken == null) {
         throw new InvalidCredentialsException("Please log in with Microsoft again.");
      }

      this.applySession(new MicrosoftAuthenticator(this.proxy).refresh(this.refreshToken));
   }

   @Override
   public void logOut() {
      super.logOut();
      this.refreshToken = null;
      this.accessToken = null;
      this.accessTokenExpiresAt = 0L;
      this.xuid = null;
   }

   /** Signed in at some point; the token may have expired (see {@link #canPlayOnline()}). */
   @Override
   public boolean isLoggedIn() {
      return this.accessToken != null;
   }

   @Override
   public boolean canPlayOnline() {
      return this.accessToken != null && System.currentTimeMillis() < this.accessTokenExpiresAt - EXPIRY_MARGIN_MS;
   }

   @Override
   public GameProfile[] getAvailableProfiles() {
      GameProfile profile = this.getSelectedProfile();
      return profile == null ? new GameProfile[0] : new GameProfile[]{profile};
   }

   @Override
   public void selectGameProfile(GameProfile profile) throws AuthenticationException {
      throw new AuthenticationException("Microsoft accounts have a single Minecraft profile");
   }

   @Override
   public String getAuthenticatedToken() {
      return this.accessToken;
   }

   @Override
   public UserType getUserType() {
      return this.isLoggedIn() ? UserType.MSA : null;
   }

   public String getXuid() {
      return this.xuid;
   }

   @Override
   public void loadFromStorage(Map<String, Object> credentials) {
      super.loadFromStorage(credentials);
      this.refreshToken = stringOrNull(credentials.get(STORAGE_KEY_REFRESH_TOKEN));
      this.accessToken = stringOrNull(credentials.get(STORAGE_KEY_ACCESS_TOKEN));
      this.xuid = stringOrNull(credentials.get(STORAGE_KEY_XUID));
      try {
         this.accessTokenExpiresAt = Long.parseLong(String.valueOf(credentials.get(STORAGE_KEY_EXPIRES_AT)));
      } catch (NumberFormatException e) {
         this.accessTokenExpiresAt = 0L;
      }

      this.setUserType(UserType.MSA);
   }

   @Override
   public Map<String, Object> saveForStorage() {
      Map<String, Object> result = super.saveForStorage();
      result.put(STORAGE_KEY_TYPE, STORAGE_TYPE);
      if (this.refreshToken != null) {
         result.put(STORAGE_KEY_REFRESH_TOKEN, this.refreshToken);
      }

      if (this.accessToken != null) {
         result.put(STORAGE_KEY_ACCESS_TOKEN, this.accessToken);
         result.put(STORAGE_KEY_EXPIRES_AT, Long.toString(this.accessTokenExpiresAt));
      }

      if (this.xuid != null) {
         result.put(STORAGE_KEY_XUID, this.xuid);
      }

      return result;
   }

   private static String stringOrNull(Object value) {
      return value == null || "null".equals(String.valueOf(value)) ? null : String.valueOf(value);
   }
}
