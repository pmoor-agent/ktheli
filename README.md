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
- **Input**: *Input setup…* (device, channel assignment, reverse/centered, calibration, raw axes) and device selection
- **View**: HUD, *Telemetry* (live ImPlot charts), camera auto-zoom, screenshot
- **Help**: keyboard reference, ImGui demo window, about

Window layout persists in `~/.config/ktheli/imgui.ini`; input profiles are stored per device GUID in
`~/.config/ktheli/input.json`.

### Keyboard

| Key | Action |
|-----|--------|
| `P` / `R` | Pause / reset |
| `F1` / `F2` | Input setup / telemetry window |
| `F12` | Save a screenshot to the working directory |
| `C` | Start / finish calibration (center sticks first, then sweep all extremes) |
| `1`–`4` | Assign collective / roll / pitch / yaw: press, then move that stick in the positive direction |
| `Tab` | Next joystick |
| `Esc` | Cancel calibration / assignment |
| `W/S`, `A/D`, arrows | Keyboard fallback when no joystick is connected (`K` zeroes collective) |
| `Ctrl+Q` | Quit |

### Development hooks (environment variables)

- `KTHELI_SCREENSHOT=out.png` (+ optional `KTHELI_SCREENSHOT_AFTER=seconds`, default 2): capture one frame, then exit.
- `KTHELI_SHOW=input,telemetry`: open those windows at startup.
- `KTHELI_FAKE_JOYSTICK=1`: add a synthetic joystick with sweeping axes, for working on input UI without hardware.
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
