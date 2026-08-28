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

require_file TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java
require_file TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java
require_file TMessagesProj/src/main/java/org/telegram/messenger/FcmPushListenerService.java
require_file TMessagesProj/src/main/res/values/battery_firebase.xml

require_text TMessagesProj/build.gradle \
    "com.google.firebase:firebase-messaging:22.0.0"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "org.telegram.messenger.FcmPushListenerService"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "com.google.firebase.MESSAGING_EVENT"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "firebase_messaging_auto_init_enabled"
require_text TMessagesProj/src/main/AndroidManifest.xml \
    "firebase_analytics_collection_enabled"
require_text TMessagesProj/src/main/res/values/battery_firebase.xml \
    '<string name="gcm_defaultSenderId" translatable="false">760348033671</string>'
require_text TMessagesProj_App/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java \
    "return BatteryPushProvider.INSTANCE;"
require_text TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java \
    "FcmPushProvider.INSTANCE.hasServices()"
require_text TMessagesProj/src/main/java/org/telegram/messenger/BatteryPushProvider.java \
    "UnifiedPushListenerServiceProvider.INSTANCE"
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    "return !hasUnifiedPushServices();"
require_text TMessagesProj/src/main/java/org/telegram/messenger/FcmPushProvider.java \
    "enableBackgroundNotificationFallback();"
require_text TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java \
    "FcmPushProvider.onPreferenceChanged(SharedConfig.enableFirebasePush);"

printf '%s\n' "push_contract=ok"
