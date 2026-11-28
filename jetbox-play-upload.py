#!/usr/bin/env python3
"""
Google Play Developer API для JetBox (net.jetnet.jetbox) — без библиотек Google: JWT сервисного аккаунта
подписывается openssl, запросы — urllib (JETBOX-BUILD.md §6.1).

  ./jetbox-play-upload.py status                       # треки и выпуски (ничего не меняет)
  ./jetbox-play-upload.py library <файл.aab>          # бандл — только в библиотеку (правка с одним бандлом)
  ./jetbox-play-upload.py upload <файл.aab> --notes-ru "…" [--track production] [--commit]
        # загрузить бандл в новую правку и поставить его выпуском трека; без --commit правка
        # проверяется (edits.validate) и удаляется — в Play ничего не меняется. У сервисного аккаунта нет права на
        # выпуск в production (403 на validate/commit, как в 1.0.3–1.0.5): бандл — `library`, выпуск — в консоли.

Ключ — ~/.config/jetbox-android/play.json (0600) или JETBOX_PLAY_KEY. Токен и ключ не печатаются.
Выпуск в Play — только по слову владельца.

Каждый commit правки — с changesNotSentForReview=true: иначе commit отправляет на проверку в Google ВСЁ, что лежит в
приложении, включая черновики консоли (26.11.2026: загрузка бандла 20 в библиотеку отправила на автопроверку черновик
выпуска 19, который владелец решил не выпускать; руководитель отозвал его до Google). Отправка на проверку — только
в консоли, по слову владельца.

Если в консоли выключена «Управляемая публикация», Google отвечает 400 «Changes are sent for review automatically.
The query parameter changesNotSentForReview must not be set» (28.11.2026) — скрипт тогда НЕ коммитит (правка
удаляется) и ничего не меняет: загружать бандл в консоли или включить управляемую публикацию. Без параметра не
коммитить никогда.
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

PACKAGE = "net.jetnet.jetbox"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def token(key_path: str) -> str:
    key = json.load(open(key_path))
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": key["client_email"], "scope": SCOPE, "aud": key["token_uri"], "iat": now, "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    with tempfile.NamedTemporaryFile("w", delete=True) as pem:
        os.chmod(pem.name, 0o600)
        pem.write(key["private_key"])
        pem.flush()
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", pem.name], input=signing_input,
                             capture_output=True, check=True).stdout
    assertion = f"{header}.{claims}.{b64url(sig)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(key["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


class Play:
    def __init__(self, access_token: str):
        self.auth = {"Authorization": "Bearer " + access_token}

    def call(self, method: str, url: str, body=None, data: bytes = None, ctype: str = "application/json"):
        headers = dict(self.auth)
        if body is not None:
            data = json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = ctype
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:1500]
            raise SystemExit(f"{method} {url.split(PACKAGE)[-1]} → HTTP {e.code}: {detail}")

    def edit(self) -> str:
        return self.call("POST", API + "/edits", body={})["id"]


def status(play: Play):
    edit = play.edit()
    try:
        tracks = play.call("GET", f"{API}/edits/{edit}/tracks")
        for t in tracks.get("tracks", []):
            for rel in t.get("releases", []):
                notes = "; ".join(f"{n['language']}: {n['text']}" for n in rel.get("releaseNotes", []))
                print(f"{t['track']:12} {rel.get('status'):10} codes={rel.get('versionCodes')} name={rel.get('name')} "
                      f"{('notes=' + notes) if notes else ''}")
        bundles = play.call("GET", f"{API}/edits/{edit}/bundles").get("bundles", [])
        print("бандлы в библиотеке:", sorted(b["versionCode"] for b in bundles)[-6:])
    finally:
        play.call("DELETE", f"{API}/edits/{edit}")


def upload(play: Play, aab: str, notes_ru: str, track: str, commit: bool):
    edit = play.edit()
    done = False
    try:
        data = open(aab, "rb").read()
        bundle = play.call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", data=data,
                           ctype="application/octet-stream")
        code = bundle["versionCode"]
        print(f"бандл загружен в правку: versionCode {code}, sha256 {bundle.get('sha256')}")
        release = {
            "versionCodes": [str(code)],
            "status": "completed",
            "releaseNotes": [{"language": "ru-RU", "text": notes_ru}],
        }
        play.call("PUT", f"{API}/edits/{edit}/tracks/{track}", body={"track": track, "releases": [release]})
        play.call("POST", f"{API}/edits/{edit}:validate")
        print(f"трек {track}: выпуск {code}, проверка правки пройдена")
        if commit:
            r = play.call("POST", f"{API}/edits/{edit}:commit?changesNotSentForReview=true")
            done = True
            print(f"правка {r.get('id')} применена — выпуск НЕ отправлен на проверку: отправить в консоли")
        else:
            print("без --commit: правка не применяется")
    finally:
        if not done:
            play.call("DELETE", f"{API}/edits/{edit}")


def library(play: Play, aab: str):
    edit = play.edit()
    done = False
    try:
        bundle = play.call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", data=open(aab, "rb").read(),
                           ctype="application/octet-stream")
        print(f"бандл загружен: versionCode {bundle['versionCode']}, sha256 {bundle.get('sha256')}")
        play.call("POST", f"{API}/edits/{edit}:commit?changesNotSentForReview=true")
        done = True
        print("правка применена — бандл в библиотеке; выпуск собирается в консоли")
    finally:
        if not done:
            play.call("DELETE", f"{API}/edits/{edit}")


def main():
    import urllib.parse  # noqa: F401 — нужен token()
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["status", "upload", "library"])
    p.add_argument("aab", nargs="?")
    p.add_argument("--notes-ru", default="")
    p.add_argument("--track", default="production")
    p.add_argument("--commit", action="store_true")
    a = p.parse_args()
    key = os.environ.get("JETBOX_PLAY_KEY", os.path.expanduser("~/.config/jetbox-android/play.json"))
    play = Play(token(key))
    if a.cmd == "status":
        status(play)
    elif a.cmd == "library":
        library(play, a.aab)
    else:
        if not a.aab or not a.notes_ru:
            sys.exit("upload: нужен файл .aab и --notes-ru")
        upload(play, a.aab, a.notes_ru, a.track, a.commit)


if __name__ == "__main__":
    import urllib.parse
    main()
