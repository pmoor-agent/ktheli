package ktheli.physics.heli

/**
 * A servo: takes a new command only once per frame of its pulse train ([frameTicks] simulation ticks) and
 * moves towards it at a limited speed.
 */
class Servo(params: ServoParams, tickRateHz: Int, initial: Double = 0.0) {
    val frameTicks: Int = maxOf(1, (tickRateHz / params.frameRateHz + 0.5).toInt())
    private val speed = params.unitsPerSecond

    var position = initial
        private set
    private var target = initial

    fun step(command: Double, tick: Long, dt: Double) {
        if (tick % frameTicks == 0L) target = command.coerceIn(-1.0, 1.0)
        val maxStep = speed * dt
        position += (target - position).coerceIn(-maxStep, maxStep)
    }
}
