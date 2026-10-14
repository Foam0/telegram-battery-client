package it.belloworld.mercurygram.vpn;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=30)
public class ProxyLifecycleRegressionTest {
    private Context context;
    @Before public void setUp(){context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;}
    @Test public void startOnlyWithLocalModeProfileAndNoRunningCore(){
        for(String mode:new String[]{BatteryVpnStore.MODE_OFF,BatteryVpnStore.MODE_SYSTEM,BatteryVpnStore.MODE_EMBEDDED,BatteryVpnStore.MODE_LOCAL_PROXY}){
            for(boolean profile:new boolean[]{false,true}){
                for(boolean appRunning:new boolean[]{false,true}){
                    for(boolean vpnRunning:new boolean[]{false,true}){
                        try(MockedConstruction<BatteryVpnStore> stores=mockConstruction(BatteryVpnStore.class,(store,init)->{
                                when(store.getMode()).thenReturn(mode);
                                when(store.getProfile()).thenReturn(profile?new BatteryVpnProfile("test","vless://test@example.com"):null);
                            });
                            MockedStatic<BatteryAppVlessProxy> app=mockStatic(BatteryAppVlessProxy.class);
                            MockedStatic<BatteryVpnService> vpn=mockStatic(BatteryVpnService.class)){
                            app.when(BatteryAppVlessProxy::isCoreRunning).thenReturn(appRunning);
                            vpn.when(BatteryVpnService::isCoreRunning).thenReturn(vpnRunning);
                            BatteryProxyLifecycle.startLocalProxyIfNeeded(context);
                            boolean expected=mode.equals(BatteryVpnStore.MODE_LOCAL_PROXY)&&profile&&!appRunning&&!vpnRunning;
                            app.verify(()->BatteryAppVlessProxy.start(context),times(expected?1:0));
                        }
                    }
                }
            }
        }
    }
    @Test public void backgroundStopsOnlyAnActiveAppCore(){
        for(boolean running:new boolean[]{false,true}){
            try(MockedStatic<BatteryAppVlessProxy> app=mockStatic(BatteryAppVlessProxy.class)){
                app.when(BatteryAppVlessProxy::isCoreRunning).thenReturn(running);
                BatteryProxyLifecycle.stopLocalProxyIfRunning(context);
                app.verify(()->BatteryAppVlessProxy.stopForBackground(context),times(running?1:0));
            }
        }
    }
    @Test public void syntheticSocksProxyPreservesAuthentication(){
        SharedConfig.ProxyInfo proxy=SharedConfig.publishMgInternalLocalProxy(1099,"alice","password");
        assertTrue(proxy.mgInternal);assertEquals("127.0.0.1",proxy.settings.getAddress());
        assertEquals(1099,proxy.settings.getPort());assertEquals("alice",proxy.settings.getUser());assertEquals("password",proxy.settings.getPassword());
    }
}
