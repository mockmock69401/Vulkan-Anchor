# VulkanMod-shaded

English | [한국어](README.ko.md)

A Vulkan renderer for **legacy Forge 1.20.1 modpacks**: an unofficial, modified build of
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged), itself a fork of
[VulkanMod](https://github.com/xCollateral/VulkanMod). This branch is the Forge 1.20.1 build; the NeoForge 1.21.1
build is on the `1.21.1` branch.

> **Made with AI.** Every change this build makes to VulkanMod Reforged — the compatibility fixes, the
> post-processing shaders, the Xaero map support, the Distant Horizons support, the frame generation and this
> README — was written by an AI coding assistant (Anthropic's Claude, through Claude Code) under the direction
> and in-game testing of mockmock69401. Read, test and report issues with that in mind.
>
> It is **not endorsed** by Rindw (VulkanMod Reforged), xCollateral and the VulkanMod contributors, the
> Distant Horizons authors, the authors of Xaero's mods, or AMD.

## Purpose

Large Forge 1.20.1 modpacks were made for OpenGL. Many of their mods draw with OpenGL directly (legacy shaders,
framebuffers, HUD and map mods, screen effects), which a Vulkan renderer has to translate or work around. This build
exists to run such modpacks on Vulkan, and adds a shader look, far terrain and frame generation on top.

It is experimental: compatibility and feature parity are incomplete.

## What it adds to VulkanMod Reforged

- **Modpack compatibility fixes** (`src/vulkanfix`): legacy OpenGL shaders from mods, framebuffer reads and blits,
  GUI and HUD layouts, screen-effect mods (for example attack effects that used to flip the world), and renderer
  stability fixes found while testing the modpacks below.
- **Xaero's Minimap and Xaero's World Map support**: their map framebuffers, readbacks and projections work on
  Vulkan.
- **Shaders (post-processing)**, on the "Shaders" pages of VulkanMod's video settings: sun shading and shadows,
  water reflections and ripples, fog and light shafts, bloom, clouds, ambient occlusion, foliage wind, held-item
  lights, anti-aliasing (SMAA, TAA). It needs no shader pack and no LOD mod. Its passes and shaders were written
  for this build from requirement documents and published graphics literature; they contain no shader-pack code.
- **Distant Horizons support**: Distant Horizons LODs drawn by the Vulkan renderer, composited under vanilla terrain and
  lit and shadowed by the shaders. Works with the released Distant Horizons 3.3.1 and newer (it takes over Distant
  Horizons' internal render definition) and with Distant Horizons' render API once that is released (3.3.5).
- **Frame generation** ("Frame Generation" block on the Shaders → Image page), two choices, one at a time:
  - **FSR 3.1 Frame Generation**: AMD FidelityFX Super Resolution 3.1 frame generation (one generated frame between
    real frames; the HUD stays sharp). Windows x64 and a GPU with Vulkan 1.3 only; greyed out with the reason
    elsewhere.
  - **Optical Flow**: a built-in generator that reprojects the last frame by the camera's motion.
- **Performance**: animated textures only update while visible, and fixes for stalls seen in large modpacks.

## Requirements

- Minecraft 1.20.1 with Forge 47.1.106 or newer (tested with 47.4.0 to 47.4.28)
- Java 17
- A GPU and driver with Vulkan 1.2
- For FSR 3.1 frame generation: Windows x64 and a GPU with Vulkan 1.3
- Optional, for LODs: Distant Horizons 3.3.1 or newer, the same as the 1.21.1 build; for 1.20.1 the first such release
  is 3.3.2 (tested with 3.3.2, 3.3.3 and a 3.3.5 development build). Older Distant Horizons versions are not supported.

## Installing

1. Put the single production jar (`VulkanMod-shaded_1.20.1-<version>.jar`) in the modpack's `mods` folder.
2. Remove or disable any other VulkanMod or VulkanMod Reforged jar.
3. Disable the mods listed for your modpack below. In general, Embeddium, Oculus, Mekalus,
   Chloride, Sodium add-ons and any mod that replaces OpenGL rendering or presents frames itself conflict with a
   Vulkan renderer.

Frame generation costs GPU time: the game renders fewer real frames than with it off, and at high frame rates it can
show fewer frames in total. Use it when your frame rate is below your monitor's refresh rate. Turning either
generator on sets the frame limit to 60 (change it in Video Settings). Mobs, particles and the HUD keep the real frame
rate.

## Tested modpacks: mods to disable

Tested on CurseForge with the versions below. "Disable" means renaming the jar to `<name>.jar.disabled` (CurseForge's
"disable" switch does the same).

| Modpack (version tested) | Disable |
|---|---|
| Craft to Exile 2 (2.1.4, "2.0 Atlas Update") | `embeddium`, `oculus`, `chloride`, `ModernUI`, `gbf` (GeckoBetterFPS) |
| DeceasedCraft – Urban Zombie Apocalypse (Beta 5.10.17) | `sodiumextras` |
| NightfallCraft – The Casket of Reveries (2.2.9.7) | `embeddium`, `oculus`, `chloride`, `flerovium`, `acceleratedrendering`, `sodiumdynamiclights`, `ModernUI` |
| Society: Sunlit Valley (4.1.5) | `embeddium`, `oculus`, `flerovium`, `sodiumdynamiclights`, `sodiumoptionsapi`, `colorwheel` (keep `colorwheel_patcher`) |
| Zero to Engineering (0.5.1) | `embeddium`, `oculus`, `mekalus`, `pfg` (its own OpenGL frame generation), `colorwheel`, `createbetterfps`, `sodiumdynamiclights`, `sodiumoptionsapi`, `sodiumoptionsmodcompat` |

Notes:

- Zero to Engineering's Serene Seasons Plus shows a Sodium warning screen on every launch; close it and continue.
- If a mod you add later fails to load, check whether it needs one of the disabled mods (for example, Immersive
  Lanterns requires Sodium Dynamic Lights).

## Building

```
./gradlew assemble
```

The jar is written to `build/libs/VulkanMod-shaded_1.20.1-0.1.0.jar` (the `-sources.jar` next
to it is for development). Run the tests with `./gradlew test`. The build needs JDK 17; Gradle downloads everything
else, including Forge and LWJGL. On Windows, the FSR struct-layout probe in `tools/` was compiled with MSVC 2022 and
its output is checked in; the build does not need a C compiler.

## Project structure

| Path | What it holds |
|---|---|
| `src/main/java/net/vulkanmod/` | VulkanMod Reforged: the Vulkan backend (`vulkan/`), renderer (`render/`), mixins (`mixin/`), config and compatibility code |
| `src/main/java/net/vulkanmod/visuals/` | everything this build adds to the renderer |
| `  render/` | post-processing passes and their maths (lighting, shadows, reflections, fog, clouds, bloom, ambient occlusion, TAA) |
| `  vk/` | small Vulkan helpers used by the passes (images, buffers, upload/download streams, pipelines) |
| `  api/`, `lod/` | the interface an LOD renderer implements, and the LOD target and its composite |
| `  dh/` | Distant Horizons support: render API registration (3.3.5+) and the takeover of 3.3.0-3.3.4's render definition (`Legacy*`, mixins in `mixin/visuals/legacydh/`), vertex buffers, culling, shadow casters |
| `  framegen/` | Optical Flow frame generation |
| `  fsr/` | AMD FSR 3.1 frame generation: the library loader, struct layouts, the swapchain bridge and the per-frame driver |
| `  config/` | the "Shaders" settings pages and their saved options |
| `  dev/` | a scripted test pilot, active only with `-Dvulkanmod.visuals.pilot=<file>` |
| `src/main/resources/assets/vulkanmod/visuals/shaders/` | GLSL shaders (post-processing, shadows, LOD terrain, frame generation) |
| `src/main/resources/natives/windows-x64/` | AMD's FidelityFX Vulkan library (prebuilt, signed, unmodified) and its licence |
| `src/vulkanfix/` | the merged modpack compatibility fixes (package `dev.vulkanfix`, `vulkanfix.mixins.json`) |
| `src/test/` | JUnit tests (maths, struct layouts, settings and wiring checks; no game needed) |
| `third_party/` | SMAA sources, FidelityFX headers and provenance, LWJGL licence |
| `tools/` | the MSVC probe that pins the FidelityFX struct layouts, and its output |
| `branding/` | the CurseForge icon (512 and 1024) and the script that draws it |
| `libs/DistantHorizonsApi-7.3.0.jar` | Distant Horizons API, compile-time only (not packed into the jar); Distant Horizons 3.3.3 itself is fetched from Modrinth's Maven at build time, also compile-time only |

## Known limitations

- FSR 3.1 frame generation runs on Windows only; on one tested driver it needed a workaround that reads the bundled
  FidelityFX library's internal state, so the bundled library cannot be swapped for another version without updating
  that code (the workaround switches itself off if the library differs).
- Frame generation moves mobs and particles at the real frame rate; fast-moving objects can look doubled.
- Some mods' own OpenGL shaders still fail to compile on Vulkan; their effects are skipped instead of crashing.

## License

LGPL-3.0-only, as VulkanMod and VulkanMod Reforged: see [LICENSE](LICENSE) (LGPLv3) and [COPYING](COPYING) (the
GPLv3 text it incorporates). [NOTICE](NOTICE) lists the authors, the modifications and their dates, and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists the third-party parts (LWJGL, AMD FidelityFX SDK, SMAA,
Distant Horizons) and their licences.

"VulkanMod-shaded" is an unofficial build. The code licence grants no trademark rights; upstream names and logos remain
their owners'.
