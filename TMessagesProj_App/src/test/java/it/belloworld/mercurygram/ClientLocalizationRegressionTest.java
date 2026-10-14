package it.belloworld.mercurygram;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.R;
import org.telegram.utils.localization.Localization;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=30)
public class ClientLocalizationRegressionTest {
    @Test public void russianLabelsResolveThroughRuntimeIdBindings(){
        Context c=RuntimeEnvironment.getApplication();
        Localization l=new Localization.Builder().addResLocalization(c,"localization_en.bin").addResLocalization(c,"localization_ru.bin").build();
        assertEquals("Мои функции",l.getByResId(c,R.string.BatteryClientSection));
        assertEquals("Уведомления для этого аккаунта",l.getByResId(c,R.string.BatteryClientAccountNotificationsEnabled));
        assertEquals("Предпочитать Firebase push",l.getByResId(c,R.string.BatteryClientFirebasePush));
        assertEquals("Нагрузка",l.getByResId(c,R.string.BatteryClientLoadScreen));
        assertEquals("Диагностика",l.getByResId(c,R.string.BatteryClientDiagnosticsScreen));
        assertEquals("VPN / прокси",l.getByResId(c,R.string.BatteryClientVpnScreen));
    }
    @Test public void englishDefaultsResolveWithoutATranslationPack(){
        Context c=RuntimeEnvironment.getApplication();
        Localization l=new Localization.Builder().addResLocalization(c,"localization_en.bin").build();
        assertEquals("Battery client",l.getByResId(c,R.string.BatteryClientSection));
        assertEquals("Call",l.getByResId(c,R.string.PhoneCallAction));
        assertEquals("Full MP4 metadata (ExifTool-style)",l.getByResId(c,R.string.MessageDetailsFullMp4Metadata));
    }
}
