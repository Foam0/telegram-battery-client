package org.telegram.messenger;

import android.os.PowerManager;
import android.text.TextUtils;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.telegram.tgnet.ConnectionsManager;

public class FcmPushListenerService extends FirebaseMessagingService {
    private static PowerManager.WakeLock wakeLock;

    @Override
    public void onNewToken(String token) {
        if (!FcmPushProvider.shouldUseFirebaseAsPrimary() || TextUtils.isEmpty(token)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = android.os.SystemClock.elapsedRealtime();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM token refreshed");
            }
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, token);
        });
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        if (!FcmPushProvider.shouldUseFirebaseAsPrimary()) {
            return;
        }
        String payload = message.getData().get("p");
        acquireWakeLock();
        if (!TextUtils.isEmpty(payload)) {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    PushListenerController.processRemoteMessage(
                            PushListenerController.PUSH_TYPE_FIREBASE,
                            payload,
                            System.currentTimeMillis());
                } finally {
                    releaseWakeLock();
                }
            });
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                boolean scheduled = false;
                try {
                    ApplicationLoader.postInitApplication();
                    Utilities.stageQueue.postRunnable(() -> {
                        try {
                            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                if (UserConfig.getInstance(a).isClientActivated()) {
                                    ConnectionsManager.onInternalPushReceived(a);
                                    ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                                }
                            }
                        } finally {
                            releaseWakeLock();
                        }
                    });
                    scheduled = true;
                } finally {
                    if (!scheduled) {
                        releaseWakeLock();
                    }
                }
            });
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            synchronized (FcmPushListenerService.class) {
                if (wakeLock == null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mercurygram:fcm");
                    wakeLock.setReferenceCounted(true);
                }
                wakeLock.acquire(30_000);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void releaseWakeLock() {
        synchronized (FcmPushListenerService.class) {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
