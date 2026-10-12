plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.invictus.kodex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.invictus.kodex"
        minSdk = 30
        targetSdk = 36
        // CI passes these on tag builds (v1.2.3 -> name 1.2.3, code derived from the tag
        // by .github/scripts/version-from-tag.sh so pre-releases always sort below the stable).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1010099 // 1.1.0 stable (see version-from-tag.sh)
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 code shrinking + obfuscation. Keep rules live in proguard-rules.pro
            // (JGit, Sora/tm4e, Room, WebView). Resource shrinking stays off for now.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signingConfig here on purpose: this build type produces an
            // unsigned APK (app-release-unsigned.apk). Signing is done
            // explicitly with apksigner (.github/scripts/sign-apks.sh, called from the
            // build / release workflows) or manually for local release testing), keeping build and
            // sign as separate, visible steps.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // One APK per ABI (arm64-v8a, armeabi-v7a; each contains only that ABI's native libs)
    // + a universal APK. x86 / x86_64 are intentionally not built.
    // Only for release builds so local debug installs stay a single APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Sora/tm4e pull in several jars that each ship these; harmless to drop.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // M5 preview: WebViewAssetLoader (markdown/HTML), Coil (images), AndroidSVG (svg -> Picture).
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.okhttp) // GitHub profile fetch + avatar cache interceptor (same version Coil 2.7 uses)
    implementation(libs.androidsvg)

    // M6 git: JGit (pure-Java Git) + silent SLF4J provider (JGit's log chatter is
    // dropped; real errors surface through GitErrorDetails dialogs).
    implementation(libs.org.eclipse.jgit)
    implementation(libs.slf4j.nop)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.compose.material.symbols)
    implementation(libs.compose.material.symbols.rounded.filled)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests for pure logic (M15). org.json: android.jar only ships stubs on the JVM.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
