plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    dependencies {
        classpath(libs.build.kotlin.gradle.plugin)   // pins Kotlin for AGP's built-in Kotlin, same as the Web2Native app
    }
}

subprojects {
    group = "com.bridge.kit"
    version = "0.1.0"
}
