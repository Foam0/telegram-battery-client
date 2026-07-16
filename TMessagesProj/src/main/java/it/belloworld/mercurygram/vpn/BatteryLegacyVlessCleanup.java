package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;

/** Removes profiles that older builds imported from the private bundled asset. */
public final class BatteryLegacyVlessCleanup {
    private static final String PREFS = "battery_vpn";
    private static final String KEY_CLEANUP_VERSION = "legacyBundledProfilesCleanupVersion";
    private static final int CLEANUP_VERSION = 1;
    private static final String[] LEGACY_PROFILE_HASHES = {
            "35508822ba780518cfc9f6a1273013a12c7bd66207a4139dc31679bc38cc716b",
            "9b669016ac3ee862c59336b778e6eb7b2c600b887d3722ed002c0c7459a70c28"
    };

    private BatteryLegacyVlessCleanup() {
    }

    public static void runOnce(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_CLEANUP_VERSION, 0) >= CLEANUP_VERSION) {
            return;
        }

        BatteryVpnStore store = new BatteryVpnStore(appContext);
        ArrayList<BatteryVpnProfile> profiles = store.getProfiles();
        for (int i = profiles.size() - 1; i >= 0; i--) {
            if (isLegacyProfile(profiles.get(i).link)) {
                store.removeProfile(i);
            }
        }
        if (store.getProfiles().isEmpty() && BatteryVpnStore.MODE_LOCAL_PROXY.equals(store.getMode())) {
            store.setMode(BatteryVpnStore.MODE_OFF);
        }
        prefs.edit().putInt(KEY_CLEANUP_VERSION, CLEANUP_VERSION).apply();
    }

    private static boolean isLegacyProfile(String link) {
        String hash = sha256(link == null ? "" : link.trim());
        for (String legacyHash : LEGACY_PROFILE_HASHES) {
            if (legacyHash.equals(hash)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(item & 0x0f, 16));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
