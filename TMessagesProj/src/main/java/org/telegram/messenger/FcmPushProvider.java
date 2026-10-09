package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;
import android.content.SharedPreferences;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;
import com.google.firebase.messaging.FirebaseMessaging;

import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;

public final class FcmPushProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final FcmPushProvider INSTANCE = new FcmPushProvider();
    private static final String PREFS = "battery_fcm";
    private static final String KEY_DISABLED_UNTIL = "disabledUntil";
    private static final String KEY_BACKGROUND_FALLBACK_REMOVED = "backgroundFallbackRemovedV1";
    private static final String KEY_SDK_25_TOKEN_REFRESHED = "sdk25TokenRefreshedV1";
    private static final long FAILURE_BACKOFF_MS = 24L * 60L * 60L * 1000L;

    private FcmPushProvider() {
    }

    @Override
    public boolean hasServices() {
        if (!shouldUseFirebaseAsPrimary()) {
            return false;
        }
        try {
            if (isFailureBackoffActive()) {
                return false;
            }
            boolean googlePlayServicesAvailable = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext) == ConnectionResult.SUCCESS;
            return googlePlayServicesAvailable;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String getLogTitle() {
        return "Firebase Cloud Messaging";
    }

    @Override
    public void onRequestPushToken() {
        if (!shouldUseFirebaseAsPrimary()) {
            fallbackToUnifiedPush(false);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING__";
                SharedConfig.saveConfig();
                FirebaseMessaging messaging = FirebaseMessaging.getInstance();
                messaging.setAutoInitEnabled(true);
                if (shouldRefreshSdk25Token()) {
                    // 12.10.0.2/12.10.0.3 could persist an unusable token while
                    // switching Firebase app configuration. SDK 25 also fixes
                    // FID_ALREADY_USED. Revoke the legacy token once and only
                    // mark the migration complete after a new token is issued.
                    messaging.deleteToken().addOnCompleteListener(deleteTask -> {
                        if (!deleteTask.isSuccessful() && BuildVars.LOGS_ENABLED) {
                            FileLog.d("FCM legacy token deletion failed; retrying without destructive fallback");
                        }
                        requestToken(messaging, deleteTask.isSuccessful());
                    });
                } else {
                    requestToken(messaging, false);
                }
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("FCM token request failed");
                }
                fallbackToUnifiedPush(true);
            }
        });
    }

    private static void requestToken(FirebaseMessaging messaging, boolean refreshedForSdk25) {
        messaging.getToken().addOnCompleteListener(task -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            if (task.isSuccessful() && !TextUtils.isEmpty(task.getResult())) {
                clearFailureBackoff();
                if (refreshedForSdk25) {
                    markSdk25TokenRefreshed();
                }
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
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    public static void onPreferenceChanged(boolean enabled) {
        disableLegacyBackgroundFallback();
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
            SharedConfig.pushStringStatus = backoffActive
                    ? "__FIREBASE_BACKOFF__"
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
            SharedConfig.pushStringStatus = "__FIREBASE_FAILED_NO_DISTRIBUTOR__";
            SharedConfig.saveConfig();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, null);
        }
    }

    /**
     * 12.10.0.2/12.10.0.3 could silently persist the foreground keep-alive
     * service after a transient FCM failure.  Remove that one-time fallback on
     * upgrade.  The two settings remain available as explicit user opt-ins,
     * but push-provider failures must never turn them on again.
     */
    private static void disableLegacyBackgroundFallback() {
        try {
            SharedPreferences migrationPreferences = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            if (migrationPreferences.getBoolean(KEY_BACKGROUND_FALLBACK_REMOVED, false)) {
                return;
            }
            SharedPreferences.Editor editor = MessagesController.getGlobalNotificationsSettings().edit();
            editor.putBoolean("pushService", false);
            editor.putBoolean("pushConnection", false);
            editor.apply();
            ApplicationLoader.applicationContext.stopService(
                    new android.content.Intent(ApplicationLoader.applicationContext, NotificationsService.class));
            migrationPreferences.edit().putBoolean(KEY_BACKGROUND_FALLBACK_REMOVED, true).apply();
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

    private static boolean shouldRefreshSdk25Token() {
        try {
            return !ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_SDK_25_TOKEN_REFRESHED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markSdk25TokenRefreshed() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_SDK_25_TOKEN_REFRESHED, true)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
