package it.belloworld.mercurygram.ui;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.MgMessageHistory;

/**
 * Mercurygram — per-dialog viewer for saved deleted / pre-edit messages.
 * Opened from the message context menu when
 * {@link org.telegram.messenger.SharedConfig#savedMessagesHistory} is on.
 */
public class MgMessageHistoryActivity extends UniversalFragment {

    private final long dialogId;
    private final ArrayList<Row> rows = new ArrayList<>();
    private boolean loaded;

    private static class Row {
        final String preview;
        final String meta;
        final CharSequence detail;
        final String title;

        Row(String preview, String meta, CharSequence detail, String title) {
            this.preview = preview;
            this.meta = meta;
            this.detail = detail;
            this.title = title;
        }
    }

    public MgMessageHistoryActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramDeletedEditedMessagesTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        loadEntries();
        return super.onFragmentCreate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!loaded) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)));
            return;
        }
        if (rows.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramSavedMessagesEmpty)));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            items.add(UItem.asButton(i, r.preview, r.meta));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= rows.size() || getParentActivity() == null) {
            return;
        }
        Row r = rows.get(item.id);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(r.title)
                .setMessage(r.detail)
                .setPositiveButton(LocaleController.getString(R.string.Copy), (d, w) ->
                        AndroidUtilities.addToClipboard(r.detail))
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void loadEntries() {
        final int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            List<MgMessageHistory.Entry> entries =
                    MgMessageHistory.getInstance().getEntries(account, dialogId);
            ArrayList<Row> built = new ArrayList<>(entries.size());
            for (MgMessageHistory.Entry e : entries) {
                String tag = tagOf(e);
                String sender = senderOf(account, e.message);
                String when = LocaleController.getInstance().getFormatterStats().format(e.whenMs);
                String text = textOf(e.message);
                String meta = "[" + tag + "] " + when + (sender.isEmpty() ? "" : " · " + sender);
                String detail = (sender.isEmpty() ? "" : sender + "\n") + when + "\n\n" + text;
                built.add(new Row(text, meta, detail, tag));
            }
            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(built);
                loaded = true;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    private static String tagOf(MgMessageHistory.Entry e) {
        return LocaleController.getString(e.kind == MgMessageHistory.KIND_DELETED
                ? R.string.MercurygramSavedMessagesDeleted
                : R.string.MercurygramSavedMessagesEdited);
    }

    private String senderOf(int account, TLRPC.Message m) {
        TLRPC.Peer peer = m.from_id != null ? m.from_id : m.peer_id;
        if (peer == null) {
            return "";
        }
        String name = DialogObject.getName(account, MessageObject.getPeerId(peer));
        return name != null ? name : "";
    }

    private String textOf(TLRPC.Message m) {
        if (m == null) {
            return "";
        }
        if (m.message != null && !m.message.isEmpty()) {
            return m.message;
        }
        return mediaLabel(m.media);
    }

    private String mediaLabel(TLRPC.MessageMedia media) {
        if (media == null) {
            return LocaleController.getString(R.string.MercurygramSavedMessagesEmpty);
        }
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return LocaleController.getString(R.string.AttachPhoto);
        }
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive
                || media instanceof TLRPC.TL_messageMediaVenue) {
            return LocaleController.getString(R.string.AttachLocation);
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            return LocaleController.getString(R.string.AttachContact);
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            return LocaleController.getString(R.string.Poll);
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document doc = media.document;
            if (doc != null) {
                if (MessageObject.isVideoDocument(doc)) {
                    return LocaleController.getString(R.string.AttachVideo);
                }
                if (MessageObject.isVoiceDocument(doc)) {
                    return LocaleController.getString(R.string.AttachAudio);
                }
                if (MessageObject.isMusicDocument(doc)) {
                    return LocaleController.getString(R.string.AttachMusic);
                }
                if (MessageObject.isStickerDocument(doc)) {
                    return LocaleController.getString(R.string.AttachSticker);
                }
                if (MessageObject.isGifDocument(doc)) {
                    return LocaleController.getString(R.string.AttachGif);
                }
            }
            return LocaleController.getString(R.string.AttachDocument);
        }
        return LocaleController.getString(R.string.MercurygramSavedMessagesEmpty);
    }
}
