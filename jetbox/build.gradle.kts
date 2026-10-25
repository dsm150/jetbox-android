plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.jetnet.jetbox"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "APP_VERSION_NAME", "\"${rootProject.extra["appVersionName"]}\"")
        buildConfigField("int", "APP_VERSION_CODE", "${rootProject.extra["appVersionCode"]}")
        // Куда идёт сборка: apk — файл с сайта (своя проверка обновлений); rustore и play — магазины
        // (обновляет магазин). Адрес version.json есть только в сборке apk: в магазинных сборках нет ни
        // проверки, ни самого адреса — политика Play запрещает обновляться мимо магазина (JETBOX-BUILD.md §6.1).
        val store = (project.findProperty("jetbox.store") as String?) ?: "apk"
        require(store in listOf("apk", "rustore", "play")) { "-Pjetbox.store=apk|rustore|play, а не $store" }
        buildConfigField("String", "STORE", "\"$store\"")
        val updateUrl = if (store == "apk") "https://box.jet-net.net/android/version.json" else ""
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":tool"))
    implementation(project(":gomobile"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Только core (стрелка «›», галочка в листе); шестерня и дом — свои векторы JetBoxIcons.
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.appcompat)
    // Theme.Material3.DayNight.NoActionBar — родитель темы окна (библиотека уже есть у :app).
    implementation(libs.material)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
