package org.telegram.messenger;

import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;

public final class BatteryPushProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final BatteryPushProvider INSTANCE = new BatteryPushProvider();

    private BatteryPushProvider() {
    }

    @Override
    public boolean hasServices() {
        return FcmPushProvider.INSTANCE.hasServices() || hasUnifiedPushServices();
    }

    @Override
    public String getLogTitle() {
        if (FcmPushProvider.shouldUseFirebaseAsPrimary()) {
            return FcmPushProvider.INSTANCE.getLogTitle();
        }
        if (hasUnifiedPushServices()) {
            return UnifiedPushListenerServiceProvider.INSTANCE.getLogTitle();
        }
        return FcmPushProvider.INSTANCE.getLogTitle();
    }

    @Override
    public void onRequestPushToken() {
        if (FcmPushProvider.INSTANCE.hasServices()) {
            FcmPushProvider.INSTANCE.onRequestPushToken();
        } else if (hasUnifiedPushServices()) {
            UnifiedPushListenerServiceProvider.INSTANCE.onRequestPushToken();
        } else {
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, null);
        }
    }

    @Override
    public int getPushType() {
        if (FcmPushProvider.shouldUseFirebaseAsPrimary() || FcmPushProvider.INSTANCE.hasServices()) {
            return FcmPushProvider.INSTANCE.getPushType();
        }
        if (hasUnifiedPushServices()) {
            return UnifiedPushListenerServiceProvider.INSTANCE.getPushType();
        }
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    private static boolean hasUnifiedPushServices() {
        return !SharedConfig.disableUnifiedPush
                && UnifiedPushListenerServiceProvider.INSTANCE.hasServices();
    }
}
