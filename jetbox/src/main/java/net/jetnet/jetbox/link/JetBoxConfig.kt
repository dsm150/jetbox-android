package net.jetnet.jetbox.link

/** Константы контракта ссылки (порт `JetBoxConfig` из JetBoxLink.swift, LINK-CONTRACT.md). */
object JetBoxConfig {
    /** Домен Кабинета, на котором лежит assetlinks.json (App Links). */
    const val UNIVERSAL_LINK_HOST = "box.jet-net.net"

    /** Путь App Link: `/connect` и всё под ним. */
    const val UNIVERSAL_LINK_PATH = "/connect"

    /** Запасной вход: QR со старых материалов и кнопка на странице /connect. */
    const val CUSTOM_SCHEME = "jetbox"

    /** Management по умолчанию — страховка для старых ссылок (Кабинет всегда кладёт `m=` явно). */
    const val DEFAULT_MANAGEMENT_URL = "https://netbird.jet-net.net"

    /** Маска адреса management из ссылки: только поддомены этого домена (`JetBoxLink.isAllowedManagement`). */
    const val MANAGEMENT_SUFFIX = ".jet-net.net"

    /**
     * API приложения в Кабинете (вход по рабочей почте с кодом, корпоративный VPN — спека VPN_Box
     * 2026-11-25-corporate-vpn-fabrika-design): `login/request`, `login/verify`. Один адрес на сборку, без discover.
     */
    const val APP_API_BASE = "https://box.jet-net.net/api/app/v1"

    /**
     * JetBox safety (слово владельца 25.11.2026, как iOS): приложение принимает только ссылки с верной подписью `s`
     * ([JetBoxLinkSignature]) — с любым хостом и любым сервером `m`; имя и место сервера не важны. `false` — прежние
     * правила (свой хост, маска [MANAGEMENT_SUFFIX], без подписи) — только для тестов логики сценария.
     */
    const val LINK_SIGNED = true

    /**
     * Веб-страница удаления аккаунта — для подключённых по ссылке или QR (токена приложения нет) и для анкеты Google Play.
     * Адрес — от руководителя JetBox (страница на box.jet-net.net делается); до его письма — предварительный.
     */
    const val ACCOUNT_DELETE_URL = "https://box.jet-net.net/account/delete"

    const val PRIVACY_POLICY_URL = "https://jet-net.net/privacy"
    const val SITE_URL = "https://jet-net.net"

    /** Исходники форка и лицензия — GPL-3.0 требует их доступности. */
    const val SOURCE_CODE_URL = "https://github.com/dsm150/jetbox-android"
    const val LICENSE_URL = "https://github.com/dsm150/jetbox-android/blob/main/LICENSE"
}
