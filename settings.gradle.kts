pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VideoRef"
include(":app")
include(":player")  // v0.6.6 재생기 묶음(따로 재생기 앱으로 뺄 수 있게 분리)