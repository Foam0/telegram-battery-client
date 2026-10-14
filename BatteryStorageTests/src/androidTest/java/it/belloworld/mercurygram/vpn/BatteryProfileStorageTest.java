package it.belloworld.mercurygram.vpn;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

public class BatteryProfileStorageTest {
    private Context context;private SharedPreferences prefs;private File encrypted;private BatteryVpnStore store;
    private static final String A="vless://00000000-0000-4000-8000-000000000000@example.com#first";
    private static final String B="vless://00000000-0000-4000-8000-000000000001@example.org#second";
    @Before public void reset()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();prefs=context.getSharedPreferences("battery_vpn",0);
        prefs.edit().clear().commit();encrypted=new File(context.getNoBackupFilesDir(),"battery_vpn/vless_profiles.enc");
        for(String suffix:new String[]{"",".bak",".new"})new File(encrypted.getPath()+suffix).delete();
        store=new BatteryVpnStore(context);
    }
    @Test public void profileSurvivesStoreRecreationAndIsEncrypted()throws Exception{
        store.addProfile(new BatteryVpnProfile("first",A));
        assertEquals(A,new BatteryVpnStore(context).getProfile().link);
        String bytes=new String(Files.readAllBytes(encrypted.toPath()),StandardCharsets.UTF_8);
        assertTrue(bytes.startsWith("enc:v1:"));assertFalse(bytes.contains(A));assertFalse(bytes.contains("first"));
        assertFalse(prefs.contains("profileLink"));assertFalse(prefs.contains("profileList"));
    }
    @Test public void duplicateImportDoesNotMultiplyProfiles()throws Exception{
        store.addProfile(new BatteryVpnProfile("first",A));store.addProfile(new BatteryVpnProfile("renamed",A));
        assertEquals(1,store.getProfiles().size());assertEquals("renamed",store.getProfile().name);
    }
    @Test public void selectedProfileSurvivesRestartAndDeleteReindexes()throws Exception{
        store.addProfile(new BatteryVpnProfile("first",A));store.addProfile(new BatteryVpnProfile("second",B));store.selectProfile(1);
        store=new BatteryVpnStore(context);assertEquals(B,store.getProfile().link);
        store.removeProfile(0);assertEquals(B,store.getProfile().link);assertEquals(0,store.getActiveProfileIndex());
        store.removeProfile(0);assertNull(store.getProfile());
    }
    @Test public void missingOnlyImportKeepsExistingChoice()throws Exception{
        store.addProfile(new BatteryVpnProfile("first",A));
        ArrayList<BatteryVpnProfile> imports=new ArrayList<>();imports.add(new BatteryVpnProfile("first",A));imports.add(new BatteryVpnProfile("second",B));
        store.addProfilesIfMissing(imports);assertEquals(2,store.getProfiles().size());assertEquals(A,store.getProfile().link);
    }
    @Test public void legacyPlaintextProfileMigratesAndIsRemoved()throws Exception{
        prefs.edit().putString("profileName","legacy").putString("profileLink",A).commit();
        assertEquals(A,store.getProfile().link);assertTrue(encrypted.isFile());assertFalse(prefs.contains("profileLink"));
        assertEquals(A,new BatteryVpnStore(context).getProfile().link);
    }
    @Test public void legacyListMigratesToAtomicEncryptedFile()throws Exception{
        prefs.edit().putString("profileList","[{\"name\":\"legacy\",\"link\":\""+A+"\"}]").commit();
        assertEquals(A,store.getProfile().link);assertFalse(prefs.contains("profileList"));assertTrue(encrypted.isFile());
    }
    @Test public void credentialsRemainStableAndEncrypted()throws Exception{
        String[] a=store.ensureLocalProxyCredentials(),b=new BatteryVpnStore(context).ensureLocalProxyCredentials();
        assertArrayEquals(a,b);assertFalse(a[0].isEmpty());assertFalse(a[1].isEmpty());
        assertNotEquals(a[0],prefs.getString("localProxyUser",null));assertNotEquals(a[1],prefs.getString("localProxyPassword",null));
    }
    @Test public void invalidSelectionDoesNotChangeCurrentProfile()throws Exception{
        store.addProfile(new BatteryVpnProfile("first",A));store.selectProfile(-1);store.selectProfile(100);
        assertEquals(A,store.getProfile().link);store.removeProfile(100);assertEquals(A,store.getProfile().link);
    }
    @Test public void modeAndStatusRoundTripWithoutProfiles()throws Exception{
        store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);store.setConnected(true);store.setStatus("ready");
        store=new BatteryVpnStore(context);assertTrue(BatteryVpnStore.isLocalProxyModeEnabled(context));assertTrue(store.isConnected());assertEquals("ready",store.getStatus());
        store.setMode("bad");assertEquals(BatteryVpnStore.MODE_OFF,store.getMode());store.setLocalProxyPort(-1);assertEquals(0,store.getLocalProxyPort());
    }
}
