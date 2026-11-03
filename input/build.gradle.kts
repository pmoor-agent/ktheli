plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure input logic: calibration, channel mapping, profiles. Device access (GLFW) lives in :app.
dependencies {
    api(project(":physics"))
    implementation(libs.serialization.json)
}
