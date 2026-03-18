package org.telegram.messenger;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Stub PasskeysController — Play Services Credentials is removed in FOSS builds.
 * {@link BuildVars#SUPPORTS_PASSKEYS} is {@code false}, so callers never invoke these methods.
 */
@RequiresApi(api = Build.VERSION_CODES.P)
public class PasskeysController {

    public static void create(Context context, int currentAccount,
            Utilities.Callback2<TL_account.Passkey, String> done) {
        // SUPPORTS_PASSKEYS = false, never called
    }

    public static Runnable login(Context context, int currentAccount, boolean clickedButton,
            Utilities.Callback3<Long, TLRPC.auth_Authorization, String> done) {
        // SUPPORTS_PASSKEYS = false, never called
        return null;
    }
}
