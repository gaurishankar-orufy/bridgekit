plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    dependencies {
        classpath(libs.build.kotlin.gradle.plugin)   // pins the Kotlin version used by AGP's built-in Kotlin
    }
}

// JitPack serves artifacts as com.github.<user>.<repo>:<module>:<tag> and sets VERSION to the tag.
// Matching that here keeps the POMs (and the BOM's constraints) consistent with what consumers request.
subprojects {
    group = "com.github.gaurishankar-orufy.bridgekit"
    version = System.getenv("VERSION") ?: "0.1.0"
}
