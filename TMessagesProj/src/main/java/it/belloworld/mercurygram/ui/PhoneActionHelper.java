package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.browser.Browser;

/**
 * Routes phone-number links through the same tel: ACTION_VIEW path used by
 * Telegram chat links. On devices where both dialers and banking apps register
 * this URI, Android presents its native “Open with” resolver.
 */
public final class PhoneActionHelper {

    private PhoneActionHelper() {
    }

    public static void show(Context context, String rawPhone) {
        openWith(context, rawPhone);
    }

    public static void call(Context context, String rawPhone) {
        openWith(context, rawPhone);
    }

    public static void transfer(Context context, String rawPhone) {
        openWith(context, rawPhone);
    }

    private static void openWith(Context context, String rawPhone) {
        final String phone = normalize(rawPhone);
        if (context == null || TextUtils.isEmpty(phone)) {
            return;
        }
        Browser.openUrl(context, "tel:" + phone);
    }

    private static String normalize(String rawPhone) {
        if (rawPhone == null) {
            return null;
        }
        String phone = rawPhone.trim();
        if (phone.regionMatches(true, 0, "tel:", 0, 4)) {
            phone = Uri.decode(phone.substring(4));
        }
        phone = PhoneFormat.stripExceptNumbers(phone, true);
        return TextUtils.isEmpty(phone) ? null : phone;
    }
}
