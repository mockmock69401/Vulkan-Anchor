# Third-party notices

VulkanMod-shaded is LGPL-3.0-only (see [LICENSE](LICENSE), [COPYING](COPYING) and [NOTICE](NOTICE)). It includes or
builds against the following third-party parts, each under its own licence.

| Part | Licence | Where | Notes |
|---|---|---|---|
| VulkanMod (xCollateral and contributors), VulkanMod Reforged (Rindw) | LGPL-3.0-only | the whole tree | the code this build modifies |
| LWJGL 3.3.3 (`lwjgl-vulkan`, `lwjgl-shaderc`, `lwjgl-vma` and their natives) | BSD-3-Clause | packed into the jar under `META-INF/jarjar/` | licence text: [third_party/lwjgl/LICENSE.md](third_party/lwjgl/LICENSE.md), also in the jar as `THIRD_PARTY_LICENSE_LWJGL.md` |
| AMD FidelityFX SDK v1.1.4: `amd_fidelityfx_vk.dll` (prebuilt, signed by AMD, unmodified) and four API headers | MIT | `src/main/resources/natives/windows-x64/`, `third_party/fidelityfx/include/` | licence text: `src/main/resources/natives/windows-x64/LICENSE_FIDELITYFX.txt`; provenance and hashes: [third_party/fidelityfx/README.md](third_party/fidelityfx/README.md) |
| SMAA (Jorge Jimenez et al.) and the smaaDemo compute port (Alternative Games Ltd / Turo Lamminen) | MIT | `third_party/smaa/`, shaders under `src/main/resources/assets/vulkanmod/visuals/shaders/post/smaa_*.comp` | licence texts: `third_party/smaa/LICENSE-smaa.txt`, `third_party/smaa/LICENSE-smaaDemo.txt`, `src/main/resources/assets/vulkanmod/visuals/LICENSE_SMAA` |
| Distant Horizons API 7.3.0 | LGPL-3.0 | `libs/DistantHorizonsApi-7.3.0.jar` | compile-time only, not packed into the jar; Distant Horizons is installed separately |
| Distant Horizons 3.3.3 | LGPL-3.0 | fetched by Gradle from Modrinth's Maven (`maven.modrinth:distanthorizons:3.3.3-1.21.1`) | compile-time only, not in this repository or the jar; the 3.3.1-3.3.4 support implements its internal render interfaces |
| Flywheel 1.0.6 (NeoForge 1.21.1) | MIT | `libs/flywheel-neoforge-1.21.1-1.0.6.jar` | compile-time only, not packed into the jar; carried over from VulkanMod Reforged for its Flywheel compatibility code |
| Distant Horizons LOD terrain shaders (Copyright (C) 2020 James Seibel) | LGPL-3.0 | `src/main/resources/assets/vulkanmod/visuals/shaders/dh/` | translated to Vulkan GLSL; notices kept in the files |
| 32-bit hash finaliser multipliers `0x7feb352d`, `0x846ca68b` (Wellons 2018, "Prospecting for hash functions") | public domain | `post/water.glsl` | constants only, no code |

Mods this build works alongside but contains no code from: AAA Particles, Vivecraft, FancyMenu and Iceberg (compatibility
hooks only), Distant Horizons (through its public API), NeoForge and Minecraft.
