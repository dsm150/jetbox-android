#!/bin/bash
# Экспорт ключа подписи приложения (release.jks) для Play App Signing (JETBOX-BUILD.md §6.2, «Google Play»).
# Вариант А (решение владельца 26.10.2026): Play подписывает приложение НАШИМ ключом — подпись одна у
# сайта, RuStore и Play, assetlinks.json не меняется. Выбор делается ОДИН РАЗ и навсегда.
#
#   ./jetbox-pepk-export.sh --pepk <pepk.jar> --encryption-key <encryption_public_key.pem>          # показать, что будет
#   ./jetbox-pepk-export.sh --pepk <pepk.jar> --encryption-key <encryption_public_key.pem> --run    # выполнить
#   … --run --interactive   # pepk сам спросит пароли (если подача из файла не сработала)
#   … --out <файл.zip>      # куда положить (по умолчанию ~/.config/jetbox-android/pepk/jetbox-signing-key.zip)
#
# pepk.jar и encryption_public_key.pem даёт Play Console (настройка подписи приложения → «Экспорт и
# загрузка ключа из хранилища Java»); в git их не класть. Запуск — только по слову владельца.
# Результат — ZIP с ключом, зашифрованным открытым ключом Google (расшифровать может только Google);
# загружается в Console вручную. Хранить вне репозитория, после загрузки — удалить.
#
# Пароль ключа (release.password; PKCS12 — пароль хранилища и ключа один) не попадает в аргументы
# команд (видны в ps) и в вывод: pepk получает его через file: (если в `pepk.jar --help` есть и
# --keystore-pass, и «file:») или через stdin. Перед экспортом скрипт сверяет отпечаток release.jks
# с §6.1. Даже сухой прогон (без --run) запускает `java -jar <pepk.jar> --help` — выбрать способ подачи пароля.
set -euo pipefail
set +x

expected_sha256="AB:7E:7E:B2:E2:2F:70:FA:23:8B:BA:AB:B7:80:9F:B0:13:7E:1D:95:F7:81:16:5A:33:99:51:02:4D:01:1E:7A"
keystore="${JETBOX_RELEASE_KEYSTORE:-$HOME/.config/jetbox-android/release.jks}"
password_file="${JETBOX_RELEASE_PASSWORD_FILE:-$HOME/.config/jetbox-android/release.password}"
alias=jetbox
out="$HOME/.config/jetbox-android/pepk/jetbox-signing-key.zip"
pepk=""
enc_key=""
run=no
interactive=no

while [ $# -gt 0 ]; do
    case "$1" in
        --pepk) pepk="$2"; shift 2 ;;
        --encryption-key) enc_key="$2"; shift 2 ;;
        --out) out="$2"; shift 2 ;;
        --run) run=yes; shift ;;
        --interactive) interactive=yes; shift ;;
        *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -n "$pepk" ] && [ -f "$pepk" ] || { echo "Нужен --pepk <pepk.jar> (скачать в Play Console)" >&2; exit 1; }
[ -n "$enc_key" ] && [ -f "$enc_key" ] || { echo "Нужен --encryption-key <encryption_public_key.pem> (из Play Console)" >&2; exit 1; }
[ -f "$keystore" ] || { echo "Нет ключа: $keystore" >&2; exit 1; }
[ -f "$password_file" ] || { echo "Нет файла пароля: $password_file" >&2; exit 1; }
command -v java > /dev/null || { echo "Нет java — source ../jetbox-env.sh" >&2; exit 1; }

# Тот ли ключ: отпечаток сертификата алиаса jetbox против §6.1 (пароль — keytool -storepass:file).
actual_sha256=$(keytool -list -v -keystore "$keystore" -storetype PKCS12 -alias "$alias" -storepass:file "$password_file" \
    | awk '/SHA256:/ { print $2; exit }')
if [ "$actual_sha256" != "$expected_sha256" ]; then
    echo "ОШИБКА: отпечаток $keystore ($alias) не совпадает с JETBOX-BUILD.md §6.1:" >&2
    echo "  есть     $actual_sha256" >&2
    echo "  ожидался $expected_sha256" >&2
    exit 1
fi
echo "Ключ: $keystore, алиас $alias, SHA-256 $actual_sha256 — совпадает с §6.1"

cmd=(java -jar "$pepk" --keystore="$keystore" --alias="$alias" --output="$out"
     --include-cert --rsa-aes-encryption --encryption-key-path="$enc_key")
mode=stdin
if [ "$interactive" = yes ]; then
    mode=interactive
else
    # Справку — в переменную, а не `| grep -q`: под pipefail grep, вышедший на первом совпадении,
    # оставляет java с SIGPIPE — и проверка молча «не находила» (как было в jetbox-bundle.sh).
    pepk_help=$(java -jar "$pepk" --help 2>&1 || true)
    if grep -q -- '--keystore-pass' <<< "$pepk_help" && grep -q 'file:' <<< "$pepk_help"; then
        mode=file
    fi
fi
if [ "$mode" = file ]; then
    cmd+=(--keystore-pass="file:$password_file" --key-pass="file:$password_file")
fi

echo "Команда (пароль — $mode):"
printf '  %q' "${cmd[@]}"; echo
echo "Выход: $out"

[ "$run" = yes ] || { echo "Ничего не выполнено: для экспорта — тот же вызов с --run (по слову владельца)."; exit 0; }

# Папку выхода закрываем (700), только если создаём её сами: чужую (--out в существующую) не трогаем.
out_dir=$(dirname "$out")
if [ ! -d "$out_dir" ]; then
    mkdir -p "$out_dir"
    chmod 700 "$out_dir"
fi
umask 077
case "$mode" in
    file|interactive) "${cmd[@]}" ;;
    # Пароль хранилища и пароль ключа — две строки на stdin (printf — встроенная команда, в ps не видна).
    # pepk читает пароль только через System.console(): без терминала падает с NPE. `script -q /dev/null`
    # даёт ему псевдотерминал; ввод идёт с нашего stdin, readPassword не печатает его (проверено 26.10.2026
    # при переносе release.jks в Play App Signing).
    stdin) IFS= read -r password < "$password_file"
           script -q /dev/null "${cmd[@]}" < <(printf '%s\n%s\n' "$password" "$password"); unset password ;;
esac
ls -l "$out"
echo "Готово. Загрузить $out в Play Console (подпись приложения), затем удалить файл."
