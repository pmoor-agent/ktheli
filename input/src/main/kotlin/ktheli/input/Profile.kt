package ktheli.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ktheli.physics.sim.Controls
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Pilot functions. The direction in [prompt] is the one that must produce a positive value. */
enum class Channel(val prompt: String, val centeredByDefault: Boolean) {
    COLLECTIVE("Move COLLECTIVE (pitch stick) UP", centeredByDefault = false),
    ROLL("Move ROLL (aileron) RIGHT", centeredByDefault = true),
    PITCH("Move PITCH (elevator) FORWARD", centeredByDefault = true),
    YAW("Move YAW (rudder) RIGHT", centeredByDefault = true),
}

@Serializable
data class ChannelBinding(
    val axis: Int,
    val reversed: Boolean = false,
    val centered: Boolean = true,
)

/**
 * Everything needed to turn one device's raw input into [Controls] and [Action]s. Keyed by the device GUID.
 *
 * @property bindings axis assignment per flight channel; unassigned channels fall back to digital input
 * @property buttons controller button index per action
 */
@Serializable
data class DeviceProfile(
    val guid: String,
    val name: String,
    val calibration: List<AxisCalibration> = emptyList(),
    val bindings: Map<Channel, ChannelBinding> = emptyMap(),
    val buttons: Map<Action, Int> = emptyMap(),
) {
    /** Normalized value of [channel], or null if it has no (present) axis assigned. */
    fun read(channel: Channel, axes: FloatArray): Double? {
        val b = bindings[channel] ?: return null
        if (b.axis >= axes.size) return null
        val cal = calibration.getOrElse(b.axis) { AxisCalibration() }
        val v = cal.normalize(axes[b.axis], b.centered)
        return if (b.reversed) -v else v
    }

    /** Axis-driven channels from [axes]; channels without an axis take their value from [fallback]. */
    fun toControls(axes: FloatArray, fallback: Controls = Controls.NEUTRAL) = Controls(
        collective = read(Channel.COLLECTIVE, axes) ?: fallback.collective,
        roll = read(Channel.ROLL, axes) ?: fallback.roll,
        pitch = read(Channel.PITCH, axes) ?: fallback.pitch,
        yaw = read(Channel.YAW, axes) ?: fallback.yaw,
    )

    fun withBinding(channel: Channel, detection: AxisDetector.Detection) = copy(
        bindings = bindings + (channel to ChannelBinding(detection.axis, detection.reversed, channel.centeredByDefault)),
    )

    fun withoutBinding(channel: Channel) = copy(bindings = bindings - channel)

    /** Binds [button] to [action], taking it away from any other action. */
    fun withButton(action: Action, button: Int) = copy(
        buttons = buttons.filterValues { it != button } + (action to button),
    )

    fun withoutButton(action: Action) = copy(buttons = buttons - action)

    /** Mapping Mode 2 (left stick: collective + yaw, right stick: cyclic) as a starting point. */
    companion object {
        fun default(guid: String, name: String) = DeviceProfile(
            guid, name,
            bindings = mapOf(
                Channel.COLLECTIVE to ChannelBinding(1, reversed = true, centered = false),
                Channel.YAW to ChannelBinding(0),
                Channel.ROLL to ChannelBinding(2),
                Channel.PITCH to ChannelBinding(3, reversed = true),
            ),
        )
    }
}

/**
 * @property keyOverrides keyboard bindings that differ from the application's defaults; a null value
 *   means the user cleared that binding.
 */
@Serializable
data class InputConfig(
    val devices: Map<String, DeviceProfile> = emptyMap(),
    val activeGuid: String? = null,
    val keyOverrides: Map<Action, KeyChord?> = emptyMap(),
) {
    fun keyFor(action: Action, defaults: Map<Action, KeyChord>): KeyChord? =
        if (action in keyOverrides) keyOverrides[action] else defaults[action]

    /** Binds [chord] to [action], taking it away from any other action that currently uses it. */
    fun withKey(action: Action, chord: KeyChord, defaults: Map<Action, KeyChord>): InputConfig {
        val displaced = Action.entries.filter { it != action && keyFor(it, defaults) == chord }
        return copy(keyOverrides = keyOverrides + displaced.associateWith { null } + (action to chord))
    }

    fun withoutKey(action: Action) = copy(keyOverrides = keyOverrides + (action to null))

    fun withDefaultKeys() = copy(keyOverrides = emptyMap())
}

class ProfileStore(private val path: Path = defaultPath()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): InputConfig =
        if (Files.exists(path)) json.decodeFromString(InputConfig.serializer(), Files.readString(path)) else InputConfig()

    fun save(config: InputConfig) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(InputConfig.serializer(), config))
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun defaultPath(): Path = configDir().resolve("input.json")

        /** `$XDG_CONFIG_HOME/ktheli`, defaulting to `~/.config/ktheli`. */
        fun configDir(): Path {
            val base = System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("ktheli")
        }
    }
}
