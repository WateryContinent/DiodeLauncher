package net.minecraft.launcher.profile;

import com.mojang.authlib.AuthenticationService;
import com.mojang.authlib.BaseUserAuthentication;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.UserType;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.util.UUIDTypeAdapter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Local-only authentication object used by the restored launcher's
 * "Play Offline" mode. It never contacts Mojang/Microsoft services.
 */
public final class OfflineUserAuthentication extends BaseUserAuthentication {
    private static final String STORAGE_KEY_OFFLINE = "offline";

    public OfflineUserAuthentication(AuthenticationService authenticationService, String username) {
        super(authenticationService);
        initialize(username);
    }

    private OfflineUserAuthentication(AuthenticationService authenticationService) {
        super(authenticationService);
    }

    public static OfflineUserAuthentication fromStorage(
            AuthenticationService authenticationService,
            Map<String, Object> credentials) {
        OfflineUserAuthentication auth = new OfflineUserAuthentication(authenticationService);
        auth.loadFromStorage(credentials);
        return auth;
    }

    private void initialize(String requestedName) {
        String username = sanitizeUsername(requestedName);
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> credentials = new HashMap<>();
        credentials.put("username", username);
        credentials.put("userid", UUIDTypeAdapter.fromUUID(uuid));
        credentials.put("displayName", username);
        credentials.put("uuid", UUIDTypeAdapter.fromUUID(uuid));
        credentials.put(STORAGE_KEY_OFFLINE, "true");
        loadFromStorage(credentials);
    }

    public static String sanitizeUsername(String requestedName) {
        if (requestedName == null) {
            return "Player";
        }

        String trimmed = requestedName.trim();
        if (trimmed.matches("[A-Za-z0-9_]{1,16}")) {
            return trimmed;
        }

        return "Player";
    }

    @Override
    public void loadFromStorage(Map<String, Object> credentials) {
        super.loadFromStorage(credentials);
        setUserType(UserType.LEGACY);
    }

    @Override
    public Map<String, Object> saveForStorage() {
        Map<String, Object> result = super.saveForStorage();
        result.put(STORAGE_KEY_OFFLINE, "true");
        return result;
    }

    @Override
    public void logIn() throws AuthenticationException {
        // Already represented by the local GameProfile; no network request.
    }

    @Override
    public boolean canPlayOnline() {
        return false;
    }

    @Override
    public GameProfile[] getAvailableProfiles() {
        GameProfile profile = getSelectedProfile();
        return profile == null ? new GameProfile[0] : new GameProfile[]{profile};
    }

    @Override
    public void selectGameProfile(GameProfile profile) throws AuthenticationException {
        if (profile == null) {
            throw new IllegalArgumentException("profile");
        }
        if (getSelectedProfile() == null || !getSelectedProfile().equals(profile)) {
            throw new AuthenticationException("Offline mode has a single fixed profile");
        }
    }

    @Override
    public String getAuthenticatedToken() {
        return "-";
    }

    public String getStorageId() {
        return UUIDTypeAdapter.fromUUID(getSelectedProfile().getId());
    }
}
