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

/** ImGui's built-in font only covers ASCII; anything else renders as '?'. */
val checkUiAscii = tasks.register("checkUiAscii") {
    group = "verification"
    description = "Fails on non-ASCII characters in string literals of the app module."
    val sources = fileTree("src/main/kotlin")
    val root = projectDir
    inputs.files(sources)
    doLast {
        val literal = Regex(""""(?:[^"\\]|\\.)*"""")
        val violations = sources.files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") &&
                    literal.findAll(line).any { m -> m.value.any { it.code > 127 } }
            }.map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
        }
        if (violations.isNotEmpty()) throw GradleException("Non-ASCII UI strings:\n" + violations.joinToString("\n"))
    }
}

tasks.named("check") { dependsOn(checkUiAscii) }
