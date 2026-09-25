// The version comes from the git tag in CI (-PmuzikaVersion=1.2.3). Android
// refuses an update whose versionCode is not strictly higher than the
// installed one, so it is derived from the same string rather than being a
// hand-maintained number that is guaranteed to be forgotten.
val muzikaVersionName: String = (findProperty("muzikaVersion") as String?) ?: "1.0.0"
val muzikaVersionCode: Int = muzikaVersionName
    .substringBefore('-')          // tolerate 1.2.3-beta
    .split('.')
    .let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 10_000 + minor * 100 + patch
    }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "lt.a777.muzika"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.a777.muzika"
        minSdk = 26
        targetSdk = 35
        versionCode = muzikaVersionCode
        versionName = muzikaVersionName
    }

    // Release builds are signed from repo secrets in CI. Locally, and for
    // anyone building a fork, the block is simply absent and Gradle falls back
    // to an unsigned release APK - nothing here depends on a private file
    // existing.
    signingConfigs {
        create("release") {
            val store = System.getenv("MUZIKA_KEYSTORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = System.getenv("MUZIKA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MUZIKA_KEY_ALIAS")
                keyPassword = System.getenv("MUZIKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the real resources to stand up a Context.
            isIncludeAndroidResources = true
        }
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20240303")
}
