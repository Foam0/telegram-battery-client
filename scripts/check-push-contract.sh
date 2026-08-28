#!/bin/sh
set -eu

fail() {
    printf '%s\n' "push contract check failed: $*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "missing $1"
}

require_text() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

reject_text() {
    if grep -Fq "$2" "$1"; then
        fail "$1 unexpectedly contains: $2"
    fi
}

require_file TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java
require_file TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java
require_file TMessagesProj/src/main/java/org/telegram/messenger/FcmPushListenerService.java
require_file TMessagesProj/src/main/res/values/battery_firebase.xml
require_file TMessagesProj_App/src/hardened/res/values/battery_firebase.xml

require_text TMessagesProj/build.gradle \
    "com.google.firebase:firebase-messaging:25.1.2"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "org.telegram.messenger.FcmPushListenerService"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "com.google.firebase.MESSAGING_EVENT"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "firebase_messaging_auto_init_enabled"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "firebase_analytics_collection_enabled"
# The hardened APK uses it.belloworld.mercurygram.beta.  The generic Telegram
# resources compile, but Firebase Installations rejects them at runtime with
# FIS_AUTH_ERROR because their app id belongs to org.telegram.messenger.  Keep
# the Mercurygram Firebase app in the hardened source-set overlay.
require_text TMessagesProj_App/src/hardened/res/values/battery_firebase.xml \
    '<string name="google_app_id" translatable="false">1:136449433263:android:c0a5fae26b3e384915be5a</string>'
require_text TMessagesProj_App/src/hardened/res/values/battery_firebase.xml \
    '<string name="gcm_defaultSenderId" translatable="false">136449433263</string>'
require_text TMessagesProj_App/src/hardened/res/values/battery_firebase.xml \
    '<string name="project_id" translatable="false">telegram-514ca</string>'
require_text TMessagesProj_App/src/hardened/res/values/battery_firebase.xml \
    '<string name="google_api_key" translatable="false">'
reject_text TMessagesProj_App/src/hardened/res/values/battery_firebase.xml \
    'tmessages2'
require_text TMessagesProj_App/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java \
    "return BatteryPushProvider.INSTANCE;"
require_text TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java \
    "FcmPushProvider.INSTANCE.hasServices()"
require_text TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java \
    "UnifiedPushListenerServiceProvider.INSTANCE"
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    "return !hasUnifiedPushServices();"
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    'editor.putBoolean("pushService", false);'
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    'editor.putBoolean("pushConnection", false);'
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    'messaging.deleteToken().addOnCompleteListener'
reject_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    'editor.putBoolean("pushService", true);'
reject_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    'editor.putBoolean("pushConnection", true);'
require_text TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java \
    "FcmPushProvider.onPreferenceChanged(SharedConfig.enableFirebasePush);"
require_text TMessagesProj/src/main/java/org/telegram/messenger/PushListenerController.java \
    'boolean tokenChanged = pushType != SharedConfig.pushType'
require_text TMessagesProj/src/main/java/org/telegram/messenger/PushListenerController.java \
    '(tokenChanged || !userConfig.registeredForPush)'
require_text TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java \
    'getConnectionsManager().sendRequest(req, (response, error) -> {'

printf '%s\n' "push_contract=ok"
