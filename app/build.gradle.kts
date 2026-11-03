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
    implementation(libs.imgui.binding)
    implementation(libs.imgui.lwjgl3) {
        exclude(group = "org.lwjgl", module = "lwjgl-sdl") // we use the GLFW backend only
    }
    runtimeOnly(libs.imgui.natives.linux)
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
