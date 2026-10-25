# JetBox для Android

Приложение JetBox для Android-телефона. Форк открытого клиента
[NetBird для Android](https://github.com/netbirdio/android-client), лицензия
[GPL-3.0](LICENSE). Исходники открыты, потому что этого требует лицензия
апстрима, — и потому что владелец телефона должен иметь возможность проверить,
что делает приложение на его устройстве.

*English version below.*

## Что это

JetBox — устройство JetNet, которое стоит в доме или в офисе и держит
собственную частную сеть на [WireGuard](https://www.wireguard.com/)
через [NetBird](https://netbird.io/). Это приложение — телефонная часть
такой сети: одно нажатие, и телефон оказывается внутри неё, как дома.

* один экран, одна кнопка, никаких настроек при первом запуске;
* параметры подключения приходят одной ссылкой от личного кабинета —
  вводить ключи и адреса вручную не нужно;
* виджет на рабочем столе и плитка быстрых настроек;
* исходные экраны NetBird остались в сборке и открываются в расширенном
  режиме (семь быстрых нажатий по строке версии в настройках), если нужно
  посмотреть пиры, маршруты и журнал.

## Для кого

Для владельцев JetBox — тех, кому JetNet поставил устройство. Без него
приложению не к чему подключаться: сервер управления, ключи и выходные
узлы принадлежат конкретной установке.

Работать приложение может и с любой другой инсталляцией NetBird — адрес
сервера управления приходит параметром ссылки, — но поддерживается только
сценарий JetNet.

## Сборка

Нужны JDK 21, Android SDK (compileSdk 36), Android NDK ровно той версии,
что записана в `build-android-lib.sh`, и Go версии не ниже указанной в
`netbird/go.mod` (с установленным `gomobile`).

```bash
git clone --recurse-submodules https://github.com/dsm150/jetbox-android.git
cd jetbox-android
./build-android-lib.sh <версия-ядра>   # ядро агента → gomobile/netbird.aar
./gradlew :jetbox:testDebugUnitTest    # тесты
./gradlew assembleDebug                # debug-APK
```

Строка версии ядра, релизная сборка и подпись, известные грабли и перечень
правок апстримовых файлов — в [JETBOX-BUILD.md](JETBOX-BUILD.md).

Собранное ядро `gomobile/netbird.aar` в git не хранится: это артефакт чужого
кода (ядро агента, BSD-3-Clause), он пересобирается скриптом
`build-android-lib.sh` из подмодуля `netbird`.

Каталог `.github/` взят у апстрима без изменений. Его сценарии рассчитаны на
инфраструктуру NetBird и секреты, которых у этого репозитория нет, поэтому
GitHub Actions в репозитории отключены.

## Лицензия и происхождение

* Лицензия — GPL-3.0, файл [LICENSE](LICENSE) взят у апстрима и не менялся.
* Что именно изменено в форке и от какой версии он отведён (тег `v0.6.2`) —
  в [NOTICE](NOTICE).
* Изменено JetNet, октябрь 2026 (GPL-3.0, п. 5a).
* Ядро агента подключено подмодулем на
  [netbirdio/netbird](https://github.com/netbirdio/netbird) (BSD-3-Clause;
  серверные каталоги ядра — AGPL-3.0, см. LICENSE ядра — клиентом не
  используются); чужой код в этот репозиторий не копируется.
* Ядро собирается с нашими патчами из
  [`patches/netbird-core/`](patches/netbird-core/): оно не отправляет
  серверу управления серийный номер устройства и ANDROID_ID.
* Сборка «файл с сайта» проверяет обновления: раз в сутки и при открытии
  настроек — запрос `https://box.jet-net.net/android/version.json` (в
  запросе — только версия приложения). В сборке для магазина проверки нет.
* Отладочный пакет из расширенного режима никуда не загружается — только
  сохраняется в файл.
* Сторонние компоненты и их лицензии (wireguard-go, gVisor, pion, ZXing,
  xterm.js, AndroidX/Compose/Material, Kotlin) — в [NOTICE](NOTICE).
* Проект не связан с NetBird GmbH и не поддерживается ею.

Вопросы по этому форку — <info@jet-net.net>.
Вопросы по самому клиенту NetBird — в апстрим.

© 2026 JetNet. Основано на клиенте NetBird © NetBird GmbH.

---

# JetBox for Android

The JetBox app for Android phones. A fork of the open-source
[NetBird Android client](https://github.com/netbirdio/android-client),
licensed under [GPL-3.0](LICENSE). The source is open because the upstream
license requires it — and because the owner of a phone should be able to
check what an app does on it.

## What it is

JetBox is a JetNet appliance that lives in a home or an office and runs a
private network of its own, built on [WireGuard](https://www.wireguard.com/)
via [NetBird](https://netbird.io/). This app is the phone side of that
network: one tap, and the phone is inside it, as if at home.

* one screen, one button, nothing to configure on first launch;
* connection parameters arrive as a single link from the customer portal —
  no setup keys or server addresses to type;
* a home-screen widget and a quick settings tile;
* the original NetBird screens are still in the build and open in advanced
  mode (seven quick taps on the version line in settings), when peers,
  routes and logs are needed.

## Who it is for

For JetNet customers who have the appliance. Without one there is nothing
for the app to connect to: the management server, the keys and the exit
nodes belong to a particular installation.

The app can technically work against any other NetBird installation — the
management URL is a parameter of the link — but only the JetNet scenario is
supported.

## Building

You need JDK 21, the Android SDK (compileSdk 36), the exact Android NDK
version pinned in `build-android-lib.sh`, and Go at least as new as the
version in `netbird/go.mod` (with `gomobile` installed).

```bash
git clone --recurse-submodules https://github.com/dsm150/jetbox-android.git
cd jetbox-android
./build-android-lib.sh <core-version>  # agent core -> gomobile/netbird.aar
./gradlew :jetbox:testDebugUnitTest    # tests
./gradlew assembleDebug                # debug APK
```

The core version string, release build and signing, known pitfalls and the
list of patches to upstream files are in [JETBOX-BUILD.md](JETBOX-BUILD.md)
(in Russian).

The built core `gomobile/netbird.aar` is not kept in git: it is a build
artifact of third-party code (the agent core, BSD-3-Clause) and is
regenerated by `build-android-lib.sh` from the `netbird` submodule.

The `.github/` directory is taken from upstream unchanged. Its workflows
assume NetBird's infrastructure and secrets that this repository does not
have, so GitHub Actions are disabled for the repository.

## License and provenance

* Licensed under GPL-3.0; [LICENSE](LICENSE) is the upstream file, unmodified.
* What exactly was changed in this fork, and the upstream version it was
  branched from (tag `v0.6.2`), are listed in [NOTICE](NOTICE).
* Modified by JetNet, October 2026 (GPL-3.0, section 5a).
* The agent core is a submodule pointing at
  [netbirdio/netbird](https://github.com/netbirdio/netbird) (BSD-3-Clause;
  the server directories of the core are AGPL-3.0, see the core's LICENSE,
  and are not used by the client); no third-party code is vendored into
  this repository.
* The core is built with our patches from
  [`patches/netbird-core/`](patches/netbird-core/): it does not send the
  device serial number or ANDROID_ID to the management server.
* The website APK build checks for updates: once a day and when settings
  are opened it requests `https://box.jet-net.net/android/version.json`
  (only the app version is sent). Store builds do not check.
* The debug bundle from advanced mode is never uploaded anywhere — it can
  only be saved to a file.
* Third-party components and their licenses (wireguard-go, gVisor, pion,
  ZXing, xterm.js, AndroidX/Compose/Material, Kotlin) are listed in
  [NOTICE](NOTICE).
* This project is not affiliated with or endorsed by NetBird GmbH.

Questions about this fork: <info@jet-net.net>.
Questions about the NetBird client itself belong upstream.

(c) 2026 JetNet. Based on the NetBird client (c) NetBird GmbH.
