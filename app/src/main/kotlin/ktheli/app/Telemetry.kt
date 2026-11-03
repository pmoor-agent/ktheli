package ktheli.app

import ktheli.physics.model.AircraftModel
import ktheli.physics.sim.Controls

/**
 * Rolling window of per-frame samples for plotting: flight state and sticks for every aircraft, plus the
 * aircraft's own [AircraftModel.gauges]. Display-only; nothing here feeds back into the sim.
 */
class Telemetry(private val windowSeconds: Double = 15.0) {
    class Series(val name: String, internal val read: (Sample) -> Double) {
        internal val values = ArrayDeque<Double>()
        fun toArray() = values.toDoubleArray()
    }

    /** One chart: a title and the series drawn in it. */
    class Group(val title: String, val series: List<Series>)

    class Sample(val aircraft: AircraftModel, val controls: Controls)

    private val times = ArrayDeque<Double>()
    private var aircraft: AircraftModel? = null

    var groups: List<Group> = emptyList()
        private set

    val latestTime: Double get() = times.lastOrNull() ?: 0.0
    val window: Double get() = windowSeconds

    fun record(time: Double, aircraft: AircraftModel, controls: Controls) {
        if (aircraft !== this.aircraft) rebuild(aircraft)
        if (times.isNotEmpty() && time <= times.last()) return // paused
        val sample = Sample(aircraft, controls)
        times.addLast(time)
        groups.forEach { g -> g.series.forEach { it.values.addLast(it.read(sample)) } }
        while (times.first() < time - windowSeconds) {
            times.removeFirst()
            groups.forEach { g -> g.series.forEach { it.values.removeFirst() } }
        }
    }

    private fun rebuild(aircraft: AircraftModel) {
        this.aircraft = aircraft
        times.clear()
        val base = listOf(
            Group(
                "Flight",
                listOf(
                    Series("altitude [m]") { -it.aircraft.body.position.z },
                    Series("climb [m/s]") { -it.aircraft.body.velocity.z },
                    Series("speed [m/s]") { it.aircraft.body.velocity.length() },
                ),
            ),
            Group(
                "Sticks",
                listOf(
                    Series("collective") { it.controls.collective },
                    Series("roll") { it.controls.roll },
                    Series("pitch") { it.controls.pitch },
                    Series("yaw") { it.controls.yaw },
                ),
            ),
        )
        val gauges = aircraft.gauges.groupBy { it.group }.map { (title, gauges) ->
            Group(title, gauges.map { g -> Series(if (g.unit.isEmpty()) g.name else "${g.name} [${g.unit}]") { g.read() } })
        }
        groups = base + gauges
    }

    fun timesArray() = times.toDoubleArray()

    fun clear() {
        aircraft = null
        groups = emptyList()
        times.clear()
    }
}
