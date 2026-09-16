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

// Short commit hash of the working tree, with a "+" appended when there are uncommitted changes.
// versionName alone cannot identify a build: 1.4.7 and 1.4.7v4 both reported as "1.4.7 (147)" in
// bug reports, so we could not tell which build a crash came from. This is computed at build time
// and never has to be remembered. Falls back to "unknown" outside a git checkout or without git,
// so the build never depends on it.
val gitBuildId: String = try {
    val hash = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim()
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim().isNotEmpty()
    if (hash.isEmpty()) "unknown" else hash + if (dirty) "+" else ""
} catch (e: Exception) {
    "unknown"
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
        versionCode = 150
        versionName = "1.5.0"

        buildConfigField("String", "GIT_BUILD_ID", "\"$gitBuildId\"")

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
                // Package libc++_shared.so: the Mach-O loader (macho_loader.c) resolves the C++
                // runtime imports of the game's macOS dylibs against it at run time.
                arguments += "-DANDROID_STL=c++_shared"
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

// Plain JVM code: runs under the embedded HotSpot, not Android ART/D8.
val compileServerBootstrap by tasks.registering(JavaCompile::class) {
    source("src/serverBootstrap/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("serverBootstrap/classes"))
    options.release.set(8)
}
val serverBootstrapJar by tasks.registering(Jar::class) {
    dependsOn(compileServerBootstrap)
    from(compileServerBootstrap.flatMap { it.destinationDirectory })
    archiveFileName.set("server-bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/serverBootstrapAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(serverBootstrapJar.map { it.destinationDirectory })
// Asset merging AND the release lint model (lintVital reads the asset dirs too - the release
// build failed on exactly that, 2026-09-13).
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(serverBootstrapJar) }

val coopAgentDependency by tasks.registering(Copy::class) {
    from(tarTree(file("src/main/assets/bundles/jars.tar"))) { include("**/zomdroid-agent.jar") }
    into(layout.buildDirectory.dir("coopAgent/dependencies"))
}
val compileCoopAgent by tasks.registering(JavaCompile::class) {
    dependsOn(coopAgentDependency)
    source("src/coopAgent/java")
    classpath = fileTree(layout.buildDirectory.dir("coopAgent/dependencies")) { include("**/*.jar") }
    destinationDirectory.set(layout.buildDirectory.dir("coopAgent/classes"))
    options.release.set(8)
}
val coopAgentJar by tasks.registering(Jar::class) {
    dependsOn(compileCoopAgent)
    from(compileCoopAgent.flatMap { it.destinationDirectory })
    manifest.attributes["Premain-Class"] = "com.zomdroid.coop.CoopAgent"
    archiveFileName.set("coop-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/coopAgentAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(coopAgentJar.map { it.destinationDirectory })
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(coopAgentJar) }
val compileCoopAgentTest by tasks.registering(JavaCompile::class) {
    dependsOn(compileCoopAgent)
    source("src/coopAgentTest/java")
    classpath = files(compileCoopAgent.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("coopAgent/testClasses"))
    options.release.set(8)
}
tasks.register<JavaExec>("testCoopAgent") {
    dependsOn(compileCoopAgentTest, coopAgentJar)
    classpath = files(compileCoopAgentTest.flatMap { it.destinationDirectory }, coopAgentJar.flatMap { it.archiveFile }) +
            fileTree(layout.buildDirectory.dir("coopAgent/dependencies")) { include("**/*.jar") }
    mainClass.set("com.zomdroid.coop.BridgeTest")
    jvmArgs("-javaagent:" + coopAgentJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<JavaExec>("testCoopInternet") {
    dependsOn(compileCoopAgentTest, coopAgentJar)
    classpath = files(compileCoopAgentTest.flatMap { it.destinationDirectory }, coopAgentJar.flatMap { it.archiveFile }) +
            fileTree(layout.buildDirectory.dir("coopAgent/dependencies")) { include("**/*.jar") }
    mainClass.set("com.zomdroid.coop.InternetStatusTest")
    jvmArgs("-Dzomdroid.server.upnpGuard=true",
            "-javaagent:" + coopAgentJar.get().archiveFile.get().asFile.absolutePath + "=server-upnp")
}
val compileServerBootstrapTest by tasks.registering(JavaCompile::class) {
    dependsOn(compileServerBootstrap)
    source("src/serverBootstrapTest/java")
    classpath = files(compileServerBootstrap.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("serverBootstrap/testClasses"))
    options.release.set(8)
}
tasks.register<JavaExec>("testServerBootstrap") {
    dependsOn(compileServerBootstrapTest)
    classpath = files(compileServerBootstrap.flatMap { it.destinationDirectory },
        compileServerBootstrapTest.flatMap { it.destinationDirectory })
    mainClass.set("com.zomdroid.server.BootstrapTest")
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
