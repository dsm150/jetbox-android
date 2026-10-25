val hasGoogleServicesJson = rootProject.file("app/google-services.json").exists()

plugins {
    alias(libs.plugins.android.application)
}

if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

fun getPropertyOrEnv(propertyName: String, envName: String = propertyName): String? {
    return if (project.hasProperty(propertyName)) {
        project.property(propertyName) as String
    } else {
        System.getenv(envName)
    }
}

android {
    signingConfigs {
        create("release") {
            val storeFile = getPropertyOrEnv("NETBIRD_UPLOAD_STORE_FILE")
            val storePassword = getPropertyOrEnv("NETBIRD_UPLOAD_STORE_PASSWORD")
            val keyAlias = getPropertyOrEnv("NETBIRD_UPLOAD_KEY_ALIAS")
            val keyPassword = getPropertyOrEnv("NETBIRD_UPLOAD_KEY_PASSWORD")

            if (storeFile != null) {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    namespace = "io.netbird.client"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        applicationId = "net.jetnet.jetbox"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "3600000"
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // JetBox: R8 в 1.0 выключен явно — ядро зовётся из JNI по именам (JETBOX-BUILD.md §6).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // JetBox: в релиз — только ABI телефонов; -Pjetbox.abis=x86_64 — сборка для эмулятора.
            ndk {
                abiFilters += ((project.findProperty("jetbox.abis") as String?) ?: "arm64-v8a,armeabi-v7a")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }

    // JetBox: .so ядра в APK сжаты (extractNativeLibs) — APK вдвое меньше. Бандл для Google Play
    // (jetbox-bundle.sh) передаёт -Pjetbox.legacyPackaging=false: Play сам отдаёт устройству одну ABI,
    // а несжатые .so не распаковываются на телефоне второй копией (замер — JETBOX-BUILD.md §6.1).
    packaging {
        jniLibs {
            useLegacyPackaging = (project.findProperty("jetbox.legacyPackaging") as String?)?.toBooleanStrict() ?: true
        }
    }

    // JetBox: бандл без разбиения по языкам — язык меняется и внутри приложения (AppCompat на
    // Android < 13), а сплита с нужными строками на телефоне могло бы не оказаться.
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

// JetBox: релиз без ключа подписи — понятная ошибка до начала сборки, а не неподписанный APK.
// Проверяется только когда в графе есть упаковка/подпись релиза: debug, test (testReleaseUnitTest
// упаковку не тянет) и lint собираются без ключа.
gradle.taskGraph.whenReady {
    val releasePackaging = setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle")
    val needsKey = allTasks.any { it.path.startsWith(":app:") && it.name in releasePackaging }
    if (needsKey && getPropertyOrEnv("NETBIRD_UPLOAD_STORE_FILE") == null) {
        throw GradleException("ключ подписи не задан — используйте ./jetbox-release.sh (APK) или ./jetbox-bundle.sh (бандл Google Play)")
    }
}

dependencies {
    implementation(project(":tool"))
    implementation(project(":gomobile"))
    implementation(project(":jetbox"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.test.rules)
    implementation(libs.browser)  // Added for CustomTabsIntent
    implementation(libs.zxing)

    if (hasGoogleServicesJson) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.analytics)
    }
}
