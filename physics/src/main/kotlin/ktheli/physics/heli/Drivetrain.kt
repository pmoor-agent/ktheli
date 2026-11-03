package ktheli.physics.heli

import ktheli.physics.math.DMath

/**
 * Battery, ESC with headspeed governor, and brushless motor driving the main rotor through a one-way
 * (autorotation) bearing: the motor can only push the rotor, never brake it.
 *
 * Electrical model: battery `V = Voc(soc) − I·R`, motor terminal voltage `duty·V`, motor current
 * `(duty·V − ω/Kv)/R`, torque `Kt·(I − I0)`.
 */
class Drivetrain(
    private val motor: MotorParams,
    private val battery: BatteryParams,
    private val esc: EscParams,
) {
    private val kvRad = motor.kv * DMath.TWO_PI / 60.0 // rad/s per volt
    private val kt = 1.0 / kvRad // N·m per amp
    private val maxRotorSpeed = esc.governorMaxRpm * DMath.TWO_PI / 60.0

    var stateOfCharge = 1.0
        private set
    var duty = 0.0
        private set
    var motorCurrent = 0.0
        private set
    var batteryVoltage = openCircuitVoltage()
        private set

    /** Current governor target (soft-started), rad/s of main rotor. */
    var governorTarget = 0.0
        private set
    private var governorIntegral = 0.0
    private var engaged = false
    private var bailout = false

    data class Output(val rotorTorque: Double, val electricalPower: Double)

    fun openCircuitVoltage() = battery.cells * (battery.cellEmpty + (battery.cellFull - battery.cellEmpty) * stateOfCharge)

    /** Advances one tick; [throttle] is the ESC input in [-1, 1], [rotorSpeed] the main rotor's rad/s. */
    fun step(throttle: Double, rotorSpeed: Double, dt: Double): Output {
        val fraction = (throttle + 1.0) / 2.0
        updateGovernor(fraction, rotorSpeed, dt)

        val voc = openCircuitVoltage()
        val backEmf = rotorSpeed * motor.gearRatio / kvRad
        // Solve battery sag and motor current together: V = Voc − R_b·duty·I, I = (duty·V − E)/R_m.
        val rb = battery.internalResistance
        val rm = motor.resistance
        var voltage = (voc + rb * duty * backEmf / rm) / (1.0 + rb * duty * duty / rm)
        var current = (duty * voltage - backEmf) / rm
        if (current <= motor.noLoadCurrent) { // one-way bearing freewheels: motor unloaded, rotor coasting
            current = 0.0
            voltage = voc
        }
        motorCurrent = current
        batteryVoltage = voltage
        val batteryCurrent = duty * current
        stateOfCharge = (stateOfCharge - batteryCurrent * dt / (battery.capacityAh * 3600.0)).coerceAtLeast(0.0)

        val rotorTorque = if (current > 0.0) kt * (current - motor.noLoadCurrent) * motor.gearRatio else 0.0
        return Output(rotorTorque, voltage * batteryCurrent)
    }

    private fun updateGovernor(fraction: Double, rotorSpeed: Double, dt: Double) {
        if (fraction < esc.offBelow) {
            engaged = false
            governorTarget = 0.0
            governorIntegral = 0.0
            duty = 0.0
            return
        }
        val requested = fraction * maxRotorSpeed
        if (!engaged) {
            // Engaging: soft start from the current headspeed. If the rotor is still spinning well (throttle
            // hold released during an autorotation), "bailout" ramps up much faster.
            engaged = true
            bailout = rotorSpeed > 0.5 * requested
            governorTarget = rotorSpeed
        }
        if (governorTarget >= requested) bailout = false
        val rampSeconds = if (bailout) esc.bailoutSeconds else esc.spoolUpSeconds
        val maxStep = maxRotorSpeed / rampSeconds * dt
        governorTarget += (requested - governorTarget).coerceIn(-maxStep, maxStep)

        val error = governorTarget - rotorSpeed
        val feedForward = governorTarget * motor.gearRatio / kvRad / openCircuitVoltage()
        val unclamped = feedForward + esc.governorP * error + esc.governorI * governorIntegral
        // Anti-windup: only integrate while the output isn't saturated in the error's direction.
        if (!(unclamped >= 1.0 && error > 0.0) && !(unclamped <= 0.0 && error < 0.0)) governorIntegral += error * dt
        duty = (feedForward + esc.governorP * error + esc.governorI * governorIntegral).coerceIn(0.0, 1.0)
    }
}
