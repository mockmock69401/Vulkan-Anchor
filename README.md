# Vulkan Anchor (Minecraft 1.21.1, NeoForge)

English | [한국어](README.ko.md)

A Vulkan renderer for **NeoForge 1.21.1 modpacks**: an unofficial, modified build of
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged), itself a fork of
[VulkanMod](https://github.com/xCollateral/VulkanMod). It runs large OpenGL-era modpacks on Vulkan and adds built-in
shaders, Distant Horizons support and frame generation. This branch is the 1.21.1 port; the Forge 1.20.1 build
(including Conquest the Kingdom's Throne) is on the `main` branch.

It is experimental: compatibility and feature parity are incomplete.

> **Made with AI.** Every change this build makes to VulkanMod Reforged was written by an AI coding assistant
> (Anthropic's Claude, through Claude Code) under the direction and in-game testing of mockmock69401. It is
> **not endorsed** by Rindw (VulkanMod Reforged), xCollateral and the VulkanMod contributors, the Distant Horizons
> authors, the authors of the mods named below, or AMD.

## Features

- **Modpack compatibility**: mods that draw with OpenGL directly (their own shaders, framebuffers, HUD and map mods
  such as Xaero's, JourneyMap and FTB Quests, screen effects) work on Vulkan.
- **Built-in shaders**, no shader pack needed: sun shadows, water reflections, realistic and anime clouds, sky, stars
  and auroras, rain, snow and sandstorms, bloom, fog and anti-aliasing (SMAA, TAA). They were written for this build
  and contain no shader-pack code.
- **Presets**: performance presets (Potato to Ultra) and colour palettes (Default, Realistic, Cinematic, Anime,
  Horror), on the "Shaders" pages of the video settings.
- **LabPBR resource packs**: terrain lit with the pack's materials, switchable in the settings.
- **Distant Horizons**: LODs drawn by the Vulkan renderer, lit and shadowed by the shaders.
- **Frame generation**: AMD FSR 3.1 or a built-in Optical Flow generator.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1 (tested with 21.1.228 to 21.1.259)
- Java 21
- A GPU and driver with Vulkan 1.2
- For FSR 3.1 frame generation: Windows x64 and a GPU with Vulkan 1.3
- Optional: Distant Horizons 3.3.1 or newer (3.3.0 runs, but its LODs are not drawn). With Lost Cities installed,
  Distant Horizons generates through the server, more slowly but without holes in the LODs.

## Installing

1. Put `VulkanAnchor_1.21.1-<version>.jar` in the modpack's `mods` folder and remove any other VulkanMod or VulkanMod
   Reforged jar.
2. Start the game once. Vulkan Anchor disables the mods it cannot run with (the Sodium family and the others in the
   [full list](src/main/resources/assets/vulkanmod/compat/incompatible_mod_ids.txt)) by renaming them to
   `<name>.jar.disabled`, lists them and closes the game. Start it again to play.
3. To keep one of them on, remove `.disabled` and add its mod id to `config/vulkanmod_keep_mods.txt`, one id per line.

Frame generation only helps when your frame rate is below your monitor's refresh rate.

## Tested modpacks

Tested on CurseForge with the versions below. Vulkan Anchor disables these mods by itself at the first start.

| Modpack (version tested) | Disabled |
|---|---|
| Tensura Neo Otherworld (1.1.2a) | `sodium`, `sodium-extra`, `reeses-sodium-options`, `iris`, `chloride`, `flerovium`, `Kerria` |
| Better MC [BMC5] (v53) | `sodium`, `sodiumextras`, `sodiumdynamiclights`, `sodiumleafculling`, `sodiumoptionsapi`, `sodiumoptionsmodcompat`, `reeses-sodium-options`, `iris`, `iris_shader_folder`, `IrisSearch`, `colorwheel`, `immersivelanterns`, `modpack-update-checker` |
| All the Mods 10 (8.3) | `sodium`, `sodium-extra`, `iris`, `IrisSearch`, `colorwheel` |

- ComputerCraft monitors work with the default monitor renderer ("best"), not with "tbo".
- If a mod you add later fails to load, check whether it needs one of the disabled mods.

## Building

```
./gradlew assemble
```

The jar is written to `build/libs/VulkanAnchor_1.21.1-<version>.jar`. The build needs JDK 21; run the tests with
`./gradlew test`.

## Known limitations

- FSR 3.1 frame generation runs on Windows only.
- With frame generation, fast-moving mobs and particles can look doubled.
- Some mods' own OpenGL shaders still fail to convert (for example shaders that read texture buffers); their effects
  fall back to a plain shader instead of crashing.

## License

LGPL-3.0-only, as VulkanMod and VulkanMod Reforged: see [LICENSE](LICENSE) and [COPYING](COPYING).
[NOTICE](NOTICE) lists the authors and modifications, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) the
third-party parts and their licences. "Vulkan Anchor" is an unofficial build; upstream names and logos remain their
owners'.
