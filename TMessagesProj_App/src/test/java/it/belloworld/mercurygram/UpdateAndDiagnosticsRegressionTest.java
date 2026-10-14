package it.belloworld.mercurygram;

import static org.junit.Assert.*;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.ConnectionsManager;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=30)
public class UpdateAndDiagnosticsRegressionTest {
    @Test public void hotfixIsAnUpgradeInTheExistingChannel(){
        assertFalse(MgUpdateChecker.versionUpToDate("12.10.6.1","12.10.6.2"));
        assertTrue(MgUpdateChecker.versionUpToDate("12.10.6.2","12.10.6.2"));
        assertTrue(MgUpdateChecker.versionUpToDate("12.10.6.2","12.10.6.1"));
        assertFalse(MgUpdateChecker.versionUpToDate("12.9.0.99","12.10.6.2"));
    }
    @Test public void malformedReleaseNeverOffersAnUpdate(){
        for(String tag:new String[]{null,"","v12.10.6.2","12.10","12.x.6.2","12.10.6.2.3.4"}){
            assertTrue(MgUpdateChecker.versionUpToDate("12.10.6.1",tag));
        }
    }
    @Test public void accountThirtyOneHasIndependentDiagnostics(){
        BatteryClientDiagnostics.markUpdate(31);BatteryClientDiagnostics.markConnectionState(31,ConnectionsManager.ConnectionStateConnected);
        assertTrue(BatteryClientDiagnostics.getLastUpdateMs(31)>0);
        assertEquals(ConnectionsManager.ConnectionStateConnected,BatteryClientDiagnostics.getLastConnectionState(31));
        assertEquals(0,BatteryClientDiagnostics.getLastUpdateMs(0));
        BatteryClientDiagnostics.markUpdate(-1);BatteryClientDiagnostics.markUpdate(32);
        assertEquals(0,BatteryClientDiagnostics.getLastUpdateMs(-1));assertEquals(0,BatteryClientDiagnostics.getLastUpdateMs(32));
    }
}
