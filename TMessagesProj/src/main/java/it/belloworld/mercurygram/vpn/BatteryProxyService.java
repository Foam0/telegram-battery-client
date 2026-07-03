package it.belloworld.mercurygram.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

public class BatteryProxyService extends Service implements CommandServerHandler {
    public static final String ACTION_CONNECT = "it.belloworld.mercurygram.proxy.CONNECT";
    public static final String ACTION_DISCONNECT = "it.belloworld.mercurygram.proxy.DISCONNECT";
    public static final String ACTION_STATUS = "it.belloworld.mercurygram.proxy.STATUS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "battery-proxy-status";
    private static final int NOTIFICATION_ID = 7014;

    private static volatile boolean serviceActive;
    private static volatile boolean coreRunning;
    private static volatile int localPort;
    private static volatile String lastState = "disconnected";
    private static volatile String lastMessage = "";

    private CommandServer commandServer;
    private boolean disconnecting;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceActive = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
            FileLog.d("libbox local proxy debug message");
        }
    }

    private void connect() {
        if (BatteryVpnService.isServiceActive() || BatteryVpnService.isCoreRunning()) {
            try {
                startService(new Intent(this, BatteryVpnService.class).setAction(BatteryVpnService.ACTION_DISCONNECT));
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
        store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
        startForegroundCompat(foregroundNotification("Connecting"));
        try {
            String[] credentials = store.ensureLocalProxyCredentials();
            String username = credentials[0];
            String password = credentials[1];
            int port = Libbox.availablePort(2080);
            String config = VlessConfigBuilder.buildLocalSocks(profile, port, username, password);
            Libbox.checkConfig(config);

            shutdownCore(false);
            BatteryLibboxPlatform platform = new BatteryLibboxPlatform(this, profile);
            CommandServer server = Libbox.newCommandServer(this, platform);
            server.start();
            OverrideOptions overrideOptions = new OverrideOptions();
            overrideOptions.setAutoRedirect(false);
            overrideOptions.setIncludePackage(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
            overrideOptions.setExcludePackage(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
            server.startOrReloadService(config, overrideOptions);

            commandServer = server;
            coreRunning = true;
            localPort = port;
            store.setConnected(true);
            store.setLocalProxyPort(port);
            publishTelegramProxy(port, username, password);
            publishStatus("proxy-connected", "127.0.0.1:" + port);
            startForegroundCompat(foregroundNotification("SOCKS5 127.0.0.1:" + port));
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
        boolean hadLocalProxy = coreRunning || localPort != 0;
        commandServer = null;
        coreRunning = false;
        localPort = 0;
        BatteryVpnStore store = new BatteryVpnStore(this);
        store.setConnected(false);
        store.setLocalProxyPort(0);
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
        if (hadLocalProxy) {
            restoreTelegramProxy();
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

    private void publishTelegramProxy(int port, String username, String password) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedConfig.publishMgInternalLocalProxy(port, username, password);
                ConnectionsManager.setProxySettings(true, "127.0.0.1", port, username, password, "");
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(e);
                }
            }
        });
    }

    private void restoreTelegramProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedConfig.clearMgInternalTorProxy();
                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                String address = preferences.getString("proxy_ip", "");
                String username = preferences.getString("proxy_user", "");
                String password = preferences.getString("proxy_pass", "");
                String secret = preferences.getString("proxy_secret", "");
                int port = preferences.getInt("proxy_port", 1080);
                boolean enabled = preferences.getBoolean("proxy_enabled", false) && address != null && address.length() > 0;
                if (enabled) {
                    ConnectionsManager.setProxySettings(true, address, port, username, password, secret);
                    SharedConfig.currentProxy = findProxyInList(address, port, username, password, secret);
                } else {
                    ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                    SharedConfig.currentProxy = null;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(e);
                }
            }
        });
    }

    private SharedConfig.ProxyInfo findProxyInList(String address, int port, String username, String password, String secret) {
        SharedConfig.loadProxyList();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.mgInternal) {
                continue;
            }
            if (info.port == port
                    && safeEquals(info.address, address)
                    && safeEquals(info.username, username)
                    && safeEquals(info.password, password)
                    && safeEquals(info.secret, secret)) {
                return info;
            }
        }
        return null;
    }

    private boolean safeEquals(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
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
                .setContentTitle("Battery SOCKS proxy")
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
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Proxy status", NotificationManager.IMPORTANCE_LOW));
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

    public static int getLocalPort() {
        return localPort;
    }

    public static String getLastState() {
        return lastState;
    }

    public static String getLastMessage() {
        return lastMessage;
    }
}
