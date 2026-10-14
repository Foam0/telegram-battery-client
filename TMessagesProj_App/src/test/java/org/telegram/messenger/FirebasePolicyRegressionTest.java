package org.telegram.messenger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.robolectric.shadows.ShadowLooper;
import com.google.android.gms.common.GoogleApiAvailabilityLight;
import it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor;
import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=30,shadows=PushRegistrationRegressionTest.ImmediateQueue.class)
public class FirebasePolicyRegressionTest {
    private Context context;
    @Before public void setUp(){
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        SharedConfig.enableFirebasePush=false;SharedConfig.disableUnifiedPush=false;
        SharedConfig.mgEmbeddedFcmChosen=false;SharedConfig.pushString="existing-fcm-token";
        SharedConfig.pushType=PushListenerController.PUSH_TYPE_FIREBASE;
    }
    @Test public void firebaseIsAutomaticWithoutADistributor(){
        try(MockedStatic<MgEmbeddedFcmDistributor> distributors=mockStatic(MgEmbeddedFcmDistributor.class)){
            distributors.when(()->MgEmbeddedFcmDistributor.firstThirdPartyDistributor(context)).thenReturn(null);
            assertTrue(FcmPushProvider.shouldUseFirebaseAsPrimary());
        }
    }
    @Test public void explicitFirebasePreferenceWinsOverAvailableUnifiedPush(){
        try(MockedStatic<MgEmbeddedFcmDistributor> distributors=mockStatic(MgEmbeddedFcmDistributor.class)){
            distributors.when(()->MgEmbeddedFcmDistributor.firstThirdPartyDistributor(context)).thenReturn("test.distributor");
            assertFalse(FcmPushProvider.shouldUseFirebaseAsPrimary());
            SharedConfig.enableFirebasePush=true;
            assertTrue(FcmPushProvider.shouldUseFirebaseAsPrimary());
            SharedConfig.enableFirebasePush=false;SharedConfig.disableUnifiedPush=true;
            assertTrue(FcmPushProvider.shouldUseFirebaseAsPrimary());
        }
    }
    @Test public void preferenceSurvivesReloadWithoutRotatingToken()throws Exception{
        SharedConfig.setEnableFirebasePush(true);
        assertTrue(context.getSharedPreferences("userconfing",0).getBoolean("mg_enableFirebasePush",false));
        SharedConfig.enableFirebasePush=false;
        java.lang.reflect.Field loaded=SharedConfig.class.getDeclaredField("configLoaded");loaded.setAccessible(true);loaded.setBoolean(null,false);
        SharedPreferences prefs=context.getSharedPreferences("userconfing",0);
        prefs.edit().putString("pushString2","existing-fcm-token").putInt("pushType",2).commit();
        SharedConfig.loadConfig();
        assertTrue(SharedConfig.enableFirebasePush);assertEquals("existing-fcm-token",SharedConfig.pushString);
    }
    @Test public void backoffDoesNotEnablePersistentConnection(){
        SharedConfig.enableFirebasePush=true;
        SharedPreferences fallback=context.getSharedPreferences("Notifications",0);
        fallback.edit().putBoolean("pushService",false).putBoolean("pushConnection",false).commit();
        context.getSharedPreferences("battery_fcm",0).edit().putLong("disabledUntil",System.currentTimeMillis()+60000).commit();
        assertFalse(FcmPushProvider.INSTANCE.hasServices());
        assertFalse(fallback.getBoolean("pushService",true));assertFalse(fallback.getBoolean("pushConnection",true));
        assertEquals("existing-fcm-token",SharedConfig.pushString);
    }
    @Test public void lateUnifiedPushEndpointCannotReplaceHealthyFirebaseToken(){
        SharedConfig.enableFirebasePush=true;
        GoogleApiAvailabilityLight play=mock(GoogleApiAvailabilityLight.class);
        when(play.isGooglePlayServicesAvailable(context)).thenReturn(ConnectionResult.SUCCESS);
        try(MockedStatic<GoogleApiAvailabilityLight> api=mockStatic(GoogleApiAvailabilityLight.class)){
            api.when(GoogleApiAvailabilityLight::getInstance).thenReturn(play);
            UnifiedPushReceiver.registerEndpointUrl("https://example.com/push/test");
            assertEquals("existing-fcm-token",SharedConfig.pushString);assertEquals(2,SharedConfig.pushType);
            UnifiedPushListenerServiceProvider.ensureRegistered();
            assertEquals("existing-fcm-token",SharedConfig.pushString);assertEquals(2,SharedConfig.pushType);
        }
    }
    @Test public void revokingWebPushNeverRevokesFirebasePrimary(){
        SharedConfig.pushStringSimple="";
        UnifiedPushListenerServiceProvider.revokeServerTokens();
        assertEquals("existing-fcm-token",SharedConfig.pushString);assertEquals(2,SharedConfig.pushType);
    }
    @Test public void notificationRulesRemainAccountLocalAfterReload(){
        UserConfig a=new UserConfig(0),b=new UserConfig(31);
        a.getPreferences().edit().putBoolean("batteryAccountNotificationsEnabled",false)
            .putBoolean("batteryAccountNotificationSound",false).putBoolean("batteryAccountNotificationVibrate",true).commit();
        b.getPreferences().edit().putBoolean("batteryAccountNotificationsEnabled",true)
            .putBoolean("batteryAccountNotificationSound",true).putBoolean("batteryAccountNotificationVibrate",false).commit();
        a.loadConfig();b.loadConfig();
        assertFalse(a.batteryAccountNotificationsEnabled);assertFalse(a.batteryAccountNotificationSound);assertTrue(a.batteryAccountNotificationVibrate);
        assertTrue(b.batteryAccountNotificationsEnabled);assertTrue(b.batteryAccountNotificationSound);assertFalse(b.batteryAccountNotificationVibrate);
        assertEquals(32,UserConfig.MAX_ACCOUNT_COUNT);
    }
    @Test public void firebaseFailureUsesBackoffAndNeverTurnsOnBackgroundPolling(){
        FirebaseMessaging firebase=mock(FirebaseMessaging.class);
        try(MockedStatic<FirebaseMessaging> api=mockStatic(FirebaseMessaging.class);
            MockedStatic<PushListenerController> registration=mockStatic(PushListenerController.class);
            MockedStatic<MgEmbeddedFcmDistributor> distributors=mockStatic(MgEmbeddedFcmDistributor.class)){
            api.when(FirebaseMessaging::getInstance).thenReturn(firebase);
            FcmPushProvider.fallbackToUnifiedPush();
            assertTrue(context.getSharedPreferences("battery_fcm",0).getLong("disabledUntil",0)>System.currentTimeMillis());
            SharedPreferences fallback=MessagesController.getGlobalNotificationsSettings();
            assertFalse(fallback.getBoolean("pushService",false));assertFalse(fallback.getBoolean("pushConnection",false));
            registration.verify(()->PushListenerController.sendRegistrationToServer(2,null));
        }
    }
    @Test public void legacyBackgroundFallbackIsRemovedOnlyOnce(){
        FirebaseMessaging firebase=mock(FirebaseMessaging.class);
        try(MockedStatic<FirebaseMessaging> api=mockStatic(FirebaseMessaging.class)){
            api.when(FirebaseMessaging::getInstance).thenReturn(firebase);
            SharedPreferences fallback=MessagesController.getGlobalNotificationsSettings();
            fallback.edit().putBoolean("pushService",true).putBoolean("pushConnection",true).commit();
            FcmPushProvider.onPreferenceChanged(true);
            assertFalse(fallback.getBoolean("pushService",true));assertFalse(fallback.getBoolean("pushConnection",true));
            fallback.edit().putBoolean("pushService",true).putBoolean("pushConnection",true).commit();
            FcmPushProvider.onPreferenceChanged(true);
            assertTrue(fallback.getBoolean("pushService",false));assertTrue(fallback.getBoolean("pushConnection",false));
        }
    }
    @Test public void legacyTokenMigrationRotatesOnceThenReusesTheCachedToken(){
        SharedConfig.enableFirebasePush=true;
        FirebaseMessaging firebase=mock(FirebaseMessaging.class);
        when(firebase.deleteToken()).thenReturn(Tasks.forResult(null));
        when(firebase.getToken()).thenReturn(Tasks.forResult("new-token"));
        try(MockedStatic<FirebaseMessaging> api=mockStatic(FirebaseMessaging.class);
            MockedStatic<PushListenerController> registration=mockStatic(PushListenerController.class)){
            api.when(FirebaseMessaging::getInstance).thenReturn(firebase);
            FcmPushProvider.INSTANCE.onRequestPushToken();ShadowLooper.idleMainLooper();
            verify(firebase,times(1)).deleteToken();verify(firebase,times(1)).getToken();
            assertTrue(context.getSharedPreferences("battery_fcm",0).getBoolean("sdk25TokenRefreshedV1",false));
            FcmPushProvider.INSTANCE.onRequestPushToken();ShadowLooper.idleMainLooper();
            verify(firebase,times(1)).deleteToken();verify(firebase,times(2)).getToken();
            registration.verify(()->PushListenerController.sendRegistrationToServer(2,"new-token"),times(2));
        }
    }

}
