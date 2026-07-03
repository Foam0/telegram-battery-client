package it.belloworld.mercurygram.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

public class BatteryVpnService extends VpnService implements CommandServerHandler {
    public static final String ACTION_CONNECT = "it.belloworld.mercurygram.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "it.belloworld.mercurygram.vpn.DISCONNECT";
    public static final String ACTION_STATUS = "it.belloworld.mercurygram.vpn.STATUS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "battery-vpn-status";
    private static final int NOTIFICATION_ID = 7013;

    private static volatile boolean serviceActive;
    private static volatile boolean coreRunning;
    private static volatile String lastState = "disconnected";
    private static volatile String lastMessage = "";

    private CommandServer commandServer;
    private BatteryLibboxPlatform activePlatform;
    private boolean disconnecting;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceActive = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(intent.getAction())) {
            connect();
        } else if (ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdownCore(false);
        serviceActive = false;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdownCore(true);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onRevoke() {
        disconnect();
        super.onRevoke();
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }

    @Override
    public void serviceReload() {
    }

    @Override
    public void serviceStop() {
        disconnect();
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void writeDebugMessage(String message) {
        if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_VERSION) {
            FileLog.d("libbox debug message");
        }
    }

    private void connect() {
        if (BatteryProxyService.isServiceActive() || BatteryProxyService.isCoreRunning()) {
            try {
                startService(new Intent(this, BatteryProxyService.class).setAction(BatteryProxyService.ACTION_DISCONNECT));
            } catch (Throwable ignored) {
            }
        }
        BatteryVpnStore store = new BatteryVpnStore(this);
        BatteryVpnProfile profile = store.getProfile();
        if (profile == null) {
            publishStatus("error", "profile missing");
            stopSelf();
            return;
        }
        store.setMode(BatteryVpnStore.MODE_EMBEDDED);
        startForegroundCompat(foregroundNotification("Connecting"));
        try {
            String config = VlessConfigBuilder.build(profile);
            Libbox.checkConfig(config);

            shutdownCore(false);
            BatteryLibboxPlatform platform = new BatteryLibboxPlatform(this, profile);
            CommandServer server = Libbox.newCommandServer(this, platform);
            server.start();
            OverrideOptions overrideOptions = new OverrideOptions();
            overrideOptions.setAutoRedirect(false);
            overrideOptions.setIncludePackage(new LibboxIterators.StringListIterator(Collections.singletonList(getPackageName())));
            overrideOptions.setExcludePackage(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
            server.startOrReloadService(config, overrideOptions);
            platform.closeDetachedTunFds();

            activePlatform = platform;
            commandServer = server;
            coreRunning = true;
            store.setConnected(true);
            publishStatus("connected", profile.name);
            startForegroundCompat(foregroundNotification("Connected"));
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
            publishStatus("error", "start failed");
            shutdownCore(true);
        }
    }

    private void disconnect() {
        shutdownCore(true);
    }

    private void shutdownCore(boolean stopService) {
        if (disconnecting) {
            if (stopService) {
                stopSelf();
            }
            return;
        }
        disconnecting = true;
        CommandServer server = commandServer;
        BatteryLibboxPlatform platform = activePlatform;
        commandServer = null;
        activePlatform = null;
        coreRunning = false;
        new BatteryVpnStore(this).setConnected(false);
        try {
            if (platform != null) {
                platform.closeDetachedTunFds();
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
        }
        try {
            if (server != null) {
                server.closeService();
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
        }
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
        }
        publishStatus("disconnected", "");
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
        disconnecting = false;
        if (stopService) {
            stopSelf();
        }
    }

    private Notification foregroundNotification(String text) {
        ensureNotificationChannel();
        Intent open = new Intent(this, LaunchActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("Battery VPN")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private void publishStatus(String state, String message) {
        lastState = state != null ? state : "";
        lastMessage = message != null ? message : "";
        new BatteryVpnStore(this).setStatus(lastState);
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, lastState)
                .putExtra(EXTRA_MESSAGE, lastMessage);
        sendBroadcast(intent);
    }

    public static boolean isServiceActive() {
        return serviceActive;
    }

    public static boolean isCoreRunning() {
        return coreRunning;
    }

    public static String getLastState() {
        return lastState;
    }

    public static String getLastMessage() {
        return lastMessage;
    }
}
