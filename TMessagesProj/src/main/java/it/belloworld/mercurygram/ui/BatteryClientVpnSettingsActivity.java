package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.vpn.BatteryAppVlessProxy;
import it.belloworld.mercurygram.vpn.BatteryProxyService;
import it.belloworld.mercurygram.vpn.BatteryVpnProfile;
import it.belloworld.mercurygram.vpn.BatteryVpnService;
import it.belloworld.mercurygram.vpn.BatteryVpnStore;
import it.belloworld.mercurygram.vpn.ParsedVless;
import it.belloworld.mercurygram.vpn.VlessUriParser;

public class BatteryClientVpnSettingsActivity extends UniversalFragment {
    private static final int ID_MODE = 1;
    private static final int ID_PROFILE = 2;
    private static final int ID_START = 3;
    private static final int ID_STOP = 4;
    private static final int ID_ADD_PROFILE = 5;
    private static final int ID_PROFILE_ROW = 1000;
    private static final int REQUEST_VPN = 6501;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.BatteryClientVpnScreen);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        BatteryVpnStore store = store();
        items.add(UItem.asHeader(LocaleController.getString(R.string.BatteryClientVpnScreen)));
        items.add(UItem.asButton(ID_MODE,
                LocaleController.getString(R.string.BatteryClientVpnMode),
                modeLabel(store.getMode())));
        BatteryVpnProfile profile = store.getProfile();
        items.add(UItem.asButton(ID_PROFILE,
                LocaleController.getString(R.string.BatteryClientVpnActiveProfile),
                profile != null ? profile.name : LocaleController.getString(R.string.BatteryClientVpnPasteProfile)));
        items.add(UItem.asButton(ID_ADD_PROFILE, LocaleController.getString(R.string.BatteryClientVpnAddProfile)));
        ArrayList<BatteryVpnProfile> profiles = store.getProfiles();
        if (!profiles.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.BatteryClientVpnProfiles)));
            int activeIndex = store.getActiveProfileIndex();
            for (int i = 0; i < profiles.size(); i++) {
                BatteryVpnProfile itemProfile = profiles.get(i);
                UItem profileItem = UItem.asRadio(ID_PROFILE_ROW + i,
                        itemProfile.name,
                        i == activeIndex ? LocaleController.getString(R.string.BatteryClientVpnActive) : "");
                profileItem.checked = i == activeIndex;
                profileItem.intValue = i;
                profileItem.object = itemProfile;
                items.add(profileItem);
            }
        }
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientVpnStatus),
                statusLabel()));
        if (BatteryVpnService.isCoreRunning() || BatteryAppVlessProxy.isCoreRunning() || BatteryProxyService.isCoreRunning()) {
            items.add(UItem.asButton(ID_STOP, LocaleController.getString(R.string.BatteryClientVpnStop)));
        } else if (BatteryVpnStore.MODE_LOCAL_PROXY.equals(store.getMode())) {
            items.add(UItem.asButton(ID_START, LocaleController.getString(R.string.BatteryClientProxyStart)));
        } else if (BatteryVpnStore.MODE_EMBEDDED.equals(store.getMode())) {
            items.add(UItem.asButton(ID_START, LocaleController.getString(R.string.BatteryClientVpnStart)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.BatteryClientVpnAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MODE:
                cycleMode();
                break;
            case ID_PROFILE:
                showProfileDialog(false);
                break;
            case ID_ADD_PROFILE:
                showProfileDialog(true);
                break;
            case ID_START:
                startSelectedMode();
                break;
            case ID_STOP:
                stopConnection();
                break;
            default:
                if (item.id >= ID_PROFILE_ROW) {
                    selectProfile(item.intValue);
                }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_PROFILE_ROW) {
            confirmRemoveProfile(item.intValue);
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == Activity.RESULT_OK) {
            if (BatteryVpnStore.MODE_EMBEDDED.equals(store().getMode())) {
                startVpn();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void cycleMode() {
        BatteryVpnStore store = store();
        String mode = store.getMode();
        if (BatteryVpnStore.MODE_OFF.equals(mode)) {
            store.setMode(BatteryVpnStore.MODE_SYSTEM);
        } else if (BatteryVpnStore.MODE_SYSTEM.equals(mode)) {
            store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
        } else if (BatteryVpnStore.MODE_LOCAL_PROXY.equals(mode)) {
            store.setMode(BatteryVpnStore.MODE_EMBEDDED);
        } else {
            store.setMode(BatteryVpnStore.MODE_OFF);
        }
        String newMode = store.getMode();
        if (BatteryVpnStore.MODE_OFF.equals(newMode) || BatteryVpnStore.MODE_SYSTEM.equals(newMode)) {
            stopConnection();
        } else if (BatteryVpnStore.MODE_LOCAL_PROXY.equals(newMode)) {
            stopVpn();
        } else if (BatteryVpnStore.MODE_EMBEDDED.equals(newMode)) {
            stopProxy();
        }
        refresh();
    }

    private void showProfileDialog(boolean addNew) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditText editText = new EditText(context);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(LocaleController.getString(R.string.BatteryClientVpnPasteProfile));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        BatteryVpnProfile existing = store().getProfile();
        if (!addNew && existing != null) {
            editText.setText(existing.link);
            editText.setSelection(editText.getText().length());
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        showDialog(new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.BatteryClientVpnProfile))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> saveProfile(editText.getText().toString(), addNew))
                .create());
    }

    private void saveProfile(String raw, boolean addNew) {
        Context context = getParentActivity();
        try {
            ParsedVless parsed = VlessUriParser.parse(raw);
            String name = parsed.name != null && !parsed.name.isEmpty() ? parsed.name : parsed.server;
            BatteryVpnStore store = store();
            if (addNew) {
                store.addProfile(new BatteryVpnProfile(name, raw.trim()));
            } else {
                store.saveProfile(new BatteryVpnProfile(name, raw.trim()));
            }
            String mode = store.getMode();
            if (BatteryVpnStore.MODE_OFF.equals(mode) || BatteryVpnStore.MODE_SYSTEM.equals(mode)) {
                store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
            }
            if (context != null) {
                Toast.makeText(context, LocaleController.getString(R.string.BatteryClientVpnSaved), Toast.LENGTH_SHORT).show();
            }
            refresh();
        } catch (Throwable ignored) {
            if (context != null) {
                Toast.makeText(context, LocaleController.getString(R.string.BatteryClientVpnInvalidProfile), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startSelectedMode() {
        String mode = store().getMode();
        if (BatteryVpnStore.MODE_LOCAL_PROXY.equals(mode)) {
            startProxy();
        } else if (BatteryVpnStore.MODE_EMBEDDED.equals(mode)) {
            startVpn();
        }
    }

    private void startProxy() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        BatteryVpnStore store = store();
        if (store.getProfile() == null) {
            showProfileDialog(false);
            return;
        }
        stopVpn();
        store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
        BatteryAppVlessProxy.start(context);
        refresh();
    }

    private void startVpn() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        BatteryVpnStore store = store();
        if (store.getProfile() == null) {
            showProfileDialog(false);
            return;
        }
        stopProxy();
        store.setMode(BatteryVpnStore.MODE_EMBEDDED);
        Intent prepare = VpnService.prepare(context);
        if (prepare != null) {
            startActivityForResult(prepare, REQUEST_VPN);
            Toast.makeText(context, LocaleController.getString(R.string.BatteryClientVpnNeedsConsent), Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(context, BatteryVpnService.class).setAction(BatteryVpnService.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        refresh();
    }

    private void stopConnection() {
        stopProxy();
        stopVpn();
        refresh();
    }

    private void stopProxy() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (!BatteryAppVlessProxy.isCoreRunning() && !BatteryProxyService.isServiceActive() && !BatteryProxyService.isCoreRunning()) {
            return;
        }
        BatteryAppVlessProxy.stop(context);
        if (BatteryProxyService.isServiceActive() || BatteryProxyService.isCoreRunning()) {
            Intent intent = new Intent(context, BatteryProxyService.class).setAction(BatteryProxyService.ACTION_DISCONNECT);
            context.startService(intent);
        }
    }

    private void stopVpn() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (!BatteryVpnService.isServiceActive() && !BatteryVpnService.isCoreRunning()) {
            return;
        }
        Intent intent = new Intent(context, BatteryVpnService.class).setAction(BatteryVpnService.ACTION_DISCONNECT);
        context.startService(intent);
    }

    private void selectProfile(int index) {
        BatteryVpnStore store = store();
        ArrayList<BatteryVpnProfile> profiles = store.getProfiles();
        if (index < 0 || index >= profiles.size() || index == store.getActiveProfileIndex()) {
            return;
        }
        boolean restartProxy = BatteryAppVlessProxy.isCoreRunning() || BatteryProxyService.isCoreRunning();
        boolean restartVpn = BatteryVpnService.isCoreRunning();
        stopProxy();
        stopVpn();
        store.selectProfile(index);
        if (restartProxy) {
            AndroidUtilities.runOnUIThread(this::startProxy, 500);
        } else if (restartVpn && BatteryVpnStore.MODE_EMBEDDED.equals(store.getMode())) {
            AndroidUtilities.runOnUIThread(this::startVpn, 500);
        }
        refresh();
    }

    private void confirmRemoveProfile(int index) {
        Context context = getParentActivity();
        BatteryVpnStore store = store();
        ArrayList<BatteryVpnProfile> profiles = store.getProfiles();
        if (context == null || index < 0 || index >= profiles.size()) {
            return;
        }
        BatteryVpnProfile profile = profiles.get(index);
        showDialog(new AlertDialog.Builder(context)
                .setTitle(profile.name)
                .setMessage(LocaleController.getString(R.string.BatteryClientVpnDeleteProfileConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> removeProfile(index))
                .create());
    }

    private void removeProfile(int index) {
        BatteryVpnStore store = store();
        boolean removingActive = index == store.getActiveProfileIndex();
        boolean restartProxy = removingActive && (BatteryAppVlessProxy.isCoreRunning() || BatteryProxyService.isCoreRunning());
        boolean restartVpn = removingActive && BatteryVpnService.isCoreRunning();
        if (removingActive) {
            stopProxy();
            stopVpn();
        }
        store.removeProfile(index);
        if (store.getProfile() != null) {
            if (restartProxy) {
                AndroidUtilities.runOnUIThread(this::startProxy, 500);
            } else if (restartVpn && BatteryVpnStore.MODE_EMBEDDED.equals(store.getMode())) {
                AndroidUtilities.runOnUIThread(this::startVpn, 500);
            }
        }
        refresh();
    }

    private BatteryVpnStore store() {
        Context context = getParentActivity();
        if (context == null) {
            context = org.telegram.messenger.ApplicationLoader.applicationContext;
        }
        return new BatteryVpnStore(context);
    }

    private String modeLabel(String mode) {
        if (BatteryVpnStore.MODE_SYSTEM.equals(mode)) {
            return LocaleController.getString(R.string.BatteryClientVpnModeSystem);
        }
        if (BatteryVpnStore.MODE_LOCAL_PROXY.equals(mode)) {
            return LocaleController.getString(R.string.BatteryClientVpnModeLocalProxy);
        }
        if (BatteryVpnStore.MODE_EMBEDDED.equals(mode)) {
            return LocaleController.getString(R.string.BatteryClientVpnModeEmbedded);
        }
        return LocaleController.getString(R.string.BatteryClientVpnModeOff);
    }

    private String statusLabel() {
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

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
