# Vulkan Anchor (Minecraft 1.21.1, NeoForge)

English | [한국어](README.ko.md)

A Vulkan renderer for **NeoForge 1.21.1 modpacks**: an unofficial, modified build of
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged), itself a fork of
[VulkanMod](https://github.com/xCollateral/VulkanMod). This branch is the 1.21.1 port; the `main` branch holds the Forge
1.20.1 build.

> **Made with AI.** Every change this build makes to VulkanMod Reforged — the compatibility fixes, the
> post-processing shaders, the Distant Horizons support, the frame generation, the 1.21.1 port and this README — was
> written by an AI coding assistant (Anthropic's Claude, through Claude Code) under the direction and in-game testing
> of mockmock69401. Read, test and report issues with that in mind.
>
> It is **not endorsed** by Rindw (VulkanMod Reforged), xCollateral and the VulkanMod contributors, the
> Distant Horizons authors, the authors of the mods named below, or AMD.

## Purpose

Large modpacks are made for OpenGL. Many of their mods draw with OpenGL directly (their own shaders, framebuffers,
HUD and map mods, screen effects), which a Vulkan renderer has to translate or work around. This build exists to run
such modpacks on Vulkan, and adds a shader look, far terrain and frame generation on top.

It is experimental: compatibility and feature parity are incomplete.

## What it adds to VulkanMod Reforged

- **Modpack compatibility fixes**: mods' own OpenGL shaders are converted in more cases (geometry shaders such as
  Lodestone's, GLSL interface blocks, uniforms declared in imported shader files, `%` remainders), plus fixes for
  AAA Particles, Vivecraft, FancyMenu and Iceberg, and renderer stability fixes found while testing the modpacks below.
  Xaero's Minimap and World Map, JourneyMap and FTB Quests were checked and work.
- **Shaders (post-processing)**, on the "Shaders" pages of VulkanMod's video settings: sun shading and shadows,
  water reflections and ripples (raindrops ring it in the rain), fog and light shafts, bloom, clouds, ambient occlusion, foliage wind, held-item
  lights, anti-aliasing (SMAA, TAA). It needs no shader pack and no LOD mod. Its passes and shaders were written
  for this build from requirement documents and published graphics literature; they contain no shader-pack code.
- **Light colours** (Shaders → Lighting page): direct sunlight that is warmest at sunrise and sunset (Sun Warmth),
  orange light from torches and other light blocks that is brighter near the source and falls off faster (Torch Light
  Colour), and blue moonlight at night (part of Light Colour). Each has its own slider; 0 keeps the earlier colours.
- **Shadows and reflections of a high shader preset**: mobs, players and dropped items within 32 blocks cast sun
  shadows (Entity Shadows), the sharp shadows of the terrain reach 2 to 10 chunks (Shadow Distance), both on the
  Lighting page; polished stone, quartz, glazed terracotta, packed ice, gem and metal blocks catch the sun's and the
  moon's highlights and softly mirror the sky and the scene, metal in its own colour (Block Reflections on the Water
  page: Off, Highlights, Reflections).
- **Glow** (Shaders → Lighting page): lava, magma, glowstone and other light blocks glow into the bloom (Block Glow), and
  nether quartz ore's white specks glow faintly even in the dark (Ore Glow; other ores look as in vanilla). Water is clearer too,
  so lake and river floors show through (Water Depth at 0 keeps vanilla's water).
- **Weather fog** (Shaders → Atmosphere page): in rain and thunderstorms the distance fades into a blue-grey fog at any
  height, as with shader packs; nearby stays clear (Weather Fog; 0 keeps the clear-weather fog). Sunlit snow and other
  bright surfaces keep their texture instead of clipping to white.
- **Text anti-aliasing** (Video settings → Graphics page): font text, the game's own font and fonts from resource packs
  alike, stays readable from far away and at an angle (signs, name tags) and looks clean in the GUI: mipmapped font pages,
  trilinear and anisotropic filtering, thin strokes kept when small. Switches instantly (Text Anti-aliasing).
  Bold text in TTF fonts is drawn once as solid strokes instead of a second copy one pixel over, and TTF fonts smaller
  than the default sit in the middle of the line.
- **Distant Horizons support**: Distant Horizons LODs drawn by the Vulkan renderer, composited under vanilla terrain and
  lit and shadowed by the shaders. Works with the released Distant Horizons **3.3.1 and newer** (it takes over Distant
  Horizons' internal render definition) and with Distant Horizons' render API once that is released (3.3.5).
- **Frame generation** ("Frame Generation" block on the Shaders → Image page), two choices, one at a time:
  - **FSR 3.1 Frame Generation**: AMD FidelityFX Super Resolution 3.1 frame generation (one generated frame between
    real frames; the HUD stays sharp). Windows x64 and a GPU with Vulkan 1.3 only; greyed out with the reason
    elsewhere.
  - **Optical Flow**: a built-in generator that reprojects the last frame by the camera's motion.
- **Performance**: animated textures only update while visible, blocks you change (a door, a placed block) show at once while chunks load, and fixes for stalls seen in large modpacks.
- **FPS overlay** (Video settings → FPS Overlay, off by default): the frame rate in the top-left corner, as the average
  only, the average with min and max, or the average with the 1% and 0.1% lows, over the last 5 seconds.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1 (tested with 21.1.228, 21.1.244, 21.1.250 and 21.1.259)
- Java 21
- A GPU and driver with Vulkan 1.2
- For FSR 3.1 frame generation: Windows x64 and a GPU with Vulkan 1.3
- Optional, for LODs: **Distant Horizons 3.3.1 or newer** (tested with 3.3.1 and 3.3.3).
  Distant Horizons 3.3.0 for 1.21.1 runs, but its LODs are not drawn: its NeoForge build cannot be taken over, and the
  log asks you to update it. The 1.20.1 build on `main` asks for the same versions.
  With **Lost Cities** installed, this build runs Distant Horizons' distant generator in INTERNAL_SERVER mode (in
  memory only; `DistantHorizons.toml` is left alone): the faster modes fail on Lost Cities' buildings ("There was an
  error generating a chunk" in chat) and leave holes in the LODs. Distant terrain then fills in more slowly, and the
  generated chunks are saved to the world.

## Installing

1. Put the single production jar (`VulkanAnchor_1.21.1-<version>.jar`) in the modpack's `mods` folder.
2. Remove or disable any other VulkanMod or VulkanMod Reforged jar.
3. Start the game once. VulkanMod disables the mods it cannot run with by itself: the Sodium family and the others in the
   [full list](src/main/resources/assets/vulkanmod/compat/incompatible_mod_ids.txt). It renames their jars to
   `<name>.jar.disabled`, lists them in a dialog and closes the game; start it again to play. To keep one of them on,
   remove `.disabled` and add its mod id to `config/vulkanmod_keep_mods.txt`, one id per line; a kept mod that needs
   another mod on the list needs that one kept too. A mod that requires a disabled mod is only named in the dialog,
   because disabling it could remove content from a world.

Frame generation costs GPU time: the game renders fewer real frames than with it off, and at high frame rates it can
show fewer frames in total. Use it when your frame rate is below your monitor's refresh rate. While it runs, the
game renders at most half your monitor's refresh rate (a lower frame limit in Video Settings still applies), so real
and generated frames together fill the refresh. Mobs, particles and the HUD keep the real frame rate.

## Tested modpacks: mods to disable

Tested on CurseForge with the versions below (copies of the installed packs: a new world, the HUD, maps and the quest
book checked; rechecked on clean installs 2026-10-25). VulkanMod disables these by itself at the first start, so this table is what to expect. "Disable" means renaming the jar to `<name>.jar.disabled` (CurseForge's "disable" switch does the same).

| Modpack (version tested) | Disable |
|---|---|
| Tensura Neo Otherworld (1.1.2a) | `sodium`, `sodium-extra`, `reeses-sodium-options`, `iris`, `chloride`, `flerovium`, `Kerria` |
| Better MC [BMC5] (v53) | `sodium`, `sodiumextras`, `sodiumdynamiclights`, `sodiumleafculling`, `sodiumoptionsapi`, `sodiumoptionsmodcompat`, `reeses-sodium-options`, `iris`, `iris_shader_folder`, `IrisSearch`, `colorwheel`, `immersivelanterns`, `modpack-update-checker` |
| All the Mods 10 (8.3) | `sodium`, `sodium-extra`, `iris`, `IrisSearch`, `colorwheel` |

Notes:

- Kerria and Modpack Update Checker hook OpenGL texture uploads that the Vulkan renderer replaces, and crash the game
  at startup.
- Immersive Lanterns and Colorwheel require Sodium Dynamic Lights or Iris, so they go with them.
- ComputerCraft monitors work with the default monitor renderer ("best"); its "tbo" renderer option does not.
- If a mod you add later fails to load, check whether it needs one of the disabled mods.

## Building

```
./gradlew assemble
```

The jar is written to `build/libs/VulkanAnchor_1.21.1-0.1.1.jar` (the `-sources.jar` next
to it is for development). Run the tests with `./gradlew test`. The build needs JDK 21; Gradle downloads everything
else, including NeoForge and LWJGL. On Windows, the FSR struct-layout probe in `tools/` was compiled with MSVC 2022 and
its output is checked in; the build does not need a C compiler.

## Project structure

| Path | What it holds |
|---|---|
| `src/main/java/net/vulkanmod/` | VulkanMod Reforged: the Vulkan backend (`vulkan/`, including the mod-shader converter in `vulkan/shader/parser/`), renderer (`render/`), mixins (`mixin/`), config and compatibility code |
| `src/main/java/net/vulkanmod/visuals/` | everything this build adds to the renderer |
| `  render/` | post-processing passes and their maths (lighting, shadows, reflections, fog, clouds, bloom, ambient occlusion, TAA) |
| `  vk/` | small Vulkan helpers used by the passes (images, buffers, upload/download streams, pipelines) |
| `  api/`, `lod/` | the interface an LOD renderer implements, and the LOD target and its composite |
| `  dh/` | Distant Horizons support: render API registration (3.3.5+) and the takeover of 3.3.1-3.3.4's render definition (`Legacy*`, mixins in `mixin/visuals/legacydh/`), vertex buffers, culling, shadow casters |
| `  framegen/` | Optical Flow frame generation |
| `  fsr/` | AMD FSR 3.1 frame generation: the library loader, struct layouts, the swapchain bridge and the per-frame driver |
| `  config/` | the "Shaders" settings pages and their saved options |
| `  dev/` | a scripted test pilot, active only with `-Dvulkanmod.visuals.pilot=<file>` |
| `src/main/java/dev/vulkanfix/` | modpack compatibility fixes kept as mixins (`vulkanfix.mixins.json`) |
| `src/main/resources/assets/vulkanmod/visuals/shaders/` | GLSL shaders (post-processing, shadows, LOD terrain, frame generation) |
| `src/main/resources/natives/windows-x64/` | AMD's FidelityFX Vulkan library (prebuilt, signed, unmodified) and its licence |
| `src/test/` | JUnit tests (maths, struct layouts, the shader converter, settings and wiring checks; no game needed) |
| `third_party/` | SMAA sources, FidelityFX headers and provenance, LWJGL licence |
| `tools/` | the MSVC probe that pins the FidelityFX struct layouts, and its output |
| `branding/` | the CurseForge icon (512 and 1024), the mark without the name (512) and the script that draws them |
| `libs/DistantHorizonsApi-7.3.0.jar` | Distant Horizons API, compile-time only (not packed into the jar); Distant Horizons 3.3.3 itself is fetched from Modrinth's Maven at build time, also compile-time only |
| `libs/flywheel-neoforge-1.21.1-1.0.6.jar` | Flywheel, compile-time only (from VulkanMod Reforged; not packed into the jar) |

## Known limitations

- FSR 3.1 frame generation runs on Windows only. Two workarounds read the bundled FidelityFX library's internal state:
  one keeps its presents from freezing on one tested driver, the other waits for free command lists before each present
  (when the library ran out of them, the game crashed). The bundled library cannot be swapped for another version
  without updating that code (both switch themselves off if the library differs).
- Frame generation moves mobs and particles at the real frame rate; fast-moving objects can look doubled.
- Some mods' own OpenGL shaders still fail to convert (for example shaders that read texture buffers); their effects
  fall back to a plain shader instead of crashing.

## License

LGPL-3.0-only, as VulkanMod and VulkanMod Reforged: see [LICENSE](LICENSE) (LGPLv3) and [COPYING](COPYING) (the
GPLv3 text it incorporates). [NOTICE](NOTICE) lists the authors, the modifications and their dates, and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists the third-party parts (LWJGL, AMD FidelityFX SDK, SMAA,
Distant Horizons, Flywheel) and their licences.

"Vulkan Anchor" is an unofficial build. The code licence grants no trademark rights; upstream names and logos remain
their owners'.
