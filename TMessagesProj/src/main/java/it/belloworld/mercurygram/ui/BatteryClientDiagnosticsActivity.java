package it.belloworld.mercurygram.ui;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.BatteryClientDiagnostics;
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
                BatteryVpnService.getLastState()));
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
}
