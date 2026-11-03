plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":physics"))
    implementation(project(":input"))
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") useVersion(libs.versions.lwjgl.get())
    }
}

application {
    mainClass = "ktheli.app.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}
