package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;
import android.content.SharedPreferences;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;
import com.google.firebase.messaging.FirebaseMessaging;

import org.telegram.tgnet.ConnectionsManager;

import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;

public final class FcmPushProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final FcmPushProvider INSTANCE = new FcmPushProvider();
    private static final String PREFS = "battery_fcm";
    private static final String KEY_DISABLED_UNTIL = "disabledUntil";
    private static final String KEY_CONFIG_APP_ID = "configAppId";
    private static final String KEY_AUTOMATIC_BACKGROUND_FALLBACK = "automaticBackgroundFallback";
    private static final long FAILURE_BACKOFF_MS = 24L * 60L * 60L * 1000L;

    private FcmPushProvider() {
    }

    @Override
    public boolean hasServices() {
        refreshFirebaseConfigState();
        if (!shouldUseFirebaseAsPrimary()) {
            return false;
        }
        try {
            if (isFailureBackoffActive()) {
                if (!hasUnifiedPushServices()) {
                    enableBackgroundNotificationFallback();
                }
                return false;
            }
            boolean googlePlayServicesAvailable = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext) == ConnectionResult.SUCCESS;
            if (!googlePlayServicesAvailable && !hasUnifiedPushServices()) {
                enableBackgroundNotificationFallback();
            }
            return googlePlayServicesAvailable;
        } catch (Throwable ignored) {
            if (!hasUnifiedPushServices()) {
                enableBackgroundNotificationFallback();
            }
            return false;
        }
    }

    @Override
    public String getLogTitle() {
        return "Firebase Cloud Messaging";
    }

    @Override
    public void onRequestPushToken() {
        refreshFirebaseConfigState();
        if (!shouldUseFirebaseAsPrimary()) {
            fallbackToUnifiedPush(false);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING__";
                SharedConfig.saveConfig();
                FirebaseMessaging.getInstance().setAutoInitEnabled(true);
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                    SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
                    if (task.isSuccessful() && !TextUtils.isEmpty(task.getResult())) {
                        onFirebaseTokenAvailable();
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("FCM token received");
                        }
                        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, task.getResult());
                    } else {
                        if (BuildVars.LOGS_ENABLED) {
                            Exception exception = task.getException();
                            if (exception != null) {
                                FileLog.e("FCM token request failed", exception);
                            } else {
                                FileLog.d("FCM token request failed");
                            }
                        }
                        fallbackToUnifiedPush(true);
                    }
                });
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("FCM token request failed");
                }
                fallbackToUnifiedPush(true);
            }
        });
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    public static void onPreferenceChanged(boolean enabled) {
        refreshFirebaseConfigState();
        if (enabled) {
            clearFailureBackoff();
        }
        boolean useFirebase = shouldUseFirebaseAsPrimary();
        boolean backoffActive = !enabled && isFailureBackoffActive();
        try {
            FirebaseMessaging.getInstance().setAutoInitEnabled(useFirebase && !backoffActive);
        } catch (Throwable ignored) {
        }
        if (useFirebase) {
            if (backoffActive && !hasUnifiedPushServices()) {
                enableBackgroundNotificationFallback();
            }
            SharedConfig.pushStringStatus = backoffActive
                    ? "__FIREBASE_BACKOFF_BACKGROUND_FALLBACK__"
                    : (enabled ? "__FIREBASE_ENABLED__" : "__FIREBASE_AUTO_NO_UNIFIEDPUSH__");
        } else {
            SharedConfig.pushStringStatus = "__UNIFIEDPUSH_PRIMARY__";
        }
        SharedConfig.saveConfig();
    }

    static boolean shouldUseFirebaseAsPrimary() {
        if (SharedConfig.enableFirebasePush || SharedConfig.disableUnifiedPush) {
            return true;
        }
        return !hasUnifiedPushServices();
    }

    private static boolean hasUnifiedPushServices() {
        try {
            return !SharedConfig.disableUnifiedPush
                    && UnifiedPushListenerServiceProvider.INSTANCE.hasServices();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void fallbackToUnifiedPush() {
        fallbackToUnifiedPush(true);
    }

    private static void fallbackToUnifiedPush(boolean rememberFailure) {
        if (rememberFailure) {
            rememberFailureBackoff();
        }
        try {
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
        } catch (Throwable ignored) {
        }
        if (!SharedConfig.disableUnifiedPush && UnifiedPushListenerServiceProvider.INSTANCE.hasServices()) {
            SharedConfig.pushStringStatus = "__FIREBASE_FAILED__";
            SharedConfig.saveConfig();
            UnifiedPushListenerServiceProvider.INSTANCE.onRequestPushToken();
        } else {
            SharedConfig.pushStringStatus = "__FIREBASE_FAILED_BACKGROUND_FALLBACK__";
            SharedConfig.saveConfig();
            enableBackgroundNotificationFallback();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, null);
        }
    }

    private static void enableBackgroundNotificationFallback() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, true)
                    .apply();
        } catch (Throwable ignored) {
        }
        try {
            SharedPreferences.Editor editor = MessagesController.getGlobalNotificationsSettings().edit();
            editor.putBoolean("pushService", true);
            editor.putBoolean("pushConnection", true);
            editor.apply();
        } catch (Throwable ignored) {
        }
        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
        Utilities.globalQueue.postRunnable(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    try {
                        ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    static void onFirebaseTokenAvailable() {
        clearFailureBackoff();
        disableAutomaticBackgroundNotificationFallback();
    }

    public static void onManualBackgroundFallbackChanged() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, false)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void refreshFirebaseConfigState() {
        try {
            String currentAppId = ApplicationLoader.applicationContext.getString(R.string.google_app_id);
            if (TextUtils.isEmpty(currentAppId)) {
                return;
            }
            SharedPreferences state = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            String previousAppId = state.getString(KEY_CONFIG_APP_ID, null);
            if (TextUtils.equals(currentAppId, previousAppId)) {
                return;
            }

            SharedPreferences notifications = MessagesController.getGlobalNotificationsSettings();
            boolean fallbackWasActive = notifications.getBoolean("pushService", false)
                    || notifications.getBoolean("pushConnection", false);
            boolean firebaseFailureWasRecorded = state.getLong(KEY_DISABLED_UNTIL, 0) > 0
                    || "__FIREBASE_FAILED_BACKGROUND_FALLBACK__".equals(SharedConfig.pushStringStatus)
                    || "__FIREBASE_BACKOFF_BACKGROUND_FALLBACK__".equals(SharedConfig.pushStringStatus);
            boolean automaticFallbackWasActive = fallbackWasActive
                    && (state.getBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, false)
                    || firebaseFailureWasRecorded);
            state.edit()
                    .putString(KEY_CONFIG_APP_ID, currentAppId)
                    .remove(KEY_DISABLED_UNTIL)
                    .putBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, automaticFallbackWasActive)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void disableAutomaticBackgroundNotificationFallback() {
        try {
            SharedPreferences state = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            if (!state.getBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, false)) {
                return;
            }
            state.edit().putBoolean(KEY_AUTOMATIC_BACKGROUND_FALLBACK, false).apply();

            MessagesController.getGlobalNotificationsSettings().edit()
                    .putBoolean("pushService", false)
                    .putBoolean("pushConnection", false)
                    .apply();
            AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
            Utilities.globalQueue.postRunnable(() -> {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        try {
                            ConnectionsManager.getInstance(a).setPushConnectionEnabled(false);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void rememberFailureBackoff() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_DISABLED_UNTIL, System.currentTimeMillis() + FAILURE_BACKOFF_MS)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFailureBackoffActive() {
        try {
            long disabledUntil = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getLong(KEY_DISABLED_UNTIL, 0);
            return disabledUntil > System.currentTimeMillis();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearFailureBackoff() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_DISABLED_UNTIL)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
