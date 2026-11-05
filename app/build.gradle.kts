import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Bundled audio assets ---------------------------------------------------
// The rain / vinyl mp3 loops are binary, and binaries cannot travel through
// the text-only GitHub push route, so the repo carries them as base64 text
// under assets-b64/ at the project root (one .mp3.b64 file per loop).
// Decode them into src/main/assets at configuration time — on CI's fresh
// checkout they never exist yet, so this always runs there; the decoded
// mp3s are git-ignored.
val assetsB64Dir = rootProject.file("assets-b64")
if (assetsB64Dir.isDirectory) {
    val grouped = assetsB64Dir.listFiles()
        ?.filter { it.isFile && it.name.contains(".b64") }
        ?.groupBy { it.name.substringBefore(".b64") }
        ?: emptyMap()
    grouped.forEach { (base, parts) ->
        val target = file("src/main/assets/$base")
        if (!target.exists()) {
            val joined = parts.sortedBy { it.name }.joinToString("") { it.readText() }
            target.parentFile.mkdirs()
            target.writeBytes(Base64.getMimeDecoder().decode(joined))
        }
    }
}

// --- Permanent CI debug signing -------------------------------------------
// GitHub-hosted runners are ephemeral: without this, every build would get a
// fresh debug key, so a new APK could never install over the previous one.
// The repo carries Lomix's own debug keystore as base64 (debug-keystore.b64
// at the project root — the standard public debug credentials, not a
// secret). Decode it once per build and sign the debug variant with it, so
// the signature is stable forever and updates install on top.
// Stable SHA-1: 45:0E:61:FD:CA:5F:9F:0A:E1:33:89:11:A3:E6:50:74:B3:42:87:DD
val ciDebugKeystore: File? = run {
    val b64 = rootProject.file("debug-keystore.b64")
    if (!b64.exists()) return@run null
    val out = layout.buildDirectory.file("ci-debug.keystore").get().asFile
    if (!out.exists()) {
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(b64.readText()))
    }
    out
}

android {
    namespace = "app.lomix.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.lomix.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        if (ciDebugKeystore != null) {
            create("ciDebug") {
                storeFile = ciDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (ciDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
