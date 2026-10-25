#!/bin/bash
# Бандл (AAB) JetBox для Google Play (JETBOX-BUILD.md §6.1, «Google Play»).
#
#   source ../jetbox-env.sh    # <папка документов>/jetbox-env.sh, рядом с форком
#   ./jetbox-bundle.sh         # → dist/JetBox-<versionName>-play.aab
#   BUNDLETOOL=<bundletool-all-<версия>.jar | обёртка bundletool> ./jetbox-bundle.sh
#                              # + validate (провал — ошибка), versionCode/versionName из манифеста бандла
#   ./jetbox-bundle.sh --check-apk-urls <файл.aab>
#                              # только проверка 1 по готовому файлу (самотест — PlayStoreVariantTest)
#
# Сборка: BuildConfig.STORE = "play" (своей проверки обновлений и адреса version.json нет),
# ABI arm64-v8a + armeabi-v7a + x86_64 (Play отдаёт устройству одну; x86_64 — Chromebook),
# .so несжатыми (-Pjetbox.legacyPackaging=false, решение и замер — §6.1). Остальные аргументы
# уходят в ./gradlew bundleRelease.
#
# Подпись — КЛЮЧОМ ЗАГРУЗКИ (upload.jks, алиас upload), а не release.jks: release.jks — ключ
# подписи приложения, его держит Play App Signing (§6.2). Пути переопределяются
# JETBOX_UPLOAD_KEYSTORE / JETBOX_UPLOAD_PASSWORD_FILE. Пароль — как в jetbox-release.sh: из файла
# в переменные NETBIRD_UPLOAD_*, не в аргументы (ps) и не в вывод; --no-daemon и Kotlin in-process —
# ни один демон не живёт после сборки с паролем в окружении. Ключ PKCS12 — пароль хранилища и ключа один.
#
# До сборки — тесты варианта play (:jetbox:testDebugUnitTest -Pjetbox.store=play), ещё без пароля
# в окружении. После сборки — проверки по готовому файлу (любая не прошла — выход с ошибкой):
#   * в dex, ресурсах (res, resources.pb) и base/root нет адресов APK с сайта
#     (box.jet-net.net/android, JetBox.apk) — политика Play;
#   * все .so выровнены на страницы 16 КБ (LOAD Align ≥ 0x4000, llvm-readelf из NDK);
#   * с BUNDLETOOL — bundletool validate.
set -euo pipefail
set +x

# Адреса APK с сайта: в бандле для Play их быть не должно (обновляться — только через магазин).
apk_url_pattern='box\.jet-net\.net/android|JetBox\.apk'

# Проверка 1: распаковать код и ресурсы бандла во временную папку и искать по файлам. Без конвейера:
# `unzip -p … | grep -q` под pipefail ловит SIGPIPE (141) у unzip, когда grep выходит на первом
# совпадении, — и проверка молча «не находила». Нативные .so не смотрим (ядро Go, адресов сайта нет).
# Код возврата: 0 — адресов нет, 1 — найдены (файлы — в stderr), 2 — нечего проверять или ошибка.
check_apk_urls() {
    local aab=$1 dir rc=0 found
    [ -f "$aab" ] || { echo "ОШИБКА: нет файла $aab" >&2; return 2; }
    dir=$(mktemp -d)
    unzip -q -o "$aab" 'base/dex/*' 'base/res/*' 'base/root/*' 'base/resources.pb' -d "$dir" > /dev/null 2>&1 || rc=$?
    # 11 — какого-то шаблона в архиве нет (base/root бывает пуст); остальное — ошибка распаковки.
    if [ "$rc" != 0 ] && [ "$rc" != 11 ]; then
        rm -rf "$dir"; echo "ОШИБКА: unzip $aab — код $rc" >&2; return 2
    fi
    if ! compgen -G "$dir/base/dex/*.dex" > /dev/null; then
        rm -rf "$dir"; echo "ОШИБКА: в $aab нет base/dex/*.dex — проверять нечего" >&2; return 2
    fi
    rc=0
    found=$(cd "$dir" && LC_ALL=C grep -a -r -l -E "$apk_url_pattern" base) || rc=$?
    rm -rf "$dir"
    case "$rc" in
        0) echo "ОШИБКА: в бандле есть адрес APK с сайта (box.jet-net.net/android / JetBox.apk):" >&2
           printf '  %s\n' $found >&2
           return 1 ;;
        1) return 0 ;;
        *) echo "ОШИБКА: grep по $aab — код $rc" >&2; return 2 ;;
    esac
}

if [ "${1:-}" = "--check-apk-urls" ]; then
    [ $# -eq 2 ] || { echo "Использование: $0 --check-apk-urls <файл.aab>" >&2; exit 2; }
    rc=0
    check_apk_urls "$2" || rc=$?
    [ "$rc" != 0 ] || echo "Адресов APK с сайта нет: $2"
    exit "$rc"
fi

: "${ANDROID_HOME:?ANDROID_HOME не задан — сначала source ../jetbox-env.sh}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME не задан — сначала source ../jetbox-env.sh}"

cd "$(dirname "$0")"

abis=arm64-v8a,armeabi-v7a,x86_64
legacy_packaging=false
version_name=$(sed -n 's/^versionName=//p' version.properties)
version_code=$(sed -n 's/^versionCode=//p' version.properties)
[ -n "$version_name" ] && [ -n "$version_code" ] || { echo "Нет versionName/versionCode в version.properties" >&2; exit 1; }

keystore="${JETBOX_UPLOAD_KEYSTORE:-$HOME/.config/jetbox-android/upload.jks}"
password_file="${JETBOX_UPLOAD_PASSWORD_FILE:-$HOME/.config/jetbox-android/upload.password}"
[ -f "$keystore" ] || { echo "Нет ключа загрузки: $keystore" >&2; exit 1; }
[ -f "$password_file" ] || { echo "Нет файла пароля: $password_file" >&2; exit 1; }

readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-readelf"
[ -x "$readelf" ] || { echo "Нет llvm-readelf: $readelf" >&2; exit 1; }

# Тесты варианта play — до того, как пароль попадёт в окружение (демон Gradle его не увидит).
./gradlew -Pjetbox.store=play :jetbox:testDebugUnitTest \
    || { echo "ОШИБКА: тесты варианта play (:jetbox:testDebugUnitTest) не прошли" >&2; exit 1; }

IFS= read -r password < "$password_file" || true
[ -n "$password" ] || { echo "Пустой файл пароля: $password_file" >&2; exit 1; }

export NETBIRD_UPLOAD_STORE_FILE="$keystore"
export NETBIRD_UPLOAD_KEY_ALIAS="upload"
export NETBIRD_UPLOAD_STORE_PASSWORD="$password"
export NETBIRD_UPLOAD_KEY_PASSWORD="$password"
unset password

# --no-daemon: демон Gradle не остаётся жить с паролем в окружении;
# in-process: и демон Kotlin не поднимается (он тоже унаследовал бы окружение).
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
    -Pjetbox.store=play -Pjetbox.abis="$abis" -Pjetbox.legacyPackaging="$legacy_packaging" \
    bundleRelease "$@"

unset NETBIRD_UPLOAD_STORE_PASSWORD NETBIRD_UPLOAD_KEY_PASSWORD

aab=app/build/outputs/bundle/release/app-release.aab
mkdir -p dist
out="dist/JetBox-${version_name}-play.aab"
cp "$aab" "$out"

# Проверка 1: адресов APK с сайта в бандле нет (BuildConfig.UPDATE_URL пуст, JETBOX-BUILD.md §6.1).
check_apk_urls "$out" || exit 1
echo "Адресов APK с сайта в dex/res/root/resources.pb нет"

# Проверка 2: страницы 16 КБ — у каждого сегмента LOAD каждой .so выравнивание не меньше 0x4000.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q "$out" 'base/lib/*' -d "$tmp"
bad=0
echo
echo "Страницы 16 КБ (LOAD Align):"
while IFS= read -r so; do
    aligns=$("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u | tr '\n' ' ')
    ok=yes
    for a in $aligns; do [ $((a)) -ge 16384 ] || ok=no; done
    [ -n "$aligns" ] || ok=no
    echo "  ${so#"$tmp"/base/lib/}: ${aligns}$([ "$ok" = yes ] && echo ok || echo 'НЕ ВЫРОВНЕНА')"
    [ "$ok" = yes ] || bad=1
done < <(find "$tmp/base/lib" -name '*.so' | sort)
[ "$bad" = 0 ] || { echo "ОШИБКА: есть .so не на 16 КБ" >&2; exit 1; }

echo
echo "STORE: play"
echo "ABI:   $(unzip -Z1 "$out" 'base/lib/*' | cut -d/ -f3 | sort -u | tr '\n' ' ')"
echo "AAB:   $out ($(( $(stat -f %z "$out" 2>/dev/null || stat -c %s "$out") / 1024 / 1024 )) МБ)"
if [ -n "${BUNDLETOOL:-}" ]; then
    case "$BUNDLETOOL" in *.jar) bt=(java -jar "$BUNDLETOOL") ;; *) bt=("$BUNDLETOOL") ;; esac
    echo "versionCode: $("${bt[@]}" dump manifest --bundle "$out" --xpath /manifest/@android:versionCode)"
    echo "versionName: $("${bt[@]}" dump manifest --bundle "$out" --xpath /manifest/@android:versionName)"
    "${bt[@]}" validate --bundle "$out" >/dev/null || { echo "ОШИБКА: bundletool validate" >&2; exit 1; }
    echo "bundletool validate: ok"
else
    echo "versionCode: $version_code, versionName: $version_name (из version.properties; BUNDLETOOL не задан)"
fi
echo "Подпись (ключ загрузки):"
keytool -printcert -jarfile "$out" | grep -E 'SHA256:|Owner:'
