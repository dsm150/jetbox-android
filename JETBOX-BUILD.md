# Сборка JetBox Android (форк netbirdio/android-client)

Форк живёт на ветке `jetbox` поверх апстрима `netbirdio/android-client`
(remote `origin`, база — тег **v0.6.2**, `ad7865a`; ветка `jetbox` на нём и стоит).
Наша работа — всё, что видно в `git log --oneline jetbox..<ветка работы>`.
Образец документа — `JetBox_iOS/jetbox-ios/JETBOX-BUILD.md`; структура и правила те же.

Лицензии: **Android-клиент апстрима — GPL-3.0** (`LICENSE` в корне форка), а значит,
и весь APK JetBox; **ядро** (`netbird`, клиентская часть) — **BSD-3** (каталоги
`management/`, `signal/`, `relay/`, `combined/` ядра — AGPL-3.0, в клиент не
входят). При **распространении** сборки (RuStore, APK со своего сайта)
обязательны: исходный код именно этой сборки в открытом доступе (адрес —
`JetBoxConfig.SOURCE_CODE_URL`, ссылка «Исходный код» в «О программе»), тексты
лицензий (GPL-3.0 и BSD-3) и NOTICE — в APK и на сайте. Сборка «на свой
телефон» распространением не является. Пока ничего не распространялось.

## 1. Окружение и ядро

### Что нужно на машине

| Что | Версия | Проверка |
|---|---|---|
| JDK | 21 (Temurin, `~/Library/Java/JavaVirtualMachines`) | `/usr/libexec/java_home -v 21` |
| Android SDK | `~/Library/Android/sdk` (compileSdk 36, minSdk 26) | `ls $ANDROID_HOME/platforms` |
| Android NDK | **23.1.7779620** — ровно эта (константа `NDK_VERSION` в `build-android-lib.sh`; CI апстрима читает ту же строку) | `ls $ANDROID_HOME/ndk` |
| Go | не ниже `go` из `netbird/go.mod`; `gomobile` в `$(go env GOPATH)/bin` | `go version; which gomobile` |
| Gradle | wrapper 8.14.3 (AGP 8.13.0) | `./gradlew --version` |
| Kotlin / Compose | Kotlin 2.3.21, Compose BOM 2026.06.01, coroutines 1.10.2 (всё в `gradle/libs.versions.toml`) | — |

Окружение одной командой (JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME, PATH):

```bash
source ../jetbox-env.sh    # <папка документов>/jetbox-env.sh — рядом с папкой форка
```

> Файл `jetbox-env.sh` — рядом с папкой форка, а не внутри него (машина-специфичен:
> Intel x86_64, без sudo). Если его там ещё нет — он приезжает с веткой документов.

### Ядро: `gomobile/netbird.aar`

Ядро (Go, репозиторий `netbirdio/netbird`) подключено сабмодулем `netbird`
(сейчас `01185ba`, `v0.80.0-rc.1-73-g01185ba35`) и собирается в AAR скриптом
апстрима `build-android-lib.sh`. Артефакт в git не хранится (`gomobile/*.aar`
в `.gitignore`) и пересобирается **при каждом сдвиге пина** `netbird` — без
этого новый Java/Kotlin-код апстрима не соберётся: он зовёт новые API `gomobile`.

```bash
git submodule update --init --recursive
./build-android-lib.sh 0.80.0+01185ba.jetbox2      # ~3 мин (из кэша Go ~40 с), 4 ABI, AAR ~60 МБ;
                                                   # патчи ядра накладывает и откатывает сам
```

Ядро собирается с `-ldflags "-s -w …"` (без таблицы символов и DWARF): `libgojni.so`
arm64 — 41 МБ вместо 58 МБ. Стектрейсы паники Go при этом сохраняются (`pclntab`
не вырезается).

Аргумент — строка, которую пир сообщает серверу управления. Правило апстрима
(скрипт его же и считает без аргумента в CI): `<последний стабильный тег ядра
на родословной коммита сабмодуля>+<sha7>`; к нему добавляем суффикс
`.jetbox<N>`. Без аргумента локальная сборка получит `dev-<sha>` — так наружу
не отдаём. «Стабильный» — `vX.Y.Z` без `-rc`; «на родословной» — тег, чьи
изменения целиком есть в коммите сабмодуля (теги NetBird стоят на релизных
ветках, поэтому самый свежий тег может не подходить). Скрипт проверяет это
сам (`git cherry` и номера PR) — при сомнениях прочитать его шапку.

| Пин ядра | Версия | Где |
|---|---|---|
| `01185ba` | `0.80.0+01185ba.jetbox1` | этап 1 «одна кнопка» (апстрим v0.6.2); этап 3 — пересобрано с `-s -w` под тем же номером (до 1.0 ничего не распространялось) |
| `01185ba` | `0.80.0+01185ba.jetbox2` | этап 3, релиз 1.0: `-s -w` + патч `0001-android-no-device-serial` |

`.jetbox<N>` растёт при каждой пересборке того же пина (смена флагов,
добавленный патч). В `-ldflags` скрипта зашит каталог сокета WireGuard
`/data/data/net.jetnet.jetbox/cache/wireguard` — он **обязан совпадать** с
`applicationId` (§4, строка 6; сторожит `BrandingResourcesTest`).

### Патчи ядра

`patches/netbird-core/*.patch` — наши правки ядра (сабмодуль в форк не копируем).
Каждый патч — `git diff` относительно пина сабмодуля (пути `a/client/…`, `b/client/…`).

| Патч | Что | Зачем | Апстрим |
|---|---|---|---|
| `0001-android-no-device-serial.patch` | `client/system/info_android.go`: `serial()` возвращает `""`, чтение `ril.serialnumber`/`ro.serialno`/`ro.boot.serialno`/`sys.serialnumber` и фолбэк `settings get secure android_id` (функция `androidId()`) удалены | Ядро отправляло серверу управления `SystemSerialNumber` — постоянный идентификатор устройства. JetBox его не собирает (политика конфиденциальности); пир и так опознаётся ключом WireGuard. Модель и производитель (`ro.product.*`) остаются | Не предлагается: апстриму поле нужно для инвентаря ПК/MDM — это наше решение о приватности |

**Как работает.** `build-android-lib.sh` после `ensure_gomobile_tools` зовёт
`apply_core_patches`: требует чистый сабмодуль (`git -C netbird diff --quiet`, иначе
ошибка со списком изменений), затем `git -C netbird apply` каждого `*.patch` по имени
(порядок — по номеру `0001-`, `0002-` …). Откат — `git -C netbird apply -R` в обратном порядке
по `trap … EXIT` (и при ошибке, и при Ctrl-C), так что после сборки сабмодуль снова чист на
пине (`git -C netbird status` пусто). Версия в `git rev-parse` сабмодуля не меняется — патч
виден только по суффиксу `.jetbox<N>`.

**Добавить патч:** правка в `netbird/` → `git -C netbird diff > patches/netbird-core/000N-<что>.patch`
→ `git -C netbird checkout -- .` (сабмодуль чист) → `.jetbox<N+1>` в строке сборки и в таблице
пинов выше → пересобрать ядро → `./gradlew :jetbox:testDebugUnitTest` (`CorePatchesTest`).
**Удалить** (апстрим принял / не нужен): удалить файл, строку из таблицы патчей, поднять
`.jetbox<N>` (или сбросить суффикс при сдвиге пина на апстрим без наших патчей).
**При сдвиге пина** `netbird`: `git -C netbird apply --check patches/netbird-core/*.patch`
(сторожит `CorePatchesTest.patchesApplyToCurrentPin`); не ложится — обновить патч по новому
коду; изменение уже в апстриме — удалить патч.

Проверка патча серийника на собранном ядре: в `libgojni.so` нет строк `ro.serialno`,
`ril.serialnumber`, `android_id` (контроль — `ro.product.model` есть):
`unzip -o gomobile/netbird.aar 'jni/arm64-v8a/*' -d /tmp/aar && strings /tmp/aar/jni/arm64-v8a/libgojni.so | grep -cE 'ro\.serialno|android_id'` → `0`.

## 2. Сборка, тесты, установка

Всё — из корня форка, после `source jetbox-env.sh`.

```bash
./gradlew :jetbox:testDebugUnitTest                       # наши тесты
./gradlew test                                            # все (наши + апстрима)
./gradlew assembleDebug -PversionCode=1 -PversionName=1.0 # debug-APK
```

* `versionCode`/`versionName` — параметрами (`-P`); `version.properties` в форке
  — значение для сборки без параметров (сейчас `11` / `1.0.1` — бандл для Google Play, §6.1.1;
  APK сайта и RuStore — пока 1.0 / `10`).
* Релизная (подписанная) сборка — `./jetbox-release.sh`, §6.1.
* После правок **ресурсов** перед `BrandingResourcesTest`/`ManifestTest` —
  `./gradlew cleanTestDebugUnitTest :jetbox:testDebugUnitTest`: тесты читают
  файлы напрямую, Gradle не считает ресурсы входами задачи и берёт результат
  из кэша.
* Firebase/Crashlytics: плагины в `app/build.gradle.kts` подключаются только
  при наличии `app/google-services.json`, которого у нас нет (и быть не должно,
  §5.3).

### Эмулятор

Эмулятор `jetbox36`. ⚠️ **adb — только с `-s emulator-5554`**: в сети видны
приставки владельца, голый `adb` может попасть на них.

```bash
emulator -avd jetbox36 &
adb -s emulator-5554 uninstall io.netbird.client          # апстримовый, если стоял
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n net.jetnet.jetbox/.LauncherActivity
```

Ссылка Кабинета — App Link, формат — `LINK-CONTRACT.md` из iOS:

```
https://box.jet-net.net/connect#k=<setup-key>&m=<management-url>&e=<exit-node>&n=<имя>
jetbox://connect#k=<setup-key>&m=<management-url>&e=<exit-node>&n=<имя>      # запасная схема
```

Ключ — во **фрагменте**: не уходит на сервер, не попадает в access-логи и
Referer. Для проверки нужна ссылка с ключом демо-стенда (выдаёт Кабинет; хранится
вне репозитория); **значение ключа
не выводить** в чат, лог, коммит и скриншот (маска `…XXXX`).

```bash
# Открыть ссылку (схема работает всегда; https — после одобрения домена):
adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d '<ссылка>'
# Одобрить домен без assetlinks.json (только для отладки; в проде — §6):
adb -s emulator-5554 shell pm set-app-links-user-selection --user 0 --package net.jetnet.jetbox true box.jet-net.net
# Тема и язык:
adb -s emulator-5554 shell cmd uimode night yes            # / no
adb -s emulator-5554 shell cmd locale set-app-locales net.jetnet.jetbox --locales en   # / ru
```

### Debug-витрина (только debug, в релиз не попадает)

Туннель к демо-стенду на эмуляторе не всегда под рукой, поэтому есть
`ShowcaseActivity` (`jetbox/src/debug/java/net/jetnet/jetbox/ShowcaseActivity.kt`) —
настоящие экраны на демо-движке:

```bash
A='adb -s emulator-5554 shell am start -n net.jetnet.jetbox/net.jetnet.jetbox.ShowcaseActivity'
$A                                              # все фазы щита и капсулы
$A --es phase ON                                # один щит: ON|OFF|WORKING|WORKING_REVERSE|OFFLINE
$A --es home ON                                 # главный экран: OFF|CONNECTING|ON|OFFLINE|NOBOX
$A --es home ON --ez update true                # то же с капсулой «Доступна новая версия» (сайт — подделка, без сети)
$A --es flow PROTECTED                          # экран по ссылке: READY|SWITCH|REGISTERING|CONNECTING|CONSENT|
                                                #   SELECTING_EXIT|CHOOSE_EXIT|PROTECTED|PROTECTED_NO_EXIT|
                                                #   FAILED_KEY|FAILED_UNREACHABLE|FAILED_BROKEN|FAILED_VPN
$A --es flow FAILED_KEY --ez details true       # то же, подробности отказа раскрыты
$A --es screen SETTINGS                         # SETTINGS|SETTINGS_ADVANCED|SETTINGS_NOBOX|ABOUT
$A --es screen SETTINGS --ez update true        # настройки со строкой «Обновить до 1.1»
```

Полный список — в KDoc класса; если расходится с этим файлом, верен код.

## 3. Наш код — только `jetbox/`

Весь код форка — модуль `:jetbox` (Kotlin + Compose + Material 3), пакет
`net.jetnet.jetbox`:

| Пакет | Что внутри |
|---|---|
| `link` | разбор ссылки Кабинета, константы контракта (`JetBoxConfig`), приём ссылки |
| `store` | хранилище текущего JetBox (без ключа), `KeyValueStore` |
| `logic` | чистая логика без Android: действие щита, фазы, выбор выхода, классификация ошибок, DNS-замер «до дома», расширенный режим |
| `engine` | мост к туннелю (`VPNService.MyLocalBinder`), сценарий подключения по ссылке, замер «до дома» |
| `ui` | экраны Compose, тема, стекло, щит, панель дома, настройки; `ui/icons` |
| `widget` | виджет рабочего стола |

Вне пакетов: `JetBoxActivity` (стартовый экран и приёмник ссылок, `singleTask`),
`LauncherActivity` (ярлык-трамплин), `JetBoxApp`, `JetBoxInitProvider`
(разовые настройки до окна), `JetBoxNotifications`. Ресурсы бренда (`jb_*`:
иконка, знак, значок уведомления, тема) — `jetbox/src/main/res/`. Тесты —
`jetbox/src/test/` (JVM, JUnit 4, org.json), в т. ч. охранные:
`BrandingResourcesTest`, `ManifestTest`, `WordingTest` (в ru-строках нет
«коробк»/«обход»/«заблокирован», в en — отдельного слова `box`),
`UpstreamContractsTest` (контракты апстрима из §4.6).

Экраны NetBird (`MainActivity` и фрагменты) **остаются в `:app`, но не
показываются**: в простом режиме клиент их не видит. Открываются только в
расширенном режиме — **семью быстрыми нажатиями по строке «Версия»** в
настройках JetBox (с четвёртого — «Ещё N нажатий…»), затем «Расширенные
настройки». Обратно — «Выключить расширенные настройки» (закрывает и режим, и
доступ). Логика — `logic/JetBoxAdvancedAccess.kt`.

Апстримовые экраны **спрятаны, а не удалены** — иначе каждое слияние тега
превращалось бы в разбор конфликтов.

## 4. Врезки в апстрим (таблица конфликтов)

Всё, что форк меняет вне `jetbox/`. Список снят по факту:
`git diff --stat jetbox..HEAD -- . ':!jetbox'` (101 файл; из них 44 удалённых
бинарника — отдельной строкой в 4.5). **Новая врезка = новая строка здесь**:
иначе при слиянии она станет сюрпризом.

«Как разрешать» — что делать, если при слиянии тега конфликт в этом файле.
Если конфликта нет, но тест после слияния красный — смотреть ту же строку.

### 4.1. Gradle и сборка

| № | Файл | Что изменено | Зачем | Как разрешать конфликт |
|---|---|---|---|---|
| 1 | `app/build.gradle.kts` | (а) `applicationId = "net.jetnet.jetbox"` (было `io.netbird.client`); `implementation(project(":jetbox"))`; (б) у `release`: `isMinifyEnabled = false` явно, `ndk { abiFilters += … }` — `arm64-v8a,armeabi-v7a`, переопределяется `-Pjetbox.abis=x86_64`; блок `packaging { jniLibs { useLegacyPackaging = <-Pjetbox.legacyPackaging, по умолчанию true> } }`; блок `bundle { language { enableSplit = false } }`; (в) после `android { }` — `gradle.taskGraph.whenReady`: если в графе `:app:packageRelease`/`packageReleaseBundle`/`signReleaseBundle`, а `NETBIRD_UPLOAD_STORE_FILE` не задан — `GradleException("ключ подписи не задан — используйте ./jetbox-release.sh")` (все — с комментарием `// JetBox:`) | (а) Свой пакет в системе и магазинах; подключение нашего модуля. (б) Релиз 1.0: в APK только ABI телефонов, `.so` сжаты (42 МБ вместо ~85; цена — §6.1), R8 выключен (§6); бандл Play — `.so` несжатые и все языки в базе (§6.1.1). (в) Без ключа апстрим молча собирал бы неподписанный релиз; debug, `./gradlew test` (`testReleaseUnitTest` упаковку не тянет) и lint ключа не требуют | Взять апстрим целиком, затем вернуть (а), (б) и (в). `namespace` не трогаем: Java-пакет остаётся `io.netbird.client`, меняется только applicationId. Если апстрим сам завёл `abiFilters`/`packaging` — оставить одно место, наши значения. Проверки — `BrandingResourcesTest.applicationIdIsJetBox`, `releaseBuildIsSlim`, `releaseScriptAndKeyGuard`, `PlayStoreVariantTest.bundleKeepsAllLanguages` |
| 2 | `settings.gradle.kts` | `include(":jetbox")` | Модуль в сборке | Обе стороны: оставить свои `include` и добавленные апстримом |
| 3 | `build.gradle.kts` | В `plugins`: `kotlin.android`, `kotlin.compose` (`apply false`) | Kotlin и Compose в `:jetbox` (апстрим — чистая Java) | Обе стороны; если апстрим сам перейдёт на Kotlin — взять его плагин и убрать наш дубль |
| 4 | `gradle/libs.versions.toml` | Версии `kotlin`, `composeBom`, `activityCompose`, `lifecycleCompose`, `coroutines`; библиотеки `compose-*`, `activity-compose`, `lifecycle-*-compose`, `coroutines-*`; плагины `kotlin-android`, `kotlin-compose` | Зависимости модуля `:jetbox` | Обе стороны; при совпадении имён — брать **бо́льшую** версию, затем `./gradlew :jetbox:testDebugUnitTest` (AGP/Kotlin/Compose должны быть совместимы) |
| 5 | `version.properties` | `versionCode=11`, `versionName=1.0.1` (было `9999` / `development`; этап 1 — `1`, выпуск 1.0 — `10`) | Своя нумерация версий; реальные значения — параметрами `-P` | Оставить наше. Какой апстрим внутри — пишем в «Что нового» и в хендовер, а не в номер версии |
| 6 | `build-android-lib.sh` | В `-ldflags`: (а) `socketDirectory=/data/data/net.jetnet.jetbox/cache/wireguard` (было `io.netbird.client`); (б) в начале `-s -w` (+ комментарий `# JetBox:`); (в) блок `CORE_PATCHES_DIR` / `revert_core_patches` / `apply_core_patches` перед `cd netbird` и вызов `apply_core_patches` перед `gomobile bind` | (а) Каталог сокета WireGuard ядра обязан лежать в данных **нашего** пакета, иначе туннель не поднимется. (б) Ядро без символов — `.so` на треть меньше. (в) Патчи ядра `patches/netbird-core/` (§1) накладываются на время сборки и откатываются | Взять апстрим, подставить `net.jetnet.jetbox`, вернуть `-s -w` и блок (в) (вызов — после `ensure_gomobile_tools`, до `gomobile bind`). Проверки — `BrandingResourcesTest.socketDirectoryFollowsPackage`, `releaseBuildIsSlim`, `CorePatchesTest.buildScriptAppliesAndRevertsPatches`. ⚠️ Если апстрим добавил новые `-ldflags` — взять их |
| 27 | `jetbox-release.sh` (новый, в корне) | Скрипт релизной сборки: проверка `ANDROID_HOME`; пароль ключа из файла → переменные `NETBIRD_UPLOAD_*` → `./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process assembleRelease`; копия в `dist/JetBox-<versionName>-<store>-<abis>.apk` (`dist/` в `.gitignore`), затем STORE/ABI, размер, отпечаток, badging | Подписанный APK одной командой, пароль не попадает ни в git, ни в `ps`, ни в вывод, ни в живущие после сборки демоны Gradle/Kotlin (§6.1) | Конфликта быть не может (файла у апстрима нет). Номер 27 — после строки 26, чтобы ссылки на номера не съехали. Проверка — `releaseScriptAndKeyGuard` |
| 28 | `patches/netbird-core/*.patch` (новый каталог) | Патчи ядра (§1, «Патчи ядра»): `0001-android-no-device-serial.patch` | Правки ядра без форка `netbird` | Конфликта при слиянии тега Android-клиента нет; при сдвиге пина ядра — `git -C netbird apply --check`, см. §1. Проверка — `CorePatchesTest` |

### 4.2. Манифест и код приложения (`app/`)

| № | Файл | Что изменено | Зачем | Как разрешать конфликт |
|---|---|---|---|---|
| 7 | `app/src/main/AndroidManifest.xml` | (а) удалено `uses-permission …AD_ID`; (б) у `MainActivity` удалён `intent-filter` MAIN/LAUNCHER/LEANBACK_LAUNCHER (оставлен комментарий); (в) у `MainActivity` `android:exported="false"` (было `true`); (г) `uses-permission CAMERA` и `uses-feature android.hardware.camera`, `.front`, `.autofocus`, `.flash` с `tools:node="remove"` (их вносит `zxing-android-embedded`); (д) удалено объявление `<permission android:name="io.netbird.permission.NOTIFICATION" android:protectionLevel="signature">` (оставлен комментарий) | (д) разрешение никто не использует (ни `uses-permission`, ни `android:permission=`, ни код), а то же имя объявляет официальный NetBird со своей подписью: при установленном NetBird установка JetBox падала бы `INSTALL_FAILED_DUPLICATE_PERMISSION`; (а) рекламный идентификатор нам не нужен и вносил бы декларацию в магазинах; (г) камера не используется — zxing только рисует QR (`QrCodeDialog`, `BarcodeEncoder`), сканера нет; APK не просит камеру; (б) ярлык и ссылки обслуживает `JetBoxActivity` (в `jetbox/src/main/AndroidManifest.xml`), экраны NetBird — только из расширенного режима; ТВ на этапе 1 не поддерживается; (в) все, кто её открывает, — внутри пакета: настройки JetBox (`openUpstreamMainActivity`, явный интент), «Продлить сессию» в уведомлении (`extendIntent()` — PendingIntent нашего пакета), `CustomTabURLOpener`; плитка ведёт в `JetBoxActivity`. Чужое приложение служебные экраны NetBird не откроет | Взять апстрим, снова убрать (а) и (б), вернуть (в). Новые разрешения/сервисы апстрима — брать. Если апстрим добавил второй `intent-filter` к `MainActivity` (например, deep link) — решить отдельно: нужен ли он нам (с фильтром для чужих приложений нужен `exported="true"`). Вернуть (г); если апстрим начнёт сканировать QR камерой — решить отдельно. Убрать (д) снова; если апстрим начал разрешение **использовать** — переименовать в `${applicationId}.permission.NOTIFICATION` везде (объявление и все ссылки), а не возвращать `io.netbird.`. Проверки — `ManifestTest` (`upstreamMainActivityIsNotLauncher`, `upstreamMainActivityIsOpenedOnlyFromInsidePackage`, `noNetBirdPermissionsDeclared`, `providerAuthoritiesAreApplicationIdBased`), `noAdIdPermission`, `cameraPermissionRemoved`; в APK — `aapt2 dump permissions` (§6.1, нет `io.netbird.`) |
| 8 | `app/src/main/java/io/netbird/client/NetbirdTileService.java` | В `onClick`, когда нет согласия VPN, открывается `net.jetnet.jetbox.JetBoxActivity` (было `MainActivity`) | Плитка без согласия VPN ведёт на наш стартовый экран, а не в служебный экран NetBird | Взять апстрим, вернуть `setClassName(getPackageName(), "net.jetnet.jetbox.JetBoxActivity")` в этом месте; остальную логику плитки — апстрима |
| 29 | `app/src/main/java/io/netbird/client/ui/troubleshoot/TroubleshootFragment.java` | В `onCreateView` вместо обработчика `buttonDebugBundle` (загрузка) — `binding.buttonDebugBundle.setVisibility(View.GONE)` с комментарием `// JetBox:`; `buttonDebugBundleFile` («сохранить в файл») — как у апстрима | Загрузка отладочного пакета уходит на `upload.debug.netbird.io` (NetBird GmbH) — журналы клиента JetNet не должны уходить третьей стороне. Сохранение в файл остаётся для поддержки JetNet | Взять апстрим, снова спрятать кнопку загрузки и не вешать на неё обработчик. Если апстрим переименовал кнопку или добавил другой путь загрузки — спрятать и его. Проверка — `ManifestTest.debugBundleUploadButtonHidden` Переключатель «Allow remote debug bundles» (`allow_remote_jobs_layout`) скрыт в `initializeRemoteJobsSwitch` — удалённые пакеты ядро грузит на upload.debug.netbird.io. |

### 4.3. Модуль `tool/` (туннель)

| № | Файл | Что изменено | Зачем | Как разрешать конфликт |
|---|---|---|---|---|
| 9 | `tool/.../EngineRunner.java` | Поле `connectedAtMillis` (момент первого `onConnected` текущего запуска ядра; сбрасывается при старте и конце запуска); наблюдатель в конструкторе, который его ставит; геттер `connectedAtMillis()`; `removeConnectionObserver(...)` | «В сети» на главном экране считается от запуска ядра, а не от процесса приложения; наш мост снимает свой наблюдатель | Обе стороны: врезки — отдельные блоки с комментарием `// JetBox:`. Если апстрим переписал жизненный цикл запуска (`start`/`finally`) — перенести три сброса `connectedAtMillis = 0` в новые места |
| 10 | `tool/.../VPNService.java` | В `MyLocalBinder`: `connectedAtMillis()`, `addConnectionObserver(...)`, `removeConnectionObserver(...)` — тонкие обёртки над `EngineRunner` | Наш мост (`engine/`) подписывается на фазу туннеля так, чтобы подписка переживала `removeConnectionStateListener` из `MainActivity.onStop` | Обе стороны (добавленные методы не пересекаются с апстримовыми). Если апстрим сам добавил наблюдателя — перейти на его API и удалить наш |
| 11 | `tool/.../ForegroundNotification.java` | (а) `setClassName(service.getPackageName(), "net.jetnet.jetbox.JetBoxActivity")` — тап по уведомлению (было литералом `io.netbird.client` + `MainActivity`); (б) `extendIntent()`: пакет из `getPackageName()`, класс по-прежнему `io.netbird.client.MainActivity` | (а) уведомление открывает наш экран; (б) «Продлить сессию» обрабатывает `MainActivity.handleSessionIntent`, а не `JetBoxActivity`; литерал пакета ломал бы работу под нашим applicationId | Взять апстрим, повторить обе правки. Проверка — `noHardcodedUpstreamPackageInClassNames` (литерал `"io.netbird.client"` первым аргументом `setClassName` запрещён) |
| 12 | `tool/.../SessionNotification.java` | `setClassName(context.getPackageName(), "net.jetnet.jetbox.JetBoxActivity")` | Уведомление об истечении сессии открывает наш экран и не зашивает чужой пакет | Как строка 11 |
| 13 | `tool/.../IFace.java` | `builder.setSession("JetBox")` перед `setMtu` | Имя VPN в системе (шторка, настройки VPN) — JetBox | Обе стороны; проверка — `vpnSessionNamed` |
| 14 | `tool/.../Preferences.java` | `defaultServer()` → `https://netbird.jet-net.net` (было `https://api.netbird.io`) | Сервер управления JetNet по умолчанию | Взять апстрим, вернуть адрес. Проверка — `defaultServerIsJetNet` |

### 4.4. Ресурсы бренда

Правило для всех ресурсов: **имена апстримовых ресурсов сохранены** (макеты
NetBird ссылаются на `nb_orange`, `nb_logo_full`, `ic_netbird_tile` и т. д.) —
заменено только содержимое. Оранжевого NetBird в ресурсах быть не должно
(`F58431`, `F68330`; §5.3).

| № | Файл(ы) | Что изменено | Зачем | Как разрешать конфликт |
|---|---|---|---|---|
| 15 | `app/src/main/res/values/colors.xml`, `values-night/colors.xml` | Палитра: `purple_*`/`teal_*` и все оранжевые (`nb_orange`, `nb_orange_15`, `thumb_on_color`, `track_on_color`, `connect_track_on`) — оттенки фирменного синего (`#1288E1` светлая, `#1A9BF5` тёмная). Имена цветов прежние | Служебные экраны расширенного режима — в цветах JetBox | Обе стороны: новые цвета апстрима брать, значения оранжевых — наши. После слияния: `git grep -niE 'f58431\|f68330' -- app/src tool/src` пусто |
| 16 | `app/src/main/res/values/themes.xml` | `colorSecondary`: `@color/track_on_color` (было литерал `#89F58431`) | Убрать оранжевый литерал из темы | Взять апстрим, заменить литерал оранжевого на ссылку на цвет |
| 17 | `app/src/main/res/drawable/btn_bg_orange.xml`, `btn_bg_transparent.xml` | Литерал `#F58431` → `@color/nb_orange` | То же: кнопки расширенного режима — синие | Как строка 16 |
| 18 | `app/src/main/res/values*/strings.xml` (10 файлов: base, de, es, fr, hu, it, ja, pt, ru, zh-rCN) | `app_name` и `quick_settings_tile_label` → `JetBox` | Имя приложения и плитки в системе | Взять апстрим, вернуть эти две строки во **всех** языках (при слиянии новых языков — тоже). Проверка — `appNameIsJetBoxEverywhere` |
| 19 | `app/src/main/res/xml/locales_config.xml` | Оставлены только `en` и `ru` | Тексты JetBox — на двух языках; системный выбор языка не предлагает остальные (в служебных экранах остаются апстримовые переводы) | Оставить наше; новые языки апстрима не добавлять, пока у JetBox нет их текстов |
| 20 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`; `values/ic_launcher_background.xml`, `values-night/ic_launcher_background.xml` (удалены) | Адаптивная иконка ссылается на `@drawable/jb_icon_background/foreground/monochrome` (слои — `jetbox/src/main/res/drawable/jb_icon_*.xml`), добавлен `<monochrome>`; цвет-подложка апстрима удалён | Иконка JetBox, тематическая иконка Android 13+ | Взять наше. Растровых иконок `mipmap-*dpi/ic_launcher*.webp` нет (строка 26): minSdk 26 — адаптивной достаточно. Проверка — `launcherIconIsJetBox` |
| 21 | `app/src/main/res/drawable/ic_netbird_tile.xml` | Векторный знак JB 24 dp, монохром (содержимое = `jetbox/…/jb_tile.xml`) | Плитка быстрых настроек и значок | Взять наше. Проверка — `tileIconIsJetBoxMark` сверяет `pathData` с `jb_tile.xml`; менять знак — только в `jetbox/`, затем копировать сюда |
| 22 | `app/src/main/res/drawable/nb_logo_full.xml` | Вместо слова NetBird — знак JB и слово «JetBox» в контурах (цвета `jb_mark_letters`/`jb_mark_trail`, светлая и тёмная) | Шапка служебных экранов (макеты NetBird зовут этот ресурс) | Взять наше |
| 23 | `app/src/main/res/drawable/ic_netbird_btn.png`, `drawable/search.png`; `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_banner.webp` | Растры **заменены** (знак JB / перекраска в синий, баннер) | Убрать оранжевую птицу из растров, на которые ссылаются экраны NetBird и баннер | Бинарный конфликт: `git checkout --ours <файл>` (наша версия). Если апстрим сменил **имя** растра — посмотреть, не оранжевый ли он, и перекрасить/удалить |
| 24 | `tool/src/main/res/values*/strings.xml` (10 файлов) | (а) `service_name` (base) и `fg_notification_channel_name` → `JetBox` (в base и во всех переводах); (б) тексты уведомлений — словами JetBox (только значения): `fg_notification_connecting` ru «Подключаем…»; `fg_notification_no_network` «Нет интернета»/«No internet» (ru, base); `fg_notification_needs_login`, `session_notification_expired_title/_text` = `jb_notification_login_required_*` («JetBox отключён», «Связь с домом прервана…»); `session_notification_expiring_title/_text` — «JetBox скоро отключится…». В de/es/fr/hu/it/ja/pt/zh-rCN (б) — en-вариант (языки JetBox — только en/ru) | Канал и имя службы в системных настройках уведомлений; в шторке нет «NetBird» и «Требуется вход» — у клиента нет входа, есть ссылка подключения | Как строка 18: взять апстрим, вернуть значения (а) и (б) во **всех** языках. Новый ключ уведомления апстрима — перевести словами JetBox и вписать в список `notificationStrings` теста. Проверки — `appNameIsJetBoxEverywhere`, `notificationTextsDoNotSayNetBird`, `notificationTextsMatchJetBoxWording` |
| 25 | `tool/src/main/res/drawable/notification_icon_{connected,connecting,disconnected,error}.xml` (новые, ×4) | Векторные значки уведомления — знак JB 24 dp (содержимое = `jetbox/…/jb_notification.xml`); растры апстрима `drawable-*dpi/notification_icon_*.png` удалены (строка 26) | Значок строки состояния — знак JetBox, а не птица | Взять наше. Если апстрим вернул/обновил PNG — удалить их снова (иначе растр перекроет вектор на части плотностей). Проверка — `notificationIconsAreJetBoxMark` |

### 4.5. Удалённые бинарники — отдельной строкой

| № | Что удалено | Зачем | Если апстрим их обновит |
|---|---|---|---|
| 26 | **44 растра** с оранжевой птицей NetBird: `drawable-{h,m,xh,xxh,xxxh}dpi/ic_close.png` (5), `drawable-xxhdpi/activation_on.png` и `drawable-night-xxhdpi/activation_on.png` (2), `drawable/nb_nav_logo.png`, `mipmap-*dpi/ic_launcher.webp`, `ic_launcher_round.webp`, `ic_launcher_foreground.webp` (15), `mipmap-xhdpi/ic_mask_bg.png`, `tool/src/main/res/drawable-*dpi/notification_icon_{connected,connecting,disconnected,error}.png` (20) | Нигде не нужны (иконка — адаптивная, значки — векторные, на `ic_close`/`activation_on`/`nb_nav_logo` в коде и макетах ссылок нет), а оранжевый знак не должен попасть в APK | Конфликт «modified by them, deleted by us» — **удалить снова**: `git rm <файл>`. Затем `git grep -n '<имя без расширения>' -- app/src tool/src`: если апстрим стал на них ссылаться — заменить ресурс нашим (по образцу строк 21–25), а не возвращать растр |

### 4.5.1. Файлы корня репозитория

| № | Файл | Что изменено | Зачем | Как разрешать конфликт |
|---|---|---|---|---|
| 30 | `Readme.md` | Наш целиком (ru + en): что такое JetBox, сборка, лицензия и происхождение | Публичный репозиторий форка — про JetBox, а не про NetBird | `git checkout --ours Readme.md`. Что поменял апстрим — смотреть `git diff <старый тег>..<новый тег> -- Readme.md` и переносить вручную, если касается нас (требования к сборке, версии JDK/NDK/Go) |
| 31 | `.gitignore` | В конце — наш блок `# JetBox:` (`*.jks`, `*.keystore`, `*.local.md`, `release.password`, `dist/`) | Ключи подписи, локальные заметки и копии релизных APK не попадают в git | Обе стороны: правила апстрима + наш блок целиком в конце |
| 32 | `NOTICE`, `.gitleaksignore`, `JETBOX-BUILD.md`, `patches/` (строка 28) | Новые файлы (у апстрима их нет) | NOTICE — требования GPL-3.0 п. 5a и сторонние лицензии; `.gitleaksignore` — вымышленные фикстуры ссылки в тестах; этот документ | Конфликта быть не может. Если апстрим заведёт свой `NOTICE` — объединить: его текст + наш |
| 33 | `jetbox-bundle.sh` (новый, в корне) | Сборка AAB для Google Play: ключ загрузки `upload.jks`, пароль из файла в `NETBIRD_UPLOAD_*`, `--no-daemon`, Kotlin in-process; `-Pjetbox.store=play -Pjetbox.abis=arm64-v8a,armeabi-v7a,x86_64 -Pjetbox.legacyPackaging=false bundleRelease`; после сборки — проверка dex (нет адресов APK) и 16 КБ у всех `.so` | Бандл одной командой с теми же гарантиями, что у `jetbox-release.sh` (§6.1.1) | Конфликта быть не может. Проверка — `PlayStoreVariantTest.bundleScriptGuards` |
| 34 | `jetbox-pepk-export.sh` (новый, в корне) | Экспорт `release.jks` для Play App Signing инструментом PEPK из Console; без `--run` только показывает команду | Ключ подписи приложения — один на сайт, RuStore и Play (§6.2, «Google Play») | Конфликта быть не может. Проверка — `PlayStoreVariantTest.pepkScriptIsDryRunByDefault` |

Итого врезок в таблице — **34 строки** (в т. ч. строка 26 — удалённые бинарники,
строка 27 — новый файл `jetbox-release.sh` в §4.1, строка 29 — в §4.2, 30–34 — §4.5.1).
Наш модуль `:jetbox` (в т. ч. флаг `-Pjetbox.store`, §6.1) — не врезка.

### 4.6. На что опирается `:jetbox` в апстриме без правки апстримовых файлов

Врезок здесь нет, но слияние тега может сломать это **молча**: строковые
литералы, private-константы и имена prefs компилятор не сверяет. Сторож —
`UpstreamContractsTest` (читает исходники апстрима и ядра). Покраснел после
слияния — апстрим сменил контракт: чинить наш код по этой таблице, а не тест.

| Что | Где у апстрима | Кто у нас пользуется | Что сломается |
|---|---|---|---|
| `MyLocalBinder.runEngine(null, false)` — без `URLOpener` ядро идёт в `runWithoutLogin` (браузер не открывается) | `tool/.../VPNService.java`, `EngineRunner.runClient` | `AndroidEngine.start` | Старт туннеля откроет вход NetBird в браузере |
| `MyLocalBinder.isRunning`, `stopEngine`, `isLoginRequired`, `peersInfo`, `networks`, `selectRoute`/`deselectRoute` (бросают `Exception`), `connectedAtMillis` (врезка §4 стр. 9–10), `addConnectionObserver`/`removeConnectionObserver` (врезка), `setConnectionStateListener`, `addServiceStateListener`, `addRouteChangeListener` | `tool/.../VPNService.java` | `AndroidEngine` | Не соберётся (кроме смысла: `setConnectionStateListener` при установке повторяет последнее состояние наблюдателям) |
| `ServiceStateListener`: при подписке сервис сразу зовёт `onStarted`/`onStopped` по текущему состоянию | `EngineRunner.addServiceStateListener` | `AndroidEngine` (начальная фаза после переподключения к сервису) | После разрыва привязки щит «Отключено» при работающем ядре |
| `ACTION_STOP_ENGINE` — broadcast, приёмник зарегистрирован `RECEIVER_NOT_EXPORTED` | `VPNService.onCreate` | `WidgetToggleReceiver` (`setPackage`) | Виджет не выключит туннель |
| Действие always-on `"android.net.VpnService"` (`INTENT_ALWAYS_ON_START`, **private**) → `runWithoutAuth()` | `VPNService.onStartCommand` | `WidgetToggleReceiver.ALWAYS_ON_START` | Виджет не включит туннель |
| `INTENT_ACTION_START` — FGS до `runEngine` | `VPNService.onStartCommand` | `AndroidEngine.start` | — (не соберётся) |
| prefs `ui_prefs`, ключ `first_launch` | `app/.../ui/PreferenceUI.java` | `AndroidEngine.register` | Расширенный режим покажет экран первого запуска NetBird |
| prefs `netbird`, ключ `isConnectionForceRelayed` | `tool/.../Preferences.java` | `ConnectionDefaults` (через `JetBoxProcess.startup`) | Весь трафик снова через ретранслятор |
| `MDMBridge.openPreferences(ctx, configPath)`, `MDMBridge.newAuth(ctx, configPath, url)`, `ProfileManagerWrapper.getActiveConfigPath()` | `tool/.../MDMBridge.java`, `ProfileManagerWrapper.java` | `AndroidEngine.register` (порядок — как `FirstInstallFragment.applyServer`) | Регистрация по ключу |
| Коды `Android.ClientState*` (= `peer.ClientState`, `iota`: 0 Disconnected, 1 Connected, 2 Connecting, 3 Disconnecting, 4 NoNetwork) и `Android.ConnStatusConnected` | `netbird/client/internal/peer/listener.go`, `netbird/client/android/*.go` | `CoreState`, `HomeViewModel`, `AndroidEngine` | «Нет интернета» и старт без сети на щите |
| Имя класса `io.netbird.client.MainActivity` (строкой) | `app/` | `openUpstreamMainActivity`, `ForegroundNotification.extendIntent` | Расширенный режим и «Продлить сессию» не откроются |

> `app/src/main/ic_launcher-playstore.png` (512×512 для магазина) заменён иконкой JetBox
> (коммит 2a64903; копия — `store/android/icon-512.png` в папке документов). В APK не попадает.

## 5. Как не отставать от апстрима

Апстрим выпускает тег `vX.Y.Z` (и `-rc.N`) раз в 2–4 недели; каждый тег — это
исправления туннеля и ядра, которые клиент JetBox получает только через нас.

### 5.1. Правило

* **Сливаем (`merge`), а не ребейзим.** `main` публичного репозитория — это
  наша `jetbox`; ребейз переписал бы опубликованную историю (force-push, битые
  ссылки на коммиты в NOTICE и хендоверах). Каждое обновление — один
  merge-коммит с тегом апстрима во втором родителе и разбором конфликтов в
  сообщении.
* **Цель — стабильный тег апстрима** (не `-rc`, не голый `main`): между тегами
  апстрим бывает несобираемым. Коммиты после тега берём отдельным вторым
  слиянием, только если они чинят то, что видно у нас.
* **Срок — неделя от выхода тега**, исправления безопасности ядра — сразу.
* **Своя версия.** `versionName` у нас своя (1.0, 1.1, …), `versionCode` растёт
  на каждую выкладку. Какой апстрим внутри — пишем в «Что нового» и в хендовер.

### 5.2. Порядок одного обновления

```bash
git fetch origin --tags
git log --oneline <прошлый тег>..<новый тег>            # что пришло
git diff --name-only <прошлый тег> <новый тег> | sort > /tmp/up.txt
git diff --name-only <прошлый тег> jetbox      | sort > /tmp/our.txt
comm -12 /tmp/up.txt /tmp/our.txt                       # где будут конфликты (сверять с §4)
git switch -c update/upstream-<тег> jetbox
git -c merge.conflictstyle=zdiff3 merge --no-ff --no-commit <тег>
```

1. Разрешить конфликты по таблице §4, закоммитить с разбором в сообщении.
2. Если сдвинулся пин `netbird` (`git submodule status`) — пересобрать ядро
   (§1) с новой версией `<тег>+<sha7>.jetbox1`. Без этого новый код апстрима
   не соберётся.
3. Проверки — **все** (§5.3).
4. `jetbox` ← fast-forward из ветки обновления; публикация — по слову владельца.
5. Сборка с новым `versionCode`, установка, чек-лист §5.4. Только после него —
   дальше (RuStore / сайт, §6).

Ветка работы (`stage/one-button` и ей подобные) сливается в `jetbox` через
`git merge --no-ff`.

### 5.3. Проверки после слияния

```bash
# 1. applicationId и пакет сокета — наши, идентификаторы апстрима не вернулись
grep -n 'applicationId' app/build.gradle.kts                       # net.jetnet.jetbox
git grep -n 'data/data/io.netbird' -- build-android-lib.sh        # пусто
# 2. Телеметрии Google нет: файла нет (и в .gitignore), AD_ID нет
ls app/google-services.json 2>&1                                   # No such file
git grep -n 'AD_ID' -- app/src                                     # пусто
#    ⚠️ Firebase/Crashlytics в app/build.gradle.kts апстрима остаются, но подключаются
#    ТОЛЬКО при наличии app/google-services.json — не класть его никогда
# 3. Оранжевого NetBird нет
git grep -niE 'f58431|f68330' -- app/src tool/src                  # пусто
# 4. Тесты — наши охранные и апстрима
./gradlew cleanTestDebugUnitTest test
#    обязательно зелёные: BrandingResourcesTest, ManifestTest, WordingTest, JetBoxModuleTest,
#    UpstreamContractsTest (контракты §4.6)
# 5. Сборка
./gradlew assembleDebug -PversionCode=N -PversionName=X
# 6. Секреты не попали в историю
gitleaks git --log-opts="jetbox..HEAD" --redact
```

Новые экраны апстрима: найти, куда они встали, и убедиться, что в простом
режиме клиент их не видит (как остальные — за расширенным режимом); новые
ресурсы с брендом/оранжевым — перекрасить, вписать строку в §4.

### 5.4. Чек-лист на эмуляторе и телефоне

(Из спеки, раздел «Проверка». Эмулятор — `-s emulator-5554`; телефон —
отдельно, с `adb -s <serial>`.)

1. Ссылка из Кабинета → «Ваш JetBox» → «Подключить» → согласие VPN → «Вы под
   защитой JetBox», выход выбран; на главном экране панель: «до дома» в мс,
   «Напрямую», трафик растёт (открыть сайт в Chrome).
2. Щит: выключить/включить. Плитка быстрых настроек. Виджет рабочего стола.
3. Повторная ссылка того же JetBox — без вопроса.
4. Ссылка с другим `e`/`m` — «Другой JetBox».
5. Битая ссылка — «Ссылка повреждена».
6. Авиарежим — «Нет интернета».
7. Перезапуск приложения (смахнуть и открыть) — состояние верное, без перелогина.
8. Расширенный режим: 7 нажатий по «Версии» → «Расширенные настройки» →
   экраны NetBird → «Выключить расширенные настройки».
9. Светлая и тёмная темы, ru и en (команды — §2).
10. Обновление **поверх** прежней сборки: JetBox на месте, повторно ссылку не просит.
11. **Always-on VPN** (Настройки → Сеть → VPN → JetBox → «Постоянная VPN»): после
    перезагрузки туннель поднимается без окна, браузер NetBird не открывается,
    щит и виджет при открытии — «Под защитой».
12. **Согласие отозвано другим VPN** (включить чужой VPN поверх JetBox): JetBox
    отключается, щит «Защита выключена»; нажатие на щит снова спрашивает согласие.
13. **«Назад» во время регистрации** (экран по ссылке): приложение уходит в фон,
    сценарий идёт дальше; по возвращении — системный диалог VPN (если нужен) или
    «Под защитой»; если Android не дал запустить туннель из фона — «Не удалось
    включить защиту» с «Попробовать снова», а не «Что-то пошло не так».
14. **Авиарежим при включённом туннеле**: щит «Нет интернета» (жёлтый), нажатие
    выключает; авиарежим выкл. — «Под защитой» без перелогина. То же при старте
    туннеля в авиарежиме (щит не «Защита выключена»).

⚠️ Туннель до демо-стенда (шаги 1–3, 7) проверяется на эмуляторе и живом
телефоне; витрина (§2) показывает экраны, но не проверяет сеть.

### 5.5. Правила, которые держат конфликты маленькими

1. Весь код форка — в `jetbox/`. Новые файлы там не требуют правки апстримовых.
2. Врезки в апстримовые файлы — короткие, с комментарием `// JetBox:` (или
   `<!-- JetBox: … -->`), и каждая есть в таблице §4.
3. **Классы и пакеты `io.netbird.client…` не переименовывать.** Бренд живёт в
   ресурсах, манифесте и `applicationId`; Java-пакет апстрима остаётся.
4. Апстримовые экраны **прятать, а не удалять**; апстримовые workflow в
   `.github/` не трогать.
5. Пин ядра — тот же, что у апстрима в `netbird`, иначе бинарный API
   `gomobile` разъедется с Java-кодом.
6. Имена апстримовых ресурсов сохранены — заменяется только содержимое.

## 6. Релиз, подпись, домен — этап 3

### 6.1. Релизная сборка (сделано, 1.0 / versionCode 10)

```bash
source ../jetbox-env.sh    # <папка документов>/jetbox-env.sh — рядом с папкой форка
./jetbox-release.sh                          # arm64-v8a + armeabi-v7a, для сайта (BuildConfig.STORE = "apk")
./jetbox-release.sh -Pjetbox.abis=x86_64     # для эмулятора (ставится только после удаления debug — другая подпись)
./jetbox-release.sh -Pjetbox.store=rustore   # для RuStore (STORE = "rustore": своя проверка обновлений выключена)
# → app/build/outputs/apk/release/app-release.apk и копия dist/JetBox-<versionName>-<store>-<abis>.apk
#   (dist/ в .gitignore); в конце скрипт печатает STORE, ABI, размер, отпечаток, badging
```

* **Ключ:** `~/.config/jetbox-android/release.jks` (PKCS12, алиас `jetbox`, RSA 4096,
  `CN=JetBox, O=JetNet, C=RU`); пароль — файл `~/.config/jetbox-android/release.password`
  (пароль хранилища = пароль ключа). Оригинал файла и пароля — у владельца в Vaultwarden.
  Пути переопределяются `JETBOX_RELEASE_KEYSTORE` / `JETBOX_RELEASE_PASSWORD_FILE`.
* **Отпечаток SHA-256** (не секрет, для `assetlinks.json` и RuStore):
  `AB:7E:7E:B2:E2:2F:70:FA:23:8B:BA:AB:B7:80:9F:B0:13:7E:1D:95:F7:81:16:5A:33:99:51:02:4D:01:1E:7A`.
  Проверка: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
* **Пароль** скрипт читает из файла в переменные окружения `NETBIRD_UPLOAD_*` (их читает
  `signingConfigs.release` апстрима): не в аргументах команд (видны в `ps`), не в выводе.
  Gradle — `--no-daemon`, чтобы демон не жил с паролем в окружении; Kotlin —
  `-Pkotlin.compiler.execution.strategy=in-process`: иначе Gradle поднимает отдельный демон
  компилятора Kotlin, он наследует окружение (с паролем) и живёт после сборки. Без
  `ANDROID_HOME` скрипт сразу останавливается (`source ../jetbox-env.sh`). Релиз в обход
  скрипта без ключа — ошибка сборки «ключ подписи не задан» (§4.1, стр. 1 (в)).
* **Размер 1.0:** 40 МБ / 42 054 336 байт с ядром `.jetbox2` (arm64-v8a + armeabi-v7a; `libgojni.so` 41 + 39 МБ, в APK сжаты до
  14 + 14 МБ), сборка для эмулятора (x86_64) — 27 МБ. Было: debug 248 МБ (4 ABI, ядро с
  символами, несжатые `.so`).
* **`useLegacyPackaging = true` — осознанный компромисс.** `.so` в APK сжаты, поэтому при
  установке система распаковывает `.so` своей ABI в `/data/app/…/lib`: на телефоне лежат и APK
  (40 МБ), и распакованная `libgojni.so` (~41 МБ) — около 80 МБ. Замер на эмуляторе x86_64:
  `base.apk` 28 МБ + `lib/` 42 МБ. Несжатые `.so` грузились бы прямо из APK без распаковки, но
  сам APK был бы ~85 МБ (обе ABI). Взамен скачивание вдвое меньше (40 МБ вместо ~85) — для
  клиентов на мобильном интернете это важнее. Для бандла Google Play решение обратное — `false`
  (замер — §6.1.1).
* **Разрешения APK 1.0** (`aapt2 dump permissions app/build/outputs/apk/release/app-release.apk`):
  `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
  `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` и
  служебное `net.jetnet.jetbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx, signature).
  **Нет** `CAMERA` (снята врезкой, §4.2, стр. 7), `AD_ID` и ничего с `io.netbird.` (ни
  `permission`, ни `uses-permission`; authorities — `net.jetnet.jetbox.*`, §4.2, стр. 7 (д)):
  `aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep -c io.netbird.permission` → `0`. Новое разрешение в этом списке после
  слияния апстрима — повод проверить политику конфиденциальности и анкеты магазинов.
* **Данные устройства на сервер управления:** ядро `.jetbox2` не читает и не шлёт серийный номер
  и ANDROID_ID (§1, «Патчи ядра»); уходят модель, производитель, версия Android/ядра, имя устройства.
* Подпись — схема v2 (v1 не нужна: minSdk 26). `debuggable` нет, витрины (`ShowcaseActivity`)
  в релизе нет — она в `jetbox/src/debug`.
* **R8/minify выключен в 1.0 явно** (`isMinifyEnabled = false`): кода Kotlin мало, ядро
  зовётся из JNI по именам — риск ради нескольких МБ не нужен. При включении — keep для
  `-keep class io.netbird.gomobile.** { *; }` и `-keep class go.** { *; }` (без keep — падение
  при первом вызове ядра), затем полный прогон §5.4 на релизной сборке.
* `BuildConfig.STORE` (`:jetbox`) — `apk` по умолчанию, `-Pjetbox.store=rustore` / `play` для
  магазинов; другое значение — ошибка сборки. Адрес `version.json` — `BuildConfig.UPDATE_URL`,
  он задан **только** для `apk`: в магазинных сборках пустой, и `UpdateChecker` выключен
  (`enabled = url.isNotEmpty()`) — ни запроса, ни капсулы, ни строки «Обновить», ни самого адреса в
  dex (политика Play: обновляться только через магазин). Ссылки «О программе» (сайт, «Исходный
  код», лицензия, политика) — во всех вариантах. Сторож — `PlayStoreVariantTest` (в т. ч. в main-коде
  и ресурсах `:jetbox`/`:app`/`:tool` нет строк `box.jet-net.net/android` и `JetBox.apk`).

### 6.1.1. Google Play — бандл (AAB), 1.0.1 / versionCode 11

```bash
source ../jetbox-env.sh
BUNDLETOOL=<bundletool> ./jetbox-bundle.sh   # → dist/JetBox-1.0.1-play.aab; BUNDLETOOL — необязательно
```

* **Вариант** `-Pjetbox.store=play` (скрипт ставит сам): как `rustore` — см. последний пункт §6.1.
  Версия одна на все варианты: `apk/rustore/play` одного `versionCode` отличаются только `STORE`
  (и упаковкой). 1.0.1 / `11` — первый выпуск с вариантом `play`; APK сайта и RuStore на 1.0.1 пока
  не переведены (выкладка — словом владельца).
* **Ключ загрузки** — `~/.config/jetbox-android/upload.jks` (PKCS12, алиас `upload`, RSA 4096,
  `CN=JetBox Upload, O=JetNet, C=RU`), пароль — `upload.password` (0600; хранилища = ключа).
  Отпечаток SHA-256 (не секрет, вносится в Console при первой загрузке):
  `0D:49:5B:00:0D:F0:C5:3F:53:D5:92:F3:37:94:40:9A:3E:30:53:FF:F3:70:C4:B7:D2:64:2B:7E:F6:EE:BA:A6`.
  `release.jks` бандл **не подписывает**: это ключ подписи приложения, его держит Play App Signing
  (§6.2). Потерянный ключ загрузки Google сбрасывает через поддержку; ключ подписи — нет.
  Пути — `JETBOX_UPLOAD_KEYSTORE` / `JETBOX_UPLOAD_PASSWORD_FILE`.
* **Скрипт** `jetbox-bundle.sh` — те же гарантии, что у `jetbox-release.sh` (пароль из файла в
  окружение, `--no-daemon`, Kotlin in-process, `set +x`; без ключа — ошибка «ключ подписи не задан»).
  После сборки сам проверяет готовый файл и падает, если: в dex есть `box.jet-net.net/android` или
  `JetBox.apk`; у какой-то `.so` `LOAD Align` < `0x4000`. Печатает ABI, размер, отпечаток ключа
  загрузки; с `BUNDLETOOL` — ещё `versionCode`/`versionName` из манифеста бандла и `validate`.
* **ABI бандла:** `arm64-v8a`, `armeabi-v7a`, `x86_64` (Play отдаёт устройству одну; x86_64 —
  Chromebook и эмуляторы). APK сайта — по-прежнему `-Pjetbox.abis` (§6.1), по умолчанию две ABI.
* **Языки:** `bundle { language { enableSplit = false } }` — все строки в базовом APK. Язык
  меняется и внутри приложения (`LanguagePickerSheet`, AppCompat на Android < 13), а сплита с нужным
  языком на телефоне могло не оказаться. Цена — десятки КБ.
* **Страницы 16 КБ** (обязательны в Play для targetSdk ≥ 35) — проверено 26.10.2026 на бандле
  1.0.1 (`llvm-readelf -lW`, NDK 23): `libgojni.so` и `libandroidx.graphics.path.so` во всех трёх
  ABI — `LOAD Align 0x4000`. Ядро `.jetbox2` уже собрано с `-extldflags=-Wl,-z,max-page-size=16384`
  (флаг апстрима в `build-android-lib.sh`), пересборка не понадобилась. В сплитах, которые
  `bundletool build-apks` делает из бандла, несжатые `.so` выровнены и по zip на 16 КБ
  (`zipalign -c -P 16 -v 4` — ok). Эмулятор с 16 КБ страницами: образы есть
  (`system-images;android-36;google_apis_ps16k;x86_64` и др. в `sdkmanager --list`), не ставились —
  `jetbox36` на 4 КБ.
* **targetSdk 36** — сверено 26.10.2026 с developer.android.com/google/play/requirements/target-sdk
  (обновлена 16.10.2026): с 31.08.2026 новые приложения и обновления — Android 16 (API 36) и выше
  (продление — до 01.11.2026). Требование выполнено; следующего (API 37) на странице нет.
* **`useLegacyPackaging` для бандла — `false`** (`-Pjetbox.legacyPackaging=false`). Замер 26.10.2026,
  bundletool 1.18.3, бандл 1.0.1:

  | | `true` (сжатые `.so`) | `false` (несжатые) |
  |---|---|---|
  | Скачивание, `get-size total`, arm64-v8a, SDK 29+ | 26,04 МБ | 26,15 МБ |
  | Скачивание, armeabi-v7a / x86_64, SDK 29+ | 26,49 / 27,07 МБ | 26,65 / 27,19 МБ |
  | На телефоне arm64 (SDK 34): APK-сплиты | 47,2 МБ | 74,8 МБ |
  | + распакованная `libgojni.so` в `/data/app/…/lib` | +41,3 МБ = **88,5 МБ** | 0 = **74,8 МБ** |

  Скачивание отличается на 0,1 МБ (Play сжимает при передаче сам), а на телефоне `false` экономит
  ~14 МБ и не держит вторую копию ядра. На эмуляторе после установки из бандла `lib/x86_64` пуст.
  Сплит `base-master` ~33 МБ — это dex без сжатия (bundletool не сжимает dex для Android 10+;
  R8 выключен, §6.1) — отдельный вопрос, к упаковке `.so` не относится. APK сайта — `true` (§6.1).
* **Разрешения бандла 1.0.1** (`bundletool dump manifest`) — ровно список APK 1.0 (§6.1), нового нет;
  `extractNativeLibs="false"`, `minSdk 26`, `targetSdk 36`.
* **Проверка на эмуляторе** (26.10.2026, `jetbox36`, x86_64, Android 16): `bundletool build-apks
  --ks upload.jks --ks-pass file:… --key-pass file:…` → `extract-apks` по спецификации эмулятора →
  `adb -s emulator-5554 install-multiple` (сплиты `base`, `config.x86_64`, `config.xxhdpi`; прежний
  APK 1.0 с подписью release.jks удалён). Ссылка демо-стенда (`jetbox://`, ключ `…9967`) → «Ваш
  JetBox» → «Подключить» → согласие VPN → «Вы под защитой JetBox», выход `sv-demo-exit`; главный
  экран — «Под защитой», «до дома» в мс, «Напрямую», **капсулы обновления нет**; в настройках —
  «Версия 1.0.1 (11)», строки «Обновить» нет; «О программе» — сайт, «Исходный код», лицензия,
  политика. В logcat нет обращения к `version.json`. App Link `https://` на эмуляторе не
  подтверждается: `assetlinks.json` знает отпечаток `release.jks`, а установка подписана ключом
  загрузки — в Play будет подпись `release.jks` (§6.2), и ссылка заработает.
* **bundletool** в репозиторий не кладётся. Официальный `bundletool-<версия>.jar` с Maven Google —
  библиотека без зависимостей и без `aapt2`: запуск — `java -cp <jar>:<guava, protobuf-java(-util),
  aapt2-proto, dagger, javax.inject, jose4j, slf4j-api, auto-value-annotations,
  error_prone_annotations, failureaccess из ~/.gradle/caches> com.android.tools.build.bundletool.BundleToolMain`,
  `build-apks` — с `--aapt2 $ANDROID_HOME/build-tools/36.0.0/aapt2`; `install-apks` так не работает
  (нужен ddmlib) — вместо него `extract-apks --device-spec` + `adb install-multiple`. Готовый
  `bundletool-all-<версия>.jar` — в релизах github.com/google/bundletool.

### 6.2. Что ещё нужно для публикации

* **Релизный ключ подписи — один** на APK со своего сайта и RuStore: Android
  не даст обновить приложение, подписанное другим ключом. Хранить вне репозитория
  и вне `jetbox-env.sh`, сделать резервную копию; потеря ключа = переустановка
  у всех клиентов. Подпись App Links зависит от отпечатка (SHA-256) этого ключа.
* **`https://box.jet-net.net/.well-known/assetlinks.json`** с пакетом
  `net.jetnet.jetbox` и отпечатком релизного ключа (§6.1) — без него `autoVerify` не
  пройдёт и https-ссылка Кабинета откроет браузер, а не приложение (до этого
  работают схема `jetbox://` и ручное одобрение, §2). Отпечаток debug-сборки в
  файл не класть.
* **Хостинг APK** со своего сайта (HTTPS, постоянный адрес
  `https://box.jet-net.net/android/JetBox.apk` + `…/android/version.json`); для RuStore — их
  механизм обновлений.
* **Проверка обновлений** (сделано, `engine/UpdateChecker.kt`, только `STORE = "apk"`):
  `GET https://box.jet-net.net/android/version.json` раз в сутки, пока виден главный экран, и
  сразу при открытии настроек; таймауты 10 с, без cookie и редиректов, `User-Agent:
  JetBox-Android/<версия>`. Формат: `{"versionCode":11,"versionName":"1.1",
  "url":"https://box.jet-net.net/android/JetBox.apk","sha256":"<64 hex, необязательно>"}`;
  `url` — только `https://box.jet-net.net/…`, иначе файл игнорируется. Если `versionCode`
  больше установленного — капсула «Доступна новая версия 1.1» над панелью дома и строка
  «Обновить до 1.1» в настройках; нажатие открывает `url` в браузере (установку делает система).
  Нет файла (404), нет сети, мусор — тихо, ничего не показывается. При выкладке новой версии:
  сначала APK, потом `version.json`.
* **Google Play** (приложение создано в Console, аккаунт CARDO, пакет `net.jetnet.jetbox`; версий
  нет). Бандл — §6.1.1. **Play App Signing — вариант А** (решение владельца 26.10.2026): Play
  подписывает нашим `release.jks` — подпись одна у сайта, RuStore и Play, `assetlinks.json` не
  меняется, версия из Play встаёт поверх версии с сайта и наоборот. Экспорт ключа — PEPK из Console:
  `./jetbox-pepk-export.sh --pepk <pepk.jar> --encryption-key <encryption_public_key.pem>` показывает
  команду и сверяет отпечаток `release.jks` с §6.1; выполняет — только с `--run`, по слову
  владельца (пароль — `file:` или stdin, не в аргументах; результат — `~/.config/jetbox-android/pepk/`,
  после загрузки удалить; сухой прогон на заглушках 26.10.2026 — отпечаток совпал, команда собрана).
  Выбор ключа подписи — **один раз и навсегда**. Первая загрузка — через
  Console вместе с настройкой подписи; дальше — API ключом сервисного аккаунта. До публикации в Play
  коммит сборки должен лежать в `github.com/dsm150/jetbox-android` (GPL). В Play приложение не
  проверяет обновления и не ссылается на APK (§6.1).
* **Открытые исходники:** GitHub Actions апстрима выключить флагом репозитория при создании
  (`gh api -X PUT repos/<owner>/jetbox-android/actions/permissions -F enabled=false`).
* **ABI:** в релизе — один APK на `arm64-v8a` + `armeabi-v7a` (§6.1); x86/x86_64 — только
  эмулятор. Сплиты по ABI не делаем (42 МБ приемлемо). В бандле Play — три ABI, сплиты делает Play (§6.1.1).
* **Уведомление BIS/NSA** — клиент использует криптографию (WireGuard):
  подать уведомление по требованиям экспортного контроля перед распространением.
* **Лицензии и NOTICE** для распространяемой сборки (см. начало файла): APK —
  GPL-3.0 (клиент апстрима), ядро — BSD-3; исходники сборки — по
  `JetBoxConfig.SOURCE_CODE_URL` (публичный репозиторий форка, коммит сборки —
  в «Что нового»), тексты лицензий и NOTICE — в APK и на сайте.
