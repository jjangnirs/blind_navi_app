plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * ARCore Geospatial(VPS) 현장 정확도 측정용 시험 앱.
 * 메인 앱과 분리된 별도 APK(kr.safecross.vpsprobe)로 빌드한다.
 *
 * ARCore API 키: 루트 .env 의 ARCORE_API_KEY 또는 환경변수 ARCORE_API_KEY
 * (Google Cloud 프로젝트에서 "ARCore API" 사용 설정 후 발급, Android 앱 제한 권장)
 */
fun readArcoreApiKey(): String {
    System.getenv("ARCORE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    val envFile = rootProject.file("../.env")
    if (envFile.exists()) {
        envFile.readLines().map { it.trim() }
            .firstOrNull { it.startsWith("ARCORE_API_KEY=") }
            ?.substringAfter("ARCORE_API_KEY=")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return ""
}

android {
    namespace = "kr.safecross.vpsprobe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "kr.safecross.vpsprobe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        val arcoreKey = readArcoreApiKey()
        manifestPlaceholders["arcoreApiKey"] = arcoreKey
        buildConfigField("boolean", "HAS_ARCORE_API_KEY", (arcoreKey.isNotEmpty()).toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.google.ar:core:1.56.0")
    // ARCore Geospatial은 Google Play 서비스 위치 라이브러리가 앱에 포함되어 있어야 동작한다
    // (미포함 시 AR_ERROR_GOOGLE_PLAY_SERVICES_LOCATION_LIBRARY_NOT_LINKED)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation(libs.junit)
}
