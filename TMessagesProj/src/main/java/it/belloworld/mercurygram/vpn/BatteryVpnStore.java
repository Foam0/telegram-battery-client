package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class BatteryVpnStore {
    public static final String MODE_OFF = "off";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LOCAL_PROXY = "local_proxy";
    public static final String MODE_EMBEDDED = "embedded";

    private static final String PREFS = "battery_vpn";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PROFILE_NAME = "profileName";
    private static final String KEY_PROFILE_LINK = "profileLink";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_STATUS = "status";
    private static final String KEY_LOCAL_PROXY_PORT = "localProxyPort";
    private static final String KEY_LOCAL_PROXY_USER = "localProxyUser";
    private static final String KEY_LOCAL_PROXY_PASSWORD = "localProxyPassword";

    private final SharedPreferences prefs;

    public BatteryVpnStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getMode() {
        return prefs.getString(KEY_MODE, MODE_OFF);
    }

    public void setMode(String mode) {
        if (!MODE_SYSTEM.equals(mode) && !MODE_LOCAL_PROXY.equals(mode) && !MODE_EMBEDDED.equals(mode)) {
            mode = MODE_OFF;
        }
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    public BatteryVpnProfile getProfile() {
        String link = prefs.getString(KEY_PROFILE_LINK, "");
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        String name = prefs.getString(KEY_PROFILE_NAME, "");
        if (name == null || name.trim().isEmpty()) {
            try {
                ParsedVless parsed = VlessUriParser.parse(link);
                name = parsed.name != null && !parsed.name.isEmpty() ? parsed.name : parsed.server;
            } catch (Exception ignored) {
                name = "VLESS";
            }
        }
        return new BatteryVpnProfile(name, link);
    }

    public void saveProfile(BatteryVpnProfile profile) {
        prefs.edit()
                .putString(KEY_PROFILE_NAME, profile.name)
                .putString(KEY_PROFILE_LINK, profile.link)
                .apply();
    }

    public boolean isConnected() {
        return prefs.getBoolean(KEY_CONNECTED, false);
    }

    public void setConnected(boolean connected) {
        prefs.edit().putBoolean(KEY_CONNECTED, connected).apply();
    }

    public String getStatus() {
        return prefs.getString(KEY_STATUS, "");
    }

    public void setStatus(String status) {
        prefs.edit().putString(KEY_STATUS, status != null ? status : "").apply();
    }

    public int getLocalProxyPort() {
        return prefs.getInt(KEY_LOCAL_PROXY_PORT, 0);
    }

    public void setLocalProxyPort(int port) {
        prefs.edit().putInt(KEY_LOCAL_PROXY_PORT, Math.max(port, 0)).apply();
    }

    public String getLocalProxyUsername() {
        return prefs.getString(KEY_LOCAL_PROXY_USER, "");
    }

    public String getLocalProxyPassword() {
        return prefs.getString(KEY_LOCAL_PROXY_PASSWORD, "");
    }

    public String[] ensureLocalProxyCredentials() {
        String username = getLocalProxyUsername();
        String password = getLocalProxyPassword();
        if (username != null && username.length() > 0 && password != null && password.length() > 0) {
            return new String[]{username, password};
        }
        username = "tg" + randomHex(8);
        password = randomHex(24);
        prefs.edit()
                .putString(KEY_LOCAL_PROXY_USER, username)
                .putString(KEY_LOCAL_PROXY_PASSWORD, password)
                .commit();
        return new String[]{username, password};
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        char[] out = new char[data.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xff;
            out[i * 2] = alphabet[value >>> 4];
            out[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(out);
    }
}
