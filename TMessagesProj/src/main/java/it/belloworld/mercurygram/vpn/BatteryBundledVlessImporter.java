package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class BatteryBundledVlessImporter {
    private static final String ASSET_NAME = "battery_default_vless_profiles.json";
    private static final String PREFS = "battery_vpn";
    private static final String KEY_IMPORTED_VERSION = "bundledProfilesImportedVersion";
    private static final int IMPORT_VERSION = 1;

    private BatteryBundledVlessImporter() {
    }

    public static void importIfPresent(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_IMPORTED_VERSION, 0) >= IMPORT_VERSION) {
            return;
        }
        ArrayList<BatteryVpnProfile> profiles = readProfiles(appContext);
        if (profiles == null) {
            return;
        }
        if (!profiles.isEmpty()) {
            BatteryVpnStore store = new BatteryVpnStore(appContext);
            store.addProfilesIfMissing(profiles);
            if (BatteryVpnStore.MODE_OFF.equals(store.getMode()) || BatteryVpnStore.MODE_SYSTEM.equals(store.getMode())) {
                store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
            }
        }
        prefs.edit().putInt(KEY_IMPORTED_VERSION, IMPORT_VERSION).apply();
    }

    private static ArrayList<BatteryVpnProfile> readProfiles(Context context) {
        ArrayList<BatteryVpnProfile> profiles = new ArrayList<>();
        try (InputStream stream = context.getAssets().open(ASSET_NAME)) {
            String raw = readString(stream);
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                String link;
                String name = "";
                if (item instanceof JSONObject) {
                    JSONObject object = (JSONObject) item;
                    link = object.optString("link", "").trim();
                    name = object.optString("name", "").trim();
                } else {
                    link = array.optString(i, "").trim();
                }
                if (link.length() == 0) {
                    continue;
                }
                ParsedVless parsed = VlessUriParser.parse(link);
                if (name.length() == 0) {
                    name = parsed.name != null && !parsed.name.isEmpty() ? parsed.name : parsed.server;
                }
                profiles.add(new BatteryVpnProfile(name, link));
            }
        } catch (java.io.FileNotFoundException ignored) {
            return null;
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("Failed to import bundled VLESS profiles");
            }
        }
        return profiles;
    }

    private static String readString(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
