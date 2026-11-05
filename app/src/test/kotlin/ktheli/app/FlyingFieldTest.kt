package ktheli.app

import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import ktheli.app.session.FlyingFields
import ktheli.app.session.TreeLine
import ktheli.physics.math.Vec3
import ktheli.physics.sim.StateHash
import java.nio.file.Files
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlyingFieldTest {
    /**
     * Obstacles are a simulation input: a replay needs exactly the same trees, on any machine. Their geometry is
     * built with Pcg32 and StrictMath, so this hash is the same everywhere; it only changes when a field's layout
     * is changed on purpose (update it then, knowing old recordings won't replay).
     */
    @Test
    fun `obstacle geometry is bit-reproducible`() {
        val hash = StateHash()
        for (field in FlyingFields.ALL) {
            for (o in field.obstacles.all) hash.add(o.x).add(o.y).add(o.radius).add(o.bottom).add(o.top)
        }
        assertEquals(PINNED_OBSTACLE_HASH, hash.value, "obstacle hash 0x%016x".format(hash.value))
    }

    @Test
    fun `fields have unique ids, the home field has its pad at the origin and starts the model on it`() {
        assertEquals(FlyingFields.ALL.size, FlyingFields.ALL.map { it.id }.toSet().size)
        val home = FlyingFields.HOME
        assertEquals(Vec3.ZERO, home.start)
        assertEquals(Vec3.ZERO, home.pads.single().center)
        assertEquals(1.0, home.pads.single().radius) // 2 m across
        assertEquals(6.0, hypot(home.pilotEye.x - home.start.x, home.pilotEye.y - home.start.y), 1e-9) // pilot 6 m away
        assertEquals(-1.7, home.pilotEye.z)
    }

    @Test
    fun `trees ring the field 200 m out, 10 to 20 m tall, placed the same every time`() {
        val line = FlyingFields.HOME.trees!!
        val trees = line.trees()
        println("tree line: %d trees, %d conifers".format(trees.size, trees.count { it.kind == TreeLine.Kind.CONIFER }))
        assertTrue(trees.size in 600..900, "a dense tree line")
        for (t in trees) {
            assertTrue(hypot(t.position.x, t.position.y) in line.radius..line.radius + line.depth)
            assertTrue(t.height in 10.0..20.0)
            assertEquals(0.0, t.position.z)
        }
        assertTrue(trees.count { it.kind == TreeLine.Kind.CONIFER } in trees.size / 4..3 * trees.size / 4, "a mix of kinds")
        assertEquals(trees, line.trees())
        // All around: every 30° sector has trees.
        val sectors = trees.map { ((Math.toDegrees(kotlin.math.atan2(it.position.y, it.position.x)) + 360) % 360 / 30).toInt() }.toSet()
        assertEquals((0 until 12).toSet(), sectors)
    }

    @Test
    fun `the field menu switches fields and the model starts at the new field's start`() = uiTest { ui ->
        assertEquals(FlyingFields.DEFAULT, ui.core.session.runner.field)
        ui.click("Field")
        for (field in FlyingFields.ALL) ui.waitFor("Field/${field.name}")
        ui.click("Field/${FlyingFields.OPEN.name}") // the menu is already open
        assertEquals(FlyingFields.OPEN, ui.core.session.runner.field)
        val p = ui.core.session.runner.sim.aircraft.body.position
        assertEquals(FlyingFields.OPEN.start.x, p.x, 0.01) // skids settle a little
        assertEquals(FlyingFields.OPEN.start.y, p.y, 0.01)
    }

    @Test
    fun `the field choice persists`() {
        val dir = Files.createTempDirectory("ktheli-field")
        try {
            UiDriver(configDir = dir).use { ui -> ui.menu("Field", FlyingFields.OPEN.name) }
            UiDriver(configDir = dir).use { ui -> assertEquals(FlyingFields.OPEN, ui.core.session.runner.field) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PINNED_OBSTACLE_HASH = 0x2e7bae60ac6e4c40L
    }
}
