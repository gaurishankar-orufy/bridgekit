plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.bridge.kit.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = true   // android.util.Log in plain JVM tests
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(libs.androidx.lifecycle.runtime)        // LifecycleOwner is in the public API
    api(libs.kotlinx.coroutines.android)        // request / requestAll are suspend
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "bridgekit-core"
            afterEvaluate { from(components["release"]) }
        }
    }
}
