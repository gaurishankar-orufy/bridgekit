plugins {
    `java-platform`
    `maven-publish`
}

// BOM: one version for every BridgeKit library. No code.
dependencies {
    constraints {
        api(project(":bridgekit-core"))
        api(project(":bridgekit-ui"))
    }
}

publishing {
    publications {
        register<MavenPublication>("bom") {
            artifactId = "bridgekit-platform"
            from(components["javaPlatform"])
        }
    }
}
