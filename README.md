# CHAOS LAB — The Universe Playground

An Android tablet physics playground. Touch, drag, pinch and fling planets, stars and
black holes, and watch thousands of particles respond to real gravity, live.

**Current milestone: 1 — Gravity Playground (fully working).**
Fully offline. No accounts, no API keys, no paid services.

## What works now

| Feature | Details |
|---|---|
| Real N-body gravity | Bodies attract each other (full N-body); up to 20,000 particles attracted by every body. Symplectic integrator, adaptive sub-stepping, softened forces. |
| Bodies | Planets, stars, black holes. Create, drag, fling, inspect, change mass/type, delete. |
| Collisions | Bodies merge with momentum conservation and throw off debris. Stars over 3,200 mass collapse into black holes; heavy planets fuse into stars. |
| Black holes | Event horizons absorb particles, accretion friction makes disks spiral in, and a photon-ring glow is drawn. |
| Space-time grid | A "rubber sheet" grid bent by the summed gravitational potential. |
| Presets | Black Hole, Solar Orbits, Binary Stars, Three-Body (figure-eight choreography), Galaxy Collision. |
| SURPRISE ME | Procedurally generated universe: random cores, spiral arms, satellites, intruders, palette. |
| Visuals | OpenGL ES 3.0, additive glow, speed-colored particles, motion trails, 4 palettes, starfield. |
| Camera | Orbit, pinch-zoom, twist, auto-framing on the center of mass, and a slow cinematic orbit for screen recordings. |
| Slow motion | Time speed 0.1×–4×. |
| Snapshots | Full-resolution PNG with CHAOS LAB watermark saved to `Pictures/ChaosLab`, plus one-tap Android share. |
| Performance | Adaptive quality lowers the particle count when FPS drops and restores it when there's headroom. |
| Persistence | Settings saved with DataStore. |

## Gestures

| Gesture | Action |
|---|---|
| Drag empty space | Orbit camera |
| Pinch / two-finger twist | Zoom / rotate |
| Long-press empty space | Create a body (choose Planet / Star / Black hole in the dock) |
| Drag a body | Move it; release with a flick to throw it |
| Tap empty space | Drop an orbiting dust cloud |
| Tap a body | Select and inspect it (mass, type, delete) |
| Double-tap space | Particle explosion |
| Double-tap a body | Delete it |

## Architecture

```
app/src/main/java/com/chaoslab/app/
├── sim/       Physics: GravitySimulation (N-body + particles), Body, Presets   (pure Kotlin, no Android deps)
├── render/    OpenGL ES 3.0: UniverseRenderer, Shaders, OrbitCamera, Palette
├── engine/    ChaosEngine (GL-thread loop: step → camera → draw → stats), ChaosSurfaceView (touch → commands)
├── export/    ScreenshotExporter (MediaStore + share sheet)
├── data/      SettingsStore (DataStore)
└── ui/        Jetpack Compose overlay: glass controls, inspector, hints
```

Threading: all simulation and GL work happens on the GLSurfaceView render thread, and touch and UI commands are queued onto it.
UI state comes back through a `StateFlow` 4× per second, so the main thread is never blocked by physics.

Render order: sky → body cores (depth write) → space-time grid → trails → particles → body halos.
Particles behind a black hole are correctly hidden by its event horizon.

## Build

### Option A: GitHub Actions (no PC setup needed)
1. Create an empty GitHub repo (e.g. `ChaosLab`) and push this folder to it.
2. The **Build CHAOS LAB APK** workflow runs automatically on push.
3. Open the repo's **Releases** page on your tablet, download `ChaosLab.apk`, and install it.
   You'll need to allow installs from unknown sources.

### Option B: Android Studio
1. Open this folder in Android Studio (Koala or newer), let Gradle sync.
2. Run on a connected tablet, or **Build → Build APK(s)**.

### Option C: Command line
```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (compileSdk 34). The release APK is signed with the debug key so it installs directly.
Use your own keystore before publishing to the Play Store.

**Requirements:** Android 10+ (API 29), OpenGL ES 3.0.

## Roadmap

1. ✅ **Gravity Playground**
2. **Black Hole Simulator**: gravitational-lensing post-process, relativistic-looking accretion disk, jets
3. **Replay & sharing**: event recording, saved experiments (Room), replay speed control, 9:16 video export (MediaCodec), shareable experiment files
4. **Challenges & polish**: daily challenges, Daily Discovery, My Creations gallery
5. Fluid Laboratory, Galaxy Creator, Particle Explosion Studio, Quantum Playground (educational visualisations, clearly labelled)

Modules are only added to the UI once they work. There are no placeholder buttons.
