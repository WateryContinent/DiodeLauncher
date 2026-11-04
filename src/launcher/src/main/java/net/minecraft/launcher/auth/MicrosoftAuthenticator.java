package net.minecraft.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.launcher.LauncherConstants;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Microsoft account sign-in for Minecraft: Java Edition.
 *
 * <pre>
 * Microsoft OAuth (device code) -> Xbox Live user token -> XSTS token
 *    -> Minecraft access token -> Minecraft profile
 * </pre>
 *
 * The device code flow shows the player a short code to enter at
 * microsoft.com/link, so the launcher never sees their password and needs no
 * embedded browser or local redirect server.
 */
public class MicrosoftAuthenticator {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final String SCOPE = "XboxLive.signin offline_access";
   // Xbox error codes (XErr) returned by XSTS for accounts that can't play yet.
   private static final long XERR_NO_XBOX_ACCOUNT = 2148916233L;
   private static final long XERR_COUNTRY_UNAVAILABLE = 2148916235L;
   private static final long XERR_ADULT_VERIFICATION = 2148916236L;
   private static final long XERR_ADULT_VERIFICATION_KR = 2148916237L;
   private static final long XERR_CHILD_ACCOUNT = 2148916238L;
   private final Proxy proxy;
   private final String clientId;
   private final MicrosoftAuthenticator.Endpoints endpoints;

   public MicrosoftAuthenticator(Proxy proxy) {
      this(proxy, LauncherConstants.getMicrosoftClientId(), MicrosoftAuthenticator.Endpoints.LIVE);
   }

   MicrosoftAuthenticator(Proxy proxy, String clientId, MicrosoftAuthenticator.Endpoints endpoints) {
      this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
      this.clientId = clientId;
      this.endpoints = endpoints;
   }

   public static boolean isConfigured() {
      String clientId = LauncherConstants.getMicrosoftClientId();
      return clientId != null && !clientId.trim().isEmpty();
   }

   /** Step 1: ask Microsoft for a code the player enters in their browser. */
   public MicrosoftAuthenticator.DeviceCode requestDeviceCode() throws AuthenticationException {
      this.requireClientId();
      Map<String, String> form = new LinkedHashMap<>();
      form.put("client_id", this.clientId);
      form.put("scope", SCOPE);
      JsonObject response = this.postForm(this.endpoints.deviceCode, form, "Microsoft");
      if (response.has("error")) {
         String reason = describeOAuthError(response);
         if (reason.contains("AADSTS70002")) {
            // The Azure app registration doesn't allow the device code flow.
            throw new AuthenticationException(
               "Microsoft login isn't enabled for this launcher's Azure app yet (Authentication > Allow public client flows). (" + reason + ")"
            );
         }

         throw new AuthenticationException("Microsoft refused the sign-in request: " + reason);
      }

      return new MicrosoftAuthenticator.DeviceCode(
         string(response, "user_code"),
         string(response, "device_code"),
         string(response, "verification_uri"),
         response.has("expires_in") ? response.get("expires_in").getAsInt() : 900,
         response.has("interval") ? response.get("interval").getAsInt() : 5
      );
   }

   /**
    * Step 2: wait until the player finishes signing in, then complete the
    * Xbox and Minecraft steps. Returns null if {@code cancelled} was set.
    */
   public MicrosoftAuthenticator.Session waitForDeviceLogin(MicrosoftAuthenticator.DeviceCode code, AtomicBoolean cancelled) throws AuthenticationException {
      Map<String, String> form = new LinkedHashMap<>();
      form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
      form.put("client_id", this.clientId);
      form.put("device_code", code.deviceCode);
      long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
      int interval = Math.max(1, code.interval);

      while (!cancelled.get()) {
         if (System.currentTimeMillis() > deadline) {
            throw new AuthenticationException("The sign-in code expired. Please try again.");
         }

         try {
            Thread.sleep(interval * 1000L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
         }

         if (cancelled.get()) {
            return null;
         }

         JsonObject response = this.postForm(this.endpoints.token, form, "Microsoft");
         String error = response.has("error") ? response.get("error").getAsString() : null;
         if (error == null) {
            return this.completeLogin(string(response, "access_token"), string(response, "refresh_token"));
         } else if ("slow_down".equals(error)) {
            interval += 5;
         } else if ("authorization_declined".equals(error)) {
            throw new AuthenticationException("Sign-in was cancelled in the browser.");
         } else if ("expired_token".equals(error)) {
            throw new AuthenticationException("The sign-in code expired. Please try again.");
         } else if (!"authorization_pending".equals(error)) {
            throw new AuthenticationException("Microsoft sign-in failed: " + describeOAuthError(response));
         }
      }

      return null;
   }

   /** Signs in again with a saved refresh token, without asking the player. */
   public MicrosoftAuthenticator.Session refresh(String refreshToken) throws AuthenticationException {
      this.requireClientId();
      Map<String, String> form = new LinkedHashMap<>();
      form.put("grant_type", "refresh_token");
      form.put("client_id", this.clientId);
      form.put("refresh_token", refreshToken);
      form.put("scope", SCOPE);
      JsonObject response = this.postForm(this.endpoints.token, form, "Microsoft");
      if (response.has("error")) {
         // invalid_grant means the token was revoked, expired or belongs to another app.
         throw new InvalidCredentialsException("Your Microsoft sign-in has expired. Please log in again. (" + describeOAuthError(response) + ")");
      }

      String newRefreshToken = response.has("refresh_token") ? string(response, "refresh_token") : refreshToken;
      return this.completeLogin(string(response, "access_token"), newRefreshToken);
   }

   private MicrosoftAuthenticator.Session completeLogin(String microsoftToken, String refreshToken) throws AuthenticationException {
      // Xbox Live user token.
      JsonObject xblRequest = new JsonObject();
      JsonObject xblProperties = new JsonObject();
      xblProperties.addProperty("AuthMethod", "RPS");
      xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
      xblProperties.addProperty("RpsTicket", "d=" + microsoftToken);
      xblRequest.add("Properties", xblProperties);
      xblRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
      xblRequest.addProperty("TokenType", "JWT");
      JsonObject xbl = this.postJson(this.endpoints.xboxUser, xblRequest, null, "Xbox Live");
      String xblToken = string(xbl, "Token");
      String userHash = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

      // XSTS token for Minecraft services.
      String xstsToken = string(this.authorizeXsts(xblToken, "rp://api.minecraftservices.com/"), "Token");

      // Minecraft access token.
      JsonObject mcRequest = new JsonObject();
      mcRequest.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
      JsonObject minecraft = this.postJson(this.endpoints.minecraftLogin, mcRequest, null, "Minecraft");
      String minecraftToken = string(minecraft, "access_token");
      long expiresAt = System.currentTimeMillis() + (minecraft.has("expires_in") ? minecraft.get("expires_in").getAsLong() : 86400L) * 1000L;

      // The Xbox user ID (xuid) is only included for the xboxlive.com relying
      // party. Minecraft uses it for chat reporting; it's optional for login.
      String xuid = null;
      try {
         JsonObject claims = this.authorizeXsts(xblToken, "http://xboxlive.com").getAsJsonObject("DisplayClaims");
         JsonObject user = claims.getAsJsonArray("xui").get(0).getAsJsonObject();
         xuid = user.has("xid") ? user.get("xid").getAsString() : null;
      } catch (AuthenticationException | RuntimeException e) {
         LOGGER.warn("Couldn't look up Xbox user ID; continuing without it", e);
      }

      // Minecraft profile. 404 means the account doesn't own Java Edition (or
      // hasn't picked a name yet), which the launcher treats as a demo account.
      JsonObject profile = this.getJson(this.endpoints.minecraftProfile, minecraftToken);
      String profileId = profile == null ? null : string(profile, "id");
      String profileName = profile == null ? null : string(profile, "name");
      LOGGER.info("Microsoft sign-in complete" + (profileName == null ? " (no Java Edition profile; demo only)" : " for " + profileName));
      return new MicrosoftAuthenticator.Session(refreshToken, minecraftToken, expiresAt, xuid, userHash, profileId, profileName);
   }

   private JsonObject authorizeXsts(String xblToken, String relyingParty) throws AuthenticationException {
      JsonObject request = new JsonObject();
      JsonObject properties = new JsonObject();
      properties.addProperty("SandboxId", "RETAIL");
      JsonArray userTokens = new JsonArray();
      userTokens.add(new JsonPrimitive(xblToken));
      properties.add("UserTokens", userTokens);
      request.add("Properties", properties);
      request.addProperty("RelyingParty", relyingParty);
      request.addProperty("TokenType", "JWT");
      return this.postJson(this.endpoints.xsts, request, null, "Xbox Live");
   }

   private void requireClientId() throws AuthenticationException {
      if (this.clientId == null || this.clientId.trim().isEmpty()) {
         throw new AuthenticationException(
            "Microsoft login isn't set up in this build yet (no Azure client ID). Set MICROSOFT_CLIENT_ID in LauncherConstants or start with -Ddiode.msaClientId=<id>."
         );
      }
   }

   private JsonObject postForm(String url, Map<String, String> form, String service) throws AuthenticationException {
      StringBuilder body = new StringBuilder();
      try {
         for (Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
               body.append('&');
            }

            body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
         }
      } catch (IOException e) {
         throw new AuthenticationException(e);
      }

      // OAuth endpoints report errors such as authorization_pending as JSON
      // with HTTP 400, so return those bodies instead of throwing.
      return this.request(url, "POST", "application/x-www-form-urlencoded", body.toString(), null, service, true);
   }

   private JsonObject postJson(String url, JsonObject json, String bearer, String service) throws AuthenticationException {
      return this.request(url, "POST", "application/json", json.toString(), bearer, service, false);
   }

   /** GET with a Minecraft bearer token; returns null for 404. */
   private JsonObject getJson(String url, String bearer) throws AuthenticationException {
      return this.request(url, "GET", null, null, bearer, "Minecraft", false);
   }

   private JsonObject request(String url, String method, String contentType, String body, String bearer, String service, boolean errorBodyIsResult)
      throws AuthenticationException {
      HttpURLConnection connection;
      int code;
      String text;
      try {
         connection = (HttpURLConnection)new URL(url).openConnection(this.proxy);
         connection.setConnectTimeout(15000);
         connection.setReadTimeout(30000);
         connection.setUseCaches(false);
         connection.setRequestMethod(method);
         connection.setRequestProperty("Accept", "application/json");
         if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
         }

         if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            // Not streaming mode: with it, HttpURLConnection discards the body
            // of a 401, which is where XSTS puts its XErr reason.
            try (OutputStream out = connection.getOutputStream()) {
               out.write(body.getBytes(StandardCharsets.UTF_8));
            }
         }

         code = connection.getResponseCode();
         InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
         text = stream == null ? "" : IOUtils.toString(stream, StandardCharsets.UTF_8);
      } catch (IOException e) {
         throw new AuthenticationUnavailableException("Couldn't reach " + service + " (" + e.getMessage() + "). Check your internet connection.", e);
      }

      JsonObject json = parse(text);
      if (code / 100 == 2) {
         return json == null ? new JsonObject() : json;
      } else if (errorBodyIsResult && json != null) {
         return json;
      } else if (code == 404 && "GET".equals(method)) {
         return null;
      } else if (code == 401 && json != null && json.has("XErr")) {
         throw new AuthenticationException(describeXboxError(json.get("XErr").getAsLong()));
      } else if (code == 403 && text.contains("Invalid app registration")) {
         throw new AuthenticationException(
            "Mojang hasn't approved this launcher's Microsoft app for Minecraft yet. Apply at aka.ms/mce-reviewappid and try again once it's approved."
         );
      } else if (code == 429) {
         throw new AuthenticationUnavailableException(service + " is rate limiting sign-ins. Wait a minute and try again.");
      } else if (code >= 500) {
         throw new AuthenticationUnavailableException(service + " is having problems right now (HTTP " + code + "). Please try again later.");
      } else {
         throw new AuthenticationException(service + " sign-in failed (HTTP " + code + ")" + (text.isEmpty() ? "" : ": " + abbreviate(text)));
      }
   }

   private static String describeXboxError(long xerr) {
      if (xerr == XERR_NO_XBOX_ACCOUNT) {
         return "This Microsoft account doesn't have an Xbox profile yet. Sign in once at xbox.com to create one, then try again.";
      } else if (xerr == XERR_COUNTRY_UNAVAILABLE) {
         return "Xbox Live isn't available in your country or region, so this account can't sign in to Minecraft.";
      } else if (xerr == XERR_ADULT_VERIFICATION || xerr == XERR_ADULT_VERIFICATION_KR) {
         return "This account needs adult verification on xbox.com before it can play.";
      } else if (xerr == XERR_CHILD_ACCOUNT) {
         return "This is a child account. An adult needs to add it to a Microsoft family group before it can play.";
      } else {
         return "Xbox Live refused the sign-in (error " + xerr + ").";
      }
   }

   private static String describeOAuthError(JsonObject response) {
      String error = response.has("error") ? response.get("error").getAsString() : "unknown_error";
      String description = response.has("error_description") ? response.get("error_description").getAsString() : null;
      if (description == null) {
         return error;
      }

      // Azure appends trace IDs and timestamps; keep just the explanation.
      int trace = description.indexOf("Trace ID:");
      String explanation = trace < 0 ? description : description.substring(0, trace);
      int newline = explanation.indexOf('\n');
      return error + ": " + (newline < 0 ? explanation : explanation.substring(0, newline)).trim();
   }

   private static JsonObject parse(String text) {
      if (text == null || text.trim().isEmpty()) {
         return null;
      }

      try {
         JsonElement element = new JsonParser().parse(text);
         return element.isJsonObject() ? element.getAsJsonObject() : null;
      } catch (JsonParseException e) {
         return null;
      }
   }

   private static String string(JsonObject json, String key) throws AuthenticationException {
      JsonElement element = json.get(key);
      if (element == null || element.isJsonNull()) {
         throw new AuthenticationException("Unexpected sign-in response (missing " + key + ")");
      }

      return element.getAsString();
   }

   private static String abbreviate(String text) {
      String single = text.replaceAll("\\s+", " ").trim();
      return single.length() > 200 ? single.substring(0, 200) + "..." : single;
   }

   public static class DeviceCode {
      private final String userCode;
      private final String deviceCode;
      private final String verificationUri;
      private final int expiresIn;
      private final int interval;

      DeviceCode(String userCode, String deviceCode, String verificationUri, int expiresIn, int interval) {
         this.userCode = userCode;
         this.deviceCode = deviceCode;
         this.verificationUri = verificationUri;
         this.expiresIn = expiresIn;
         this.interval = interval;
      }

      public String getUserCode() {
         return this.userCode;
      }

      public String getVerificationUri() {
         return this.verificationUri;
      }
   }

   /** Everything the launcher keeps after a successful sign-in. */
   public static class Session {
      final String refreshToken;
      final String accessToken;
      final long accessTokenExpiresAt;
      final String xuid;
      final String userHash;
      final String profileId;
      final String profileName;

      Session(String refreshToken, String accessToken, long accessTokenExpiresAt, String xuid, String userHash, String profileId, String profileName) {
         this.refreshToken = refreshToken;
         this.accessToken = accessToken;
         this.accessTokenExpiresAt = accessTokenExpiresAt;
         this.xuid = xuid;
         this.userHash = userHash;
         this.profileId = profileId;
         this.profileName = profileName;
      }
   }

   /** Service URLs; replaceable so the flow can be tested against a local server. */
   static class Endpoints {
      static final MicrosoftAuthenticator.Endpoints LIVE = new MicrosoftAuthenticator.Endpoints(
         "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
         "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
         "https://user.auth.xboxlive.com/user/authenticate",
         "https://xsts.auth.xboxlive.com/xsts/authorize",
         "https://api.minecraftservices.com/authentication/login_with_xbox",
         "https://api.minecraftservices.com/minecraft/profile"
      );
      final String deviceCode;
      final String token;
      final String xboxUser;
      final String xsts;
      final String minecraftLogin;
      final String minecraftProfile;

      Endpoints(String deviceCode, String token, String xboxUser, String xsts, String minecraftLogin, String minecraftProfile) {
         this.deviceCode = deviceCode;
         this.token = token;
         this.xboxUser = xboxUser;
         this.xsts = xsts;
         this.minecraftLogin = minecraftLogin;
         this.minecraftProfile = minecraftProfile;
      }
   }
}
