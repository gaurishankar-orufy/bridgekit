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
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":bridgekit-core"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.navigation.compose)      // NavGraphBuilder / NavController / NavBackStackEntry are in the public API
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "bridgekit-ui"
            afterEvaluate { from(components["release"]) }
        }
    }
}
