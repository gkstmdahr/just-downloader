// 재생기 묶음(player) v0.2.0 — just downloader 와 떨어진 라이브러리 모듈. 앱 코드를 참조하지 않는다.
// 나중에 따로 재생기 앱으로 뺄 때: 새 앱 모듈이 이 모듈을 implementation 하고 PlayerHost 에 기록·문구·변환기만 넣으면 됨.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.fourdir.player"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val media3 = "1.11.1"
dependencies {
    // api: 앱의 자르기 화면도 같은 재생 화면 부품(PlayerView)을 씀
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")   // v0.2.0 뒤에서 재생·잠금화면 조작·작은 창 조작
    implementation("androidx.appcompat:appcompat:1.7.0")
}
