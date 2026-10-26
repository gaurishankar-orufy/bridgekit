plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.bridge.kit.ui"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.isIncludeAndroidResources = true   // Robolectric Compose tests
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":bridgekit-core"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.navigation.compose)      // NavGraphBuilder / NavController / NavBackStackEntry are in the public API
    testImplementation(libs.junit)
    // Compose UI tests on the JVM (Robolectric); test-only, not part of the published library
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "bridgekit-ui"
            afterEvaluate { from(components["release"]) }
        }
    }
}
