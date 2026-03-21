package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.unifiedpush.android.connector.MessagingReceiver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import it.belloworld.mercurygram.WebPushDecryptor;

public class UnifiedPushReceiver extends MessagingReceiver {

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;
    private static long numDecryptSuccess = 0;
    private static long numDecryptFailed = 0;

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    public static long getNumDecryptSuccess() {
        return numDecryptSuccess;
    }

    public static long getNumDecryptFailed() {
        return numDecryptFailed;
    }

    @Override
    public void onNewEndpoint(Context context, String endpoint, String instance) {
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            // Ensure WebPush ECDH keys exist before registering
            SharedConfig.ensureWebPushKeys();

            // All distributors route through the /aesgcm gateway which serializes
            // WebPush headers into the body (common-proxies compatible format)
            String gateway = SharedConfig.unifiedPushGateway;
            if (!gateway.endsWith("/")) gateway += "/";

            try {
                String gatewayUrl = gateway + "aesgcm?e=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8.name());

                // WebPush JSON token: endpoint + client keys for Telegram to encrypt payloads
                String p256dh = android.util.Base64.encodeToString(SharedConfig.webPushPublicKey,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                String auth = android.util.Base64.encodeToString(SharedConfig.webPushAuthSecret,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);

                String token = "{\"endpoint\":\"" + gatewayUrl + "\",\"keys\":{\"p256dh\":\"" + p256dh + "\",\"auth\":\"" + auth + "\"}}";
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, token);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    @Override
    public void onMessage(Context context, byte[] message, String instance) {
        final long receiveTime = SystemClock.elapsedRealtime();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        lastReceivedNotification = SystemClock.elapsedRealtime();
        numOfReceivedNotifications++;

        // Try WebPush decryption first
        if (SharedConfig.webPushPrivateKey != null && SharedConfig.webPushPublicKey != null && SharedConfig.webPushAuthSecret != null) {
            try {
                byte[] plaintext = WebPushDecryptor.decrypt(
                        message,
                        SharedConfig.webPushPrivateKey,
                        SharedConfig.webPushPublicKey,
                        SharedConfig.webPushAuthSecret
                );
                // Plaintext is the MTProto-encrypted push payload — base64url-encode it as FCM's "p" field
                String encoded = android.util.Base64.encodeToString(plaintext,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                numDecryptSuccess++;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP START PROCESSING (decrypted)");
                }
                PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_WEB, encoded, receiveTime);
                return;
            } catch (Exception e) {
                numDecryptFailed++;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP DECRYPT ERROR, falling back to wake-up: " + e.getMessage());
                }
                // Fall through to wake-up behavior
            }
        }

        // Fallback: wake up the app to fetch updates via MTProto (original behavior)
        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP PRE INIT APP");
            }
            ApplicationLoader.postInitApplication();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP POST INIT APP");
            }
            Utilities.stageQueue.postRunnable(() -> {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("UP START PROCESSING (wake-up fallback)");
                }
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        ConnectionsManager.onInternalPushReceived(a);
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    }
                }
                countDownLatch.countDown();
            });
        });
        Utilities.globalQueue.postRunnable(() -> {
            try {
                countDownLatch.await();
            } catch (Throwable ignore) {}
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("finished UP service, time = " + (SystemClock.elapsedRealtime() - receiveTime));
            }
        });
    }

    @Override
    public void onRegistrationFailed(Context context, String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Failed to get endpoint");
        }
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
        });
    }

    @Override
    public void onUnregistered(Context context, String instance) {
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
        });
    }
}
