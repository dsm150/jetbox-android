#!/bin/bash
# Релизная сборка JetBox (JETBOX-BUILD.md §6.1).
#
#   source ../jetbox-env.sh    # <папка документов>/jetbox-env.sh, рядом с форком
#   ./jetbox-release.sh                          # arm64-v8a + armeabi-v7a, для сайта (BuildConfig.STORE=apk)
#   ./jetbox-release.sh -Pjetbox.abis=x86_64     # для эмулятора
#   ./jetbox-release.sh -Pjetbox.store=rustore   # для RuStore
#
# Все аргументы уходят в ./gradlew assembleRelease. Результат —
# app/build/outputs/apk/release/app-release.apk, копия — dist/JetBox-<versionName>-<store>-<abis>.apk
# (dist/ в .gitignore); в конце печатаются STORE/ABI, размер и отпечаток подписи.
#
# Ключ и пароль — вне репозитория (переопределяются переменными JETBOX_RELEASE_KEYSTORE /
# JETBOX_RELEASE_PASSWORD_FILE). Пароль читается из файла в переменные окружения
# NETBIRD_UPLOAD_*, которые читает app/build.gradle.kts: в аргументы команд (видны в ps),
# в вывод и в лог он не попадает. Демон Gradle не остаётся (--no-daemon), Kotlin компилируется
# внутри процесса Gradle (in-process) — отдельный демон Kotlin пароль из окружения не наследует.
# Ключ PKCS12 — пароль хранилища и ключа один.
set -euo pipefail
set +x

: "${ANDROID_HOME:?ANDROID_HOME не задан — сначала source ../jetbox-env.sh}"

cd "$(dirname "$0")"

# Что собираем — для имени файла в dist/ (последний -P… побеждает, как и в Gradle).
store=apk
abis=arm64-v8a,armeabi-v7a
for arg in "$@"; do
    case "$arg" in
        -Pjetbox.store=*) store="${arg#-Pjetbox.store=}" ;;
        -Pjetbox.abis=*) abis="${arg#-Pjetbox.abis=}" ;;
    esac
done
version_name=$(sed -n 's/^versionName=//p' version.properties)
[ -n "$version_name" ] || { echo "Нет versionName в version.properties" >&2; exit 1; }

keystore="${JETBOX_RELEASE_KEYSTORE:-$HOME/.config/jetbox-android/release.jks}"
password_file="${JETBOX_RELEASE_PASSWORD_FILE:-$HOME/.config/jetbox-android/release.password}"

[ -f "$keystore" ] || { echo "Нет ключа: $keystore" >&2; exit 1; }
[ -f "$password_file" ] || { echo "Нет файла пароля: $password_file" >&2; exit 1; }

IFS= read -r password < "$password_file" || true
[ -n "$password" ] || { echo "Пустой файл пароля: $password_file" >&2; exit 1; }

export NETBIRD_UPLOAD_STORE_FILE="$keystore"
export NETBIRD_UPLOAD_KEY_ALIAS="jetbox"
export NETBIRD_UPLOAD_STORE_PASSWORD="$password"
export NETBIRD_UPLOAD_KEY_PASSWORD="$password"
unset password

# --no-daemon: демон Gradle не остаётся жить с паролем в окружении;
# in-process: и демон Kotlin не поднимается (он тоже унаследовал бы окружение).
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process assembleRelease "$@"

unset NETBIRD_UPLOAD_STORE_PASSWORD NETBIRD_UPLOAD_KEY_PASSWORD

apk=app/build/outputs/apk/release/app-release.apk
mkdir -p dist
out="dist/JetBox-${version_name}-${store}-$(printf %s "$abis" | tr -d ' ' | tr ',' '+').apk"
cp "$apk" "$out"
build_tools="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -n 1)"
echo
echo "STORE: $store"
echo "ABI:   $abis"
echo "APK:   $out ($(( $(stat -f %z "$out" 2>/dev/null || stat -c %s "$out") / 1024 / 1024 )) МБ)"
"$build_tools/apksigner" verify --print-certs "$out" | grep -E 'SHA-256|DN:'
"$build_tools/aapt2" dump badging "$out" | grep -E "^(package|native-code):"
