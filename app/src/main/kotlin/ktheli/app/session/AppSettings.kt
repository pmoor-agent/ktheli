package ktheli.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ktheli.app.sound.SoundSettings
import ktheli.physics.env.AtmosphereSettings
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.env.WindSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Simulator settings that persist between runs (`~/.config/ktheli/settings.json`). */
@Serializable
data class AppSettings(
    /** [HelicopterModel.id] of the selected model. */
    val aircraft: String = HelicopterModels.DEFAULT.id,
    val environment: EnvironmentDto = EnvironmentDto(),
    val camera: CameraSettings = CameraSettings(),
    /** [FlyingField.id] of the selected field. */
    val field: String = FlyingFields.DEFAULT.id,
    val sound: SoundSettings = SoundSettings(),
    val crash: CrashSettings = CrashSettings(),
) {
    val model: HelicopterModel get() = HelicopterModels.byId(aircraft) ?: HelicopterModels.DEFAULT
    val flyingField: FlyingField get() = FlyingFields.byId(this.field) ?: FlyingFields.DEFAULT
}

/** What happens after a crash: reset automatically after [delaySeconds], or wait for the reset key. */
@Serializable
data class CrashSettings(val autoReset: Boolean = true, val delaySeconds: Double = 3.0)

/** Serializable mirror of [EnvironmentSettings] (the physics module stays free of serialization). */
@Serializable
data class EnvironmentDto(
    val windSpeed: Double = 0.0,
    val windFrom: Double = 270.0,
    val shearExponent: Double = 0.15,
    val turbulence: Double = 1.0,
    val gustiness: Double = 0.0,
    val temperatureC: Double = 15.0,
    val elevation: Double = 0.0,
) {
    fun toSettings() = EnvironmentSettings(
        WindSettings(windSpeed, windFrom, shearExponent, turbulence, gustiness),
        AtmosphereSettings(temperatureC, elevation),
    )

    companion object {
        fun of(s: EnvironmentSettings) = EnvironmentDto(
            s.wind.speed, s.wind.fromDegrees, s.wind.shearExponent, s.wind.turbulence, s.wind.gustiness,
            s.atmosphere.temperatureC, s.atmosphere.elevation,
        )
    }
}

/** Where the simulator keeps its files: `$XDG_CONFIG_HOME/ktheli`, defaulting to `~/.config/ktheli`. */
object ConfigPaths {
    val dir: Path
        get() {
            val base = System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("ktheli")
        }

    val input: Path get() = dir.resolve("input.json")
    val settings: Path get() = dir.resolve("settings.json")
    val imguiIni: Path get() = dir.resolve("imgui.ini")
}

class SettingsStore(private val path: Path = ConfigPaths.settings) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppSettings = runCatching {
        if (Files.exists(path)) json.decodeFromString(AppSettings.serializer(), Files.readString(path)) else AppSettings()
    }.getOrElse { AppSettings() }

    fun save(settings: AppSettings) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(AppSettings.serializer(), settings))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
