import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videoref.app"
    compileSdk = 36   // v0.6.1: 플레이 새 앱 요건(안드로이드 16)
    defaultConfig {
        // v0.6.0: 패키지 이름 변경(com.videoref.app -> net.fourdir.justdownloader). 플레이에 올리면 다시 못 바꿈
        applicationId = "net.fourdir.justdownloader"
        minSdk = 26
        targetSdk = 36
        versionCode = 70
        versionName = "0.6.2"
        // 개인용: 내 폰(arm64)만. 4개 다 넣으면 APK 가 200MB 넘음
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    // v0.6.0: 두 판. play = 구글 플레이(유튜브·자동 업데이트·후원 버튼 없음), site = 4dir.net·깃허브(전체 기능)
    flavorDimensions += "edition"
    productFlavors {
        create("play") {
            dimension = "edition"
            buildConfigField("boolean", "IS_PLAY", "true")
        }
        create("site") {
            dimension = "edition"
            applicationIdSuffix = ".full"
            buildConfigField("boolean", "IS_PLAY", "false")
        }
    }
    signingConfigs {
        // v0.6.2: 시험판 열쇠는 이 PC 에만(공개 소스에는 없음) — 없으면 안드로이드 기본 시험 열쇠 사용
        if (rootProject.file("debug.keystore").exists()) getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // v0.6.1: 정식 서명 열쇠 — ../_release_key/keystore.properties (비밀번호는 그 파일에만. 없으면 정식 빌드 안 됨)
        val rp = rootProject.file("_release_key/keystore.properties")
        if (rp.exists()) create("release") {
            val pr = Properties().apply { rp.inputStream().use { s -> load(s) } }
            storeFile = rootProject.file("_release_key/" + pr.getProperty("storeFile"))
            storePassword = pr.getProperty("storePassword")
            keyAlias = pr.getProperty("keyAlias")
            keyPassword = pr.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false   // 받기 라이브러리가 내부 클래스를 이름으로 찾음 -> 줄이기 끔
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    // youtubedl-android 는 so 파일을 실제 폴더에 풀어 실행한다 -> 압축 안 된 채 설치 폴더로 꺼내야 함
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

