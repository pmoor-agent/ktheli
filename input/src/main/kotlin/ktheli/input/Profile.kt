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

/** Everything needed to turn one device's raw axes into [Controls]. Keyed by the device GUID. */
@Serializable
data class DeviceProfile(
    val guid: String,
    val name: String,
    val calibration: List<AxisCalibration> = emptyList(),
    val bindings: Map<Channel, ChannelBinding> = emptyMap(),
) {
    fun read(channel: Channel, axes: FloatArray): Double {
        val b = bindings[channel] ?: return 0.0
        if (b.axis >= axes.size) return 0.0
        val cal = calibration.getOrElse(b.axis) { AxisCalibration() }
        val v = cal.normalize(axes[b.axis], b.centered)
        return if (b.reversed) -v else v
    }

    fun toControls(axes: FloatArray) = Controls(
        collective = read(Channel.COLLECTIVE, axes),
        roll = read(Channel.ROLL, axes),
        pitch = read(Channel.PITCH, axes),
        yaw = read(Channel.YAW, axes),
    )

    fun withBinding(channel: Channel, detection: AxisDetector.Detection) = copy(
        bindings = bindings + (channel to ChannelBinding(detection.axis, detection.reversed, channel.centeredByDefault)),
    )

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

@Serializable
data class InputConfig(val devices: Map<String, DeviceProfile> = emptyMap(), val activeGuid: String? = null)

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
