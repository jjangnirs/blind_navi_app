plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.safecross.mobile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "kr.safecross.mobile"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val envFile = rootProject.file("../.env")
        var tmapAppKey = System.getenv("TMAP_APP_KEY") ?: ""
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("TMAP_APP_KEY=")) {
                    val key = trimmed.substringAfter("TMAP_APP_KEY=").trim()
                    if (key.isNotEmpty()) {
                        tmapAppKey = key
                    }
                }
            }
        }
        buildConfigField("String", "TMAP_APP_KEY", "\"$tmapAppKey\"")

        // ARCore Geospatial(VPS) 인증 키 (ADR-0037): 루트 .env 의 ARCORE_API_KEY 또는 환경변수
        var arcoreApiKey = System.getenv("ARCORE_API_KEY") ?: ""
        if (envFile.exists()) {
            envFile.readLines().map { it.trim() }
                .firstOrNull { it.startsWith("ARCORE_API_KEY=") }
                ?.substringAfter("ARCORE_API_KEY=")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { arcoreApiKey = it }
        }
        manifestPlaceholders["arcoreApiKey"] = arcoreApiKey
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // CameraX dependencies (프롬프트 10)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // LiteRT / TFLite On-Device ML (프롬프트 11)
    implementation(libs.tflite)

    // ARCore Geospatial(VPS) 정밀 방향 (ADR-0037). Geospatial은 Play 서비스 위치 라이브러리가 필요하다.
    // play-services-location 21.4.0은 Kotlin 2.3 메타데이터를 요구하므로 프로젝트 Kotlin(2.0.21)과 호환되는 21.3.0 사용
    implementation("com.google.ar:core:1.56.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // OpenCV Android (Computer Vision Pipeline)
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)

}
