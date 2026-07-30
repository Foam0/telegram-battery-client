package it.belloworld.mercurygram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.BatteryClientDiagnostics;
import it.belloworld.mercurygram.vpn.BatteryAppVlessProxy;
import it.belloworld.mercurygram.vpn.BatteryProxyService;
import it.belloworld.mercurygram.vpn.BatteryVpnService;

public class BatteryClientDiagnosticsActivity extends UniversalFragment {
    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.BatteryClientDiagnosticsScreen);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.BatteryClientDiagnosticsScreen)));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientActiveAccounts),
                Integer.toString(UserConfig.getActivatedAccountsCount())));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientVpnStatus),
                connectionModeLabel()));
        addPushDiagnostics(items);
        items.add(UItem.asShadow(null));

        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) {
                continue;
            }
            TLRPC.User user = config.getCurrentUser();
            String name = user != null ? UserObject.getUserName(user) : "Account " + a;
            items.add(UItem.asHeader(name));
            int state = BatteryClientDiagnostics.getLastConnectionState(a);
            items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientConnectionStatus),
                    BatteryClientDiagnostics.connectionStateLabel(state)));
            items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientLastUpdate),
                    timeLabel(BatteryClientDiagnostics.getLastUpdateMs(a))));
            items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientPushMode),
                    SharedConfig.disableUnifiedPush ? "fallback" : "UnifiedPush / FCM-first"));
            items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientDatabaseSize),
                    AndroidUtilities.formatFileSize(MessagesStorage.getInstance(a).getDatabaseSize())));
            items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientAccountNotificationsEnabled),
                    config.batteryAccountNotificationsEnabled ? LocaleController.getString(R.string.PopupEnabled) : LocaleController.getString(R.string.PopupDisabled)));
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private String timeLabel(long ms) {
        if (ms <= 0) {
            return LocaleController.getString(R.string.BatteryClientNoData);
        }
        return LocaleController.formatDateTime(ms / 1000, true);
    }

    private String connectionModeLabel() {
        if (BatteryAppVlessProxy.isCoreRunning()) {
            int port = BatteryAppVlessProxy.getLocalPort();
            return LocaleController.getString(R.string.BatteryClientProxyConnected)
                    + (port > 0 ? " 127.0.0.1:" + port : "");
        }
        if (BatteryProxyService.isCoreRunning()) {
            int port = BatteryProxyService.getLocalPort();
            return LocaleController.getString(R.string.BatteryClientProxyConnected)
                    + (port > 0 ? " 127.0.0.1:" + port : "");
        }
        if (BatteryVpnService.isCoreRunning()) {
            return LocaleController.getString(R.string.BatteryClientVpnConnected);
        }
        return LocaleController.getString(R.string.BatteryClientVpnDisconnected);
    }

    private void addPushDiagnostics(ArrayList<UItem> items) {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientPushProvider),
                SharedConfig.disableUnifiedPush ? "fallback" : "UnifiedPush"));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientUnifiedPushDistributor),
                unifiedPushDistributorLabel()));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientUnifiedPushEndpoint),
                presentLabel(!TextUtils.isEmpty(SharedConfig.unifiedPushEndpointUrl))));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientPushToken),
                pushTokenLabel()));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientKeepAliveService),
                enabledLabel(preferences.getBoolean("pushService",
                        MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false)))));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientBackgroundConnection),
                enabledLabel(ConnectionsManager.getInstance(UserConfig.selectedAccount).isPushConnectionEnabled())));
    }

    private String unifiedPushDistributorLabel() {
        if (SharedConfig.disableUnifiedPush) {
            return LocaleController.getString(R.string.PopupDisabled);
        }
        List<String> distributors = UnifiedPush.getDistributors(ApplicationLoader.applicationContext);
        if (distributors.isEmpty()) {
            return LocaleController.getString(R.string.BatteryClientMissing);
        }
        String current = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
        return current != null ? current : distributors.get(0);
    }

    private String pushTokenLabel() {
        if (!TextUtils.isEmpty(SharedConfig.pushString)) {
            return LocaleController.getString(R.string.BatteryClientPresent);
        }
        if (!TextUtils.isEmpty(SharedConfig.pushStringStatus)) {
            return SharedConfig.pushStringStatus;
        }
        return LocaleController.getString(R.string.BatteryClientMissing);
    }

    private String presentLabel(boolean present) {
        return LocaleController.getString(present ? R.string.BatteryClientPresent : R.string.BatteryClientMissing);
    }

    private String enabledLabel(boolean enabled) {
        return LocaleController.getString(enabled ? R.string.PopupEnabled : R.string.PopupDisabled);
    }
}
