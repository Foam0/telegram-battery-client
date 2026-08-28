# Как выпускать Battery Telegram Client

Этот файл — рабочая инструкция для следующих релизов форка
`Foam0/telegram-battery-client`. Релиз считается готовым только после проверки
сборки, подписи, метаданных APK и ответа GitHub API, который использует
встроенный автообновлятор.

## Неизменяемые параметры релиза

- GitHub-репозиторий обновлений: `Foam0/telegram-battery-client`.
- Android application id: `it.belloworld.mercurygram.beta`.
- Ветка публичных релизов: `main`.
- Формат тега: `X.Y.Z.N` или `X.Y.Z.N.K`, без префикса `v`.
- Имя arm64 APK:
  `BatteryTelegramClient-beta-<tag>-arm64-v8a.apk`.
- SHA-256 сертификата релизной подписи:
  `A08D7DC323DDF71EF3201944397E0D3CCE7D40847263E11F328B68BBE19229AB`.
- Релиз должен быть опубликован как обычный GitHub Release. Для beta-сборки
  допустим стабильный, а не prerelease-релиз: канал определяется именем APK.

Нельзя менять application id, сертификат, репозиторий обновлений или шаблон
имени APK без одновременной миграции автообновлятора. Иначе приложение не
сможет обновиться поверх установленной версии или не найдёт новый файл.

## Обязательный контракт доставки уведомлений

Обновление Mercurygram нельзя выпускать, если при переносе исчез хотя бы один
элемент рабочего push-пути Battery Client:

- зависимость `com.google.firebase:firebase-messaging:22.0.0`;
- `BatteryPushProvider`, `FcmPushProvider` и `FcmPushListenerService`;
- Firebase service с action `com.google.firebase.MESSAGING_EVENT` в manifest;
- `TMessagesProj_App/src/hardened/res/values/battery_firebase.xml` с Firebase
  app проекта Mercurygram (`telegram-514ca`) для package
  `it.belloworld.mercurygram.beta`;
- возврат `BatteryPushProvider.INSTANCE` из `ApplicationLoaderImpl`;
- автоматический Firebase fallback, когда внешний UnifiedPush-дистрибьютор
  недоступен;
- сохранение `mg_enableFirebasePush` при обновлении;
- UnifiedPush как fallback при ошибке Firebase.

Перед каждой сборкой обязательно выполнить:

```bash
scripts/check-push-contract.sh
```

При обновлении upstream отдельно сравнить notification/push-файлы с последним
рабочим релизом. Нельзя считать перенос успешным только потому, что проект
компилируется: удалённый provider или manifest service не мешают сборке, но
ломают фоновые уведомления.

Минимальный тест миграции выполняется именно обновлением поверх предыдущего APK:

1. На предыдущей версии оставить UnifiedPush-дистрибьютор невыбранным.
2. Убедиться, что Firebase push работает, затем обновить приложение без очистки
   данных.
3. После первого запуска новой версии проверить в диагностике provider и наличие
   push token.
   Через ADB обязательно убедиться, что Firebase вернул токен, а не только
   успешно инициализировался:

   ```bash
   adb logcat -c
   # В Mercurygram выключить и снова включить «Предпочитать Firebase push».
   adb logcat -d | rg 'FCM token received|FIS_AUTH_ERROR|FCM token request failed'
   ```

   Наличие `FIS_AUTH_ERROR` или `FCM token request failed` блокирует релиз.
4. Смахнуть приложение из recent apps — не использовать Android **Force stop**,
   потому что он штатно блокирует push до следующего ручного запуска.
5. Заблокировать экран и отправить сообщение с другого аккаунта.
6. Повторить тест с выбранным внешним UnifiedPush-дистрибьютором.

Если Firebase намеренно удаляется или заменяется, до релиза должна быть готова
и проверена миграция существующего токена/настройки плюс автоматический рабочий
fallback. Молчаливое состояние «дистрибьютор не задан» после обновления является
блокирующей релиз ошибкой.

## 1. Подготовить исходники

1. Работать в отдельной чистой ветке и отдельном worktree.
2. Подтянуть нужный стабильный тег Mercurygram и перенести только наши
   изменения Battery Client.
3. Не добавлять в публичную историю серверные credentials, keystore, пароли
   или их зашифрованные архивы. Firebase Android client config проекта
   Mercurygram публикуется намеренно: он входит в APK и не является серверным
   секретом. При этом service-account JSON и FCM server credentials публиковать
   нельзя.
4. Проверить базовую версию в `gradle.properties`:
   `APP_VERSION_NAME` и `APP_VERSION_CODE` должны соответствовать новой базе.
5. Проверить настройки автообновления:

```bash
rg -n \
  'Foam0/telegram-battery-client|BatteryTelegramClient|A08D7DC3' \
  TMessagesProj/src/main/java/it/belloworld/mercurygram/MgUpdateChecker.java \
  TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java
```

6. Перед сборкой убедиться, что рабочее дерево чистое и в коммите нет
   чувствительных данных:

```bash
git status --short
git diff --check
scripts/check-sensitive-logs.sh
scripts/check-push-contract.sh
```

## 2. Выбрать номер релиза

Для обычного выпуска к базовой версии добавляется номер сборки. Например,
для базы `12.10.0` первый релиз — `12.10.0.1`, следующий — `12.10.0.2`.

Новый тег обязан быть больше установленной версии при числовом сравнении всех
компонентов. Уже опубликованный номер повторно не использовать. Проверить теги:

```bash
git ls-remote --tags \
  https://github.com/Foam0/telegram-battery-client.git \
  'refs/tags/*'
```

## 3. Рекомендуемый выпуск через GitHub Actions

Workflow `.github/workflows/build.yml` должен находиться в `main`. В GitHub
Environment с именем `release` должны существовать секреты:

- `APP_ID`
- `APP_HASH`
- `RELEASE_KEYSTORE`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Значения секретов в команды, документацию и логи не копировать.

1. Слить проверенную ветку в публичный `main`.
2. Открыть **Actions → Build signed beta APK → Run workflow**.
3. Указать новый `release_tag`, например `12.10.0.2`.
4. Дождаться обоих jobs: `build-arm64-beta` и `publish-release`.
5. Убедиться, что появился обычный, не draft-релиз и файл с точным ожидаемым
   именем.

Workflow сам проверяет подпись, application id, versionName и отсутствие
флага debuggable. Если любая проверка не прошла, публиковать APK вручную нельзя,
пока причина не устранена.

## 4. Локальная контрольная сборка

Для локальной релизной сборки нужны восстановленные приватные конфиги и
оригинальный release keystore. Их брать только из защищённого локального
хранилища; в git их не добавлять.

```bash
./gradlew \
  -PMG_BUILD_TAG=12.10.0.2 \
  :TMessagesProj_App:assembleAfatFdArm64Hardened \
  --no-daemon
```

Исходный результат Gradle:

```text
TMessagesProj_App/build/outputs/apk/afatFdArm64/hardened/afatFdArm64.apk
```

Для релиза копия должна называться строго так:

```text
BatteryTelegramClient-beta-12.10.0.2-arm64-v8a.apk
```

После сборки проверить APK через Android build-tools:

```bash
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner"
AAPT="$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt"
APK="TMessagesProj_App/build/outputs/apk/afatFdArm64/hardened/afatFdArm64.apk"

"$APKSIGNER" verify --verbose --print-certs "$APK"
"$AAPT" dump badging "$APK" | head -5
shasum -a 256 "$APK"
```

Нужно подтвердить:

- подписи v2/v3 проходят проверку;
- certificate SHA-256 совпадает с указанным выше;
- package равен `it.belloworld.mercurygram.beta`;
- versionName равен тегу релиза;
- versionCode больше, чем у предыдущего APK;
- отсутствует `application-debuggable`.

## 5. Ручная публикация — только запасной вариант

Использовать только уже проверенный hardened APK. Сначала создать релиз, затем
загрузить файл с точным именем:

```bash
TAG=12.10.0.2
APK="/absolute/path/BatteryTelegramClient-beta-${TAG}-arm64-v8a.apk"

gh release create "$TAG" "$APK" \
  --repo Foam0/telegram-battery-client \
  --target '<проверенный commit SHA>' \
  --title "Battery Telegram Client $TAG" \
  --notes 'Signed, non-debuggable arm64 hardened Battery Client build.' \
  --latest
```

Перед выполнением команды вручную проверить абсолютный путь, SHA коммита и
тег. Не использовать `--clobber` для уже существующего релиза, пока не доказано,
что загружен неправильный файл: незаметная подмена опубликованного APK мешает
аудиту и откату.

## 6. Проверить автообновление после публикации

GitHub API должен вернуть новый релиз и ожидаемый asset:

```bash
TAG=12.10.0.2
curl -fsSL \
  https://api.github.com/repos/Foam0/telegram-battery-client/releases/latest \
  | jq -e --arg tag "$TAG" '
      .draft == false
      and .tag_name == $tag
      and any(
        .assets[];
        .name == ("BatteryTelegramClient-beta-" + $tag + "-arm64-v8a.apk")
      )
    '
```

Затем проверить доступность файла и размер ответа:

```bash
TAG=12.10.0.2
curl -fIL \
  "https://github.com/Foam0/telegram-battery-client/releases/download/${TAG}/BatteryTelegramClient-beta-${TAG}-arm64-v8a.apk"
```

Финальная проверка на телефоне с предыдущей публичной версией:

1. Открыть **Настройки → Mercurygram → Проверить обновления**.
2. Убедиться, что предложена новая версия.
3. Скачать APK встроенным обновлятором.
4. Убедиться, что Android предлагает именно обновление существующего
   приложения, а не установку нового.
5. После установки проверить запуск, сохранность аккаунтов, отправку и приём
   сообщения, загрузку медиа и повторную проверку обновлений.

Если предыдущая установленная сборка сама содержит сломанный updater, один раз
установить новый APK вручную. После этого будущие версии снова должны находиться
автоматически.

## 7. Зафиксировать результат

В заметке релиза сохранить только несекретные данные:

- тег и commit SHA;
- ссылку на GitHub Release;
- имя, размер и SHA-256 APK;
- application id, versionName и versionCode;
- SHA-256 сертификата;
- результаты сборки и smoke-теста;
- известные ограничения или шаг отката.

При проблеме релиз не удалять сразу. Сначала пометить его как не latest или
опубликовать исправленную версию с большим номером. Удаление тега и релиза
допустимо только после явного решения, потому что это ломает воспроизводимость
уже скачанного файла.

## Короткий чек-лист

- [ ] Ветка основана на нужном стабильном Mercurygram.
- [ ] Публичная история не содержит приватных файлов и секретов.
- [ ] Updater смотрит на `Foam0/telegram-battery-client`.
- [ ] `scripts/check-push-contract.sh` проходит.
- [ ] Firebase provider, config resources и manifest service присутствуют в APK.
- [ ] Hardened APK содержит Firebase project `telegram-514ca`, а не
      официальный Telegram project `tmessages2`.
- [ ] На устройстве получен `FCM token received`; в logcat нет
      `FIS_AUTH_ERROR`.
- [ ] Тег новый и численно больше предыдущего.
- [ ] Собран `afatFdArm64Hardened` с release key.
- [ ] Package, versionName, versionCode, debuggable и подпись проверены.
- [ ] Имя APK точно совпадает с шаблоном updater.
- [ ] GitHub Release не draft и содержит ожидаемый asset.
- [ ] `/releases/latest` возвращает новый релиз.
- [ ] Обновление проверено на предыдущей установленной версии.
- [ ] После обновления получено уведомление при смахнутом приложении и
      заблокированном экране.
- [ ] SHA-256 и результаты smoke-теста записаны.
