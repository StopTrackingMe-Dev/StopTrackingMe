plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val qqAppId = "1905345753"
val usageApiBaseUrl = "https://api.stoptracking.me"
val updateManifestUrl = "https://stoptracking.me/latest.json"
val updateMirrorUrl =
    "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/app-release.apk"

android {
    namespace = "app.stoptrackingme"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.stoptrackingme"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.3-alpha"

        manifestPlaceholders["qqAppId"] = qqAppId
        buildConfigField("String", "QQ_APP_ID", "\"$qqAppId\"")
        buildConfigField("String", "USAGE_API_BASE_URL", "\"$usageApiBaseUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "UPDATE_MIRROR_URL", "\"$updateMirrorUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.gson)
    implementation(libs.re2j)
    implementation(libs.wechat.open.sdk)
    implementation(files("libs/open_sdk_3.5.19_r9483ffc7_lite.jar"))
    implementation(libs.jsoup)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
