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

    const val PRIVACY_POLICY_URL = "https://jet-net.net/privacy"
    const val SITE_URL = "https://jet-net.net"

    /** Исходники форка и лицензия — GPL-3.0 требует их доступности. */
    const val SOURCE_CODE_URL = "https://github.com/dsm150/jetbox-android"
    const val LICENSE_URL = "https://github.com/dsm150/jetbox-android/blob/main/LICENSE"
}
