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

android {
    namespace = "app.lomix.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.lomix.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
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
