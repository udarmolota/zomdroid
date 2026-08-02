import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val hasSigningConfig = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { localProperties[it] != null }

android {
    namespace = "com.zomdroid"
    compileSdk = 35

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(localProperties["RELEASE_STORE_FILE"].toString())
                storePassword = localProperties["RELEASE_STORE_PASSWORD"].toString()
                keyAlias = localProperties["RELEASE_KEY_ALIAS"].toString()
                keyPassword = localProperties["RELEASE_KEY_PASSWORD"].toString()

            }
        }
    }

    defaultConfig {
        applicationId = "com.zomdroid"
        minSdk = 30
        targetSdk = 35
        versionCode = 148
        versionName = "1.4.8"

        // JavaSteam + protobuf + kotlin stack push past the 64K method limit.
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Align native .so segments to 16 KB pages (Android 15+ requirement).
                // NDK r27 doesn't enable this by default; the flag adds -Wl,-z,max-page-size=16384.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "zomdroid-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

    buildTypes {
        if (hasSigningConfig) {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // JavaSteam / protobuf / bouncycastle / kotlin bring duplicate metadata files.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/{AL2.0,LGPL2.1}",
                "**/*.proto"
            )
        }
    }
  ndkVersion = "27.3.13750724"
}

dependencies {
    implementation(libs.gson)
    implementation(files("jars/fmod.jar"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.legacy.support.v4)

    // --- In-app Steam downloader (ported from RimDroid, MIT). JavaSteam = SteamKit2 port. ---
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")     // crypto provider JavaSteam needs
    implementation("com.google.protobuf:protobuf-java:4.31.1") // must match JavaSteam's protobuf
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")    // zstd depot-chunk decompression (arm64 .so)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
