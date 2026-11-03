package ktheli.app

import ktheli.physics.heli.HelicopterSpec
import ktheli.physics.math.Vec3
import ktheli.physics.model.AircraftModel
import ktheli.physics.model.ToyHeli

/**
 * A flyable model: what it's called, how it's simulated (a [HelicopterSpec], i.e. airframe, rotor model,
 * flight controller and gyro setup, radio model memory) and how it looks ([HelicopterVisual]).
 * Adding a model is adding an entry to [HelicopterModels].
 */
class HelicopterModel(
    val id: String,
    val name: String,
    val description: String,
    val visual: HelicopterVisual,
    val spec: HelicopterSpec?,
    private val factory: (start: Vec3, seed: Long) -> AircraftModel,
) {
    /** A fresh aircraft parked on its skids at [start] (world NED; z ignored). */
    fun create(start: Vec3, seed: Long = 1): AircraftModel = factory(start, seed)

    override fun toString() = id

    companion object {
        fun of(id: String, name: String, description: String, spec: HelicopterSpec, visual: HelicopterVisual) =
            HelicopterModel(id, name, description, visual, spec) { start, seed -> spec.build(spec.parkedAt(start), seed) }
    }
}

object HelicopterModels {
    private const val TREX_OBJ = "models/trex450/trex450.obj"

    val TREX_450 = HelicopterModel.of(
        id = "trex450",
        name = "Align T-Rex 450",
        description = "450 class FBL on 3S, blade element rotor",
        spec = HelicopterSpec.TREX_450,
        visual = HelicopterVisual.Obj.forSpec(HelicopterSpec.TREX_450, TREX_OBJ, "trex450_body", "rotor_blur_mat"),
    )

    val TREX_450_DISC = HelicopterModel.of(
        id = "trex450-disc",
        name = "Align T-Rex 450 (disc rotor)",
        description = "Same airframe with the simpler Phase 2 disc rotor",
        spec = HelicopterSpec.TREX_450_DISC,
        visual = HelicopterVisual.Obj.forSpec(HelicopterSpec.TREX_450_DISC, TREX_OBJ, "trex450_body", "rotor_blur_mat"),
    )

    val TOY = HelicopterModel(
        id = "toy",
        name = "Toy heli",
        description = "Ideal rate controller, no rotor physics",
        visual = HelicopterVisual.Procedural(
            rotorRadius = 0.36,
            skidDrop = ToyHeli.SKID_DROP,
            mainHubHeight = 0.17,
            tailHub = Vec3(-0.58, 0.03, -0.06),
            tailRadius = 0.075,
        ),
        spec = null,
    ) { start, _ -> ToyHeli(ToyHeli.parkedAt(start)) }

    val ALL = listOf(TREX_450, TREX_450_DISC, TOY)
    val DEFAULT = TREX_450

    /** Settings written before models existed stored aircraft type names. */
    private val LEGACY_IDS = mapOf("HELI_450" to TREX_450, "HELI_450_DISC" to TREX_450_DISC, "TOY" to TOY)

    fun byId(id: String): HelicopterModel? = ALL.firstOrNull { it.id == id } ?: LEGACY_IDS[id]
}
