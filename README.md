# ktheli

An RC helicopter simulator in Kotlin, focused on realistic rotor physics rather than graphics.

```
./gradlew :app:run     # fly (transmitter dongle, gamepad, or keyboard fallback)
./gradlew check        # tests + determinism lint
```

## Modules

| Module    | Contents | Depends on |
|-----------|----------|------------|
| `physics` | Math, rigid body, aircraft models, fixed-step simulation. Pure Kotlin, no I/O. | stdlib only |
| `input`   | Axis calibration, channel mapping, device profiles (JSON). No device access. | `physics` |
| `app`     | libGDX rendering, GLFW joystick polling, HUD, wall-clock → tick bridge. | both |

## Determinism contract (`physics`)

Given the same initial state, seed, and per-tick `Controls`, the simulation produces bit-identical state
on every run and every platform. To keep it that way:

- **Fixed timestep** (`Simulation.DEFAULT_TICK_RATE_HZ`). Time is `tick / rate`, never accumulated.
- **`DMath` (StrictMath) for transcendentals.** `kotlin.math`/`java.lang.Math` `sin`, `exp`, `pow`, … may
  differ by 1 ulp across CPUs. `+ - * /` and `sqrt` are exact IEEE and fine to use directly.
- **`Pcg32` for randomness**, explicitly seeded; its state is part of the simulation state.
- **No wall clock, threads, or I/O** inside the physics step.
- `controls` is the only input; recording one per tick is a complete replay.

`./gradlew :physics:checkDeterminism` (part of `check`) rejects forbidden APIs, and `DeterminismTest`
pins a golden state hash. When you change the physics intentionally, update `GOLDEN`.

## Conventions

World frame NED (x north, y east, z down), body frame FRD (x forward, y right, z down), SI units.
Attitude quaternions rotate body → world. The renderer converts to libGDX's Y-up only at draw time
(`Vec3.toGdx()`). See `physics/.../Frames.kt`.

## User interface

The window has a menu bar (Dear ImGui via [imgui-java](https://github.com/SpaiR/imgui-java)) above the 3D view:

- **Sim**: pause/resume, reset, quit
- **Aircraft**: model selection (toy heli for now)
- **Input**: *Controls...* and device selection
- **View**: HUD, *Telemetry* (live ImPlot charts), camera auto-zoom, screenshot
- **Help**: controls, ImGui demo window, about

Menu shortcuts always show the current key bindings.

### Controls dialog

- **Sticks**: assign an axis to each flight channel (or clear it), reverse/centered toggles, live outputs, and
  calibration. Calibration first learns each stick's resting jitter (keep sticks centered for 1.5 s); that
  neutral band reads as exactly 0 and the remaining travel is rescaled to the full range. Then sweep all extremes.
- **Buttons & keys**: bind every action to a keyboard key (with modifiers) and/or a controller button
  (D-pad directions count as buttons). Digital flight actions drive channels that have no axis assigned;
  collective up/down integrates, so e.g. two buttons can replace a spring-loaded collective stick.
- **Monitor**: live raw axes (with learned neutral band) and buttons of the selected device.

Window layout persists in `~/.config/ktheli/imgui.ini`; bindings and calibration in
`~/.config/ktheli/input.json` (controller settings per device GUID).

### Default keys

| Key | Action |
|-----|--------|
| `W` / `S` | Collective up / down (`K` returns it to center) |
| arrows | Cyclic |
| `A` / `D` | Yaw |
| `P` / `R` | Pause / reset |
| `F1` / `F2` / `F3` | Controls dialog / telemetry / HUD |
| `Z` | Camera auto-zoom |
| `F12` | Save a screenshot to the working directory |
| `Tab` | Next joystick |
| `Ctrl+Q` | Quit |
| `Esc` | Cancel calibration / assignment / capture (not rebindable) |

## Testing the UI

The UI runs headlessly in tests: no window, no GPU, no display needed. `app/src/test/.../harness/UiDriver`
drives the real `AppCore` (simulation, input, ImGui UI) at a fixed 1/60 s per frame:

```kotlin
@Test
fun `rebinding a key`() = uiTest { ui ->
    ui.menu("Input", "Controls...")                 // click through menus
    ui.click("Controls/Buttons & keys")             // widgets are found by path
    ui.click("Controls/Buttons & keys/Pause / resume/key")
    ui.press(Keys.F5)                               // keyboard (with optional modifiers)
    assertEquals(KeyChord(Keys.F5), ui.core.input.keyFor(Action.PAUSE))
    ui.joystick.axes[2] = 0.5f; ui.frame()          // scripted joystick axes and buttons
    ui.snapshot(Paths.get("build/ui-snapshots/x.png")) // CPU-rendered UI screenshot
}
```

- Widget paths come from `UiTrace`, fed by the `Gui` wrappers around ImGui calls: window, menu, tab, combo or
  row names joined with `/`, ending in the widget's visible label (or explicit trace name). `ui.dump()` lists
  every clickable path of the current frame; `ui.texts()` / `waitForText()` see the visible text.
- Mouse clicks go through ImGui's IO; keys go through the app's own key handling.
- `UiSnapshotTest` renders a tour of the UI to `app/build/ui-snapshots/` with a small software rasterizer
  (the 3D scene isn't drawn there).
- Use `Gui.*` instead of `ImGui.*` for new widgets and texts so tests can find them.

### Development hooks (environment variables)

- `KTHELI_SCREENSHOT=out.png` (+ optional `KTHELI_SCREENSHOT_AFTER=seconds`, default 2): capture one frame, then exit.
- `KTHELI_SHOW=controls[:sticks|buttons|monitor],telemetry`: open those windows (and tab) at startup.
- `KTHELI_FAKE_JOYSTICK=1`: add a synthetic joystick with sweeping axes and blinking buttons, for working on input UI
  without hardware.
  Combine with `XDG_CONFIG_HOME=$(mktemp -d)` to keep it out of your real input profiles.
- `KTHELI_NO_VSYNC=1`: disable vsync (capped at 240 fps), for compositors that throttle vsync'd windows.

## Roadmap

0. ✅ Skeleton, input diagnostics/calibration, deterministic core
1. ✅ Toy heli (thrust ∝ collective, ideal rate controller) — placeholder
2. Momentum-theory rotor, rotor RPM dynamics, motor + governor, tail rotor, heading-hold gyro
3. Blade-element rotor (360° airfoil tables), flapping, dynamic inflow, ground effect
4. FBL controller, swashplate mixing, TX curves/expo/rates, flight modes, throttle hold → autorotation
5. Wind, Dryden turbulence, gusts, vortex ring state, fuselage/fin aero
6. Sound, replays, telemetry plots, crash physics, multiple airframes
