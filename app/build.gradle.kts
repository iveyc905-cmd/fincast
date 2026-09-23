plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.ember.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ember.tv"
        minSdk = 21
        targetSdk = 34          // 34 keeps foreground-service rules simpler on TV
        // CI numbers each build so every published APK is a clean upgrade.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.4." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    // The release key comes from CI (a GitHub secret decoded to a file). Without
    // it, release builds fall back to the debug key so local builds still work,
    // but such an APK cannot update one installed from the published link.
    val keystorePath = System.getenv("EMBER_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("ember") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = "ember-release"
                keyAlias = "ember"
                keyPassword = "ember-release"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 matters for more than size: Compose runs noticeably smoother
            // once optimised, which is most of what makes a phone UI feel good.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ember") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.appcompat)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)

    testImplementation("junit:junit:4.13.2")
}
