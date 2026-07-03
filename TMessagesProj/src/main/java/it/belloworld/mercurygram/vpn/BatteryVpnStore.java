package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONArray;
import org.json.JSONObject;

public final class BatteryVpnStore {
    public static final String MODE_OFF = "off";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LOCAL_PROXY = "local_proxy";
    public static final String MODE_EMBEDDED = "embedded";

    private static final String PREFS = "battery_vpn";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PROFILE_NAME = "profileName";
    private static final String KEY_PROFILE_LINK = "profileLink";
    private static final String KEY_PROFILE_LIST = "profileList";
    private static final String KEY_ACTIVE_PROFILE_INDEX = "activeProfileIndex";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_STATUS = "status";
    private static final String KEY_LOCAL_PROXY_PORT = "localProxyPort";
    private static final String KEY_LOCAL_PROXY_USER = "localProxyUser";
    private static final String KEY_LOCAL_PROXY_PASSWORD = "localProxyPassword";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS = "MercurygramBatteryVpnPrefs";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;

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
        ArrayList<BatteryVpnProfile> profiles = getProfiles();
        if (profiles.isEmpty()) {
            return null;
        }
        int index = getActiveProfileIndex();
        if (index < 0 || index >= profiles.size()) {
            index = 0;
        }
        return profiles.get(index);
    }

    public ArrayList<BatteryVpnProfile> getProfiles() {
        String stored = getSensitiveString(KEY_PROFILE_LIST);
        ArrayList<BatteryVpnProfile> profiles = new ArrayList<>();
        if (stored != null && stored.length() > 0) {
            try {
                JSONArray array = new JSONArray(stored);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    String link = object.optString("link", "").trim();
                    if (link.length() == 0) {
                        continue;
                    }
                    String name = object.optString("name", "").trim();
                    profiles.add(new BatteryVpnProfile(profileName(name, link), link));
                }
            } catch (Exception ignored) {
                profiles.clear();
            }
        }
        if (profiles.isEmpty()) {
            BatteryVpnProfile legacy = getLegacyProfile();
            if (legacy != null) {
                profiles.add(legacy);
                saveProfiles(profiles);
                setActiveProfileIndex(0);
            }
        }
        int index = getActiveProfileIndex();
        if (!profiles.isEmpty() && (index < 0 || index >= profiles.size())) {
            setActiveProfileIndex(0);
        }
        return profiles;
    }

    public void saveProfile(BatteryVpnProfile profile) {
        ArrayList<BatteryVpnProfile> profiles = getProfiles();
        int index = getActiveProfileIndex();
        if (index >= 0 && index < profiles.size()) {
            profiles.set(index, normalizedProfile(profile));
        } else {
            profiles.add(normalizedProfile(profile));
            index = profiles.size() - 1;
        }
        saveProfiles(profiles);
        setActiveProfileIndex(index);
    }

    public int addProfile(BatteryVpnProfile profile) {
        ArrayList<BatteryVpnProfile> profiles = getProfiles();
        BatteryVpnProfile normalized = normalizedProfile(profile);
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).link.equals(normalized.link)) {
                profiles.set(i, normalized);
                saveProfiles(profiles);
                setActiveProfileIndex(i);
                return i;
            }
        }
        profiles.add(normalized);
        int index = profiles.size() - 1;
        saveProfiles(profiles);
        setActiveProfileIndex(index);
        return index;
    }

    public void selectProfile(int index) {
        ArrayList<BatteryVpnProfile> profiles = getProfiles();
        if (index >= 0 && index < profiles.size()) {
            setActiveProfileIndex(index);
        }
    }

    public void removeProfile(int index) {
        ArrayList<BatteryVpnProfile> profiles = getProfiles();
        if (index < 0 || index >= profiles.size()) {
            return;
        }
        profiles.remove(index);
        int activeIndex = getActiveProfileIndex();
        if (profiles.isEmpty()) {
            setActiveProfileIndex(0);
        } else if (activeIndex == index) {
            setActiveProfileIndex(Math.min(index, profiles.size() - 1));
        } else if (activeIndex > index) {
            setActiveProfileIndex(activeIndex - 1);
        }
        saveProfiles(profiles);
    }

    public int getActiveProfileIndex() {
        return prefs.getInt(KEY_ACTIVE_PROFILE_INDEX, 0);
    }

    private void setActiveProfileIndex(int index) {
        prefs.edit().putInt(KEY_ACTIVE_PROFILE_INDEX, Math.max(index, 0)).apply();
    }

    private void saveProfiles(ArrayList<BatteryVpnProfile> profiles) {
        JSONArray array = new JSONArray();
        for (BatteryVpnProfile profile : profiles) {
            BatteryVpnProfile normalized = normalizedProfile(profile);
            JSONObject object = new JSONObject();
            try {
                object.put("name", normalized.name);
                object.put("link", normalized.link);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        SharedPreferences.Editor editor = prefs.edit();
        putSensitiveString(editor, KEY_PROFILE_LIST, array.toString());
        editor.apply();
    }

    private BatteryVpnProfile getLegacyProfile() {
        String link = getSensitiveString(KEY_PROFILE_LINK);
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        String name = getSensitiveString(KEY_PROFILE_NAME);
        return new BatteryVpnProfile(profileName(name, link), link.trim());
    }

    private BatteryVpnProfile normalizedProfile(BatteryVpnProfile profile) {
        String link = profile != null && profile.link != null ? profile.link.trim() : "";
        return new BatteryVpnProfile(profileName(profile != null ? profile.name : "", link), link);
    }

    private static String profileName(String name, String link) {
        if (name != null && name.trim().length() > 0) {
            return name.trim();
        }
        try {
            ParsedVless parsed = VlessUriParser.parse(link);
            return parsed.name != null && !parsed.name.isEmpty() ? parsed.name : parsed.server;
        } catch (Exception ignored) {
            return "VLESS";
        }
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
        return getSensitiveString(KEY_LOCAL_PROXY_USER);
    }

    public String getLocalProxyPassword() {
        return getSensitiveString(KEY_LOCAL_PROXY_PASSWORD);
    }

    public String[] ensureLocalProxyCredentials() {
        String username = getLocalProxyUsername();
        String password = getLocalProxyPassword();
        if (username != null && username.length() > 0 && password != null && password.length() > 0) {
            return new String[]{username, password};
        }
        username = "tg" + randomHex(8);
        password = randomHex(24);
        SharedPreferences.Editor editor = prefs.edit();
        putSensitiveString(editor, KEY_LOCAL_PROXY_USER, username);
        putSensitiveString(editor, KEY_LOCAL_PROXY_PASSWORD, password);
        editor.commit();
        return new String[]{username, password};
    }

    private String getSensitiveString(String key) {
        String stored = prefs.getString(key, "");
        if (stored == null || stored.length() == 0) {
            return "";
        }
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            SharedPreferences.Editor editor = prefs.edit();
            putSensitiveString(editor, key, stored);
            editor.apply();
            return stored;
        }
        try {
            return decrypt(stored);
        } catch (Exception e) {
            return "";
        }
    }

    private void putSensitiveString(SharedPreferences.Editor editor, String key, String value) {
        try {
            editor.putString(key, encrypt(value != null ? value : ""));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to protect VPN settings", e);
        }
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return ENCRYPTED_PREFIX
                + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String payload = stored.substring(ENCRYPTED_PREFIX.length());
        int separator = payload.indexOf(':');
        if (separator <= 0 || separator == payload.length() - 1) {
            return "";
        }
        byte[] iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing != null) {
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
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
