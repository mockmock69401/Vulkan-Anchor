# Third-party notices

Vulkan Anchor is LGPL-3.0-only (see [LICENSE](LICENSE), [COPYING](COPYING) and [NOTICE](NOTICE)). It includes or
builds against the following third-party parts, each under its own licence.

| Part | Licence | Where | Notes |
|---|---|---|---|
| VulkanMod (xCollateral and contributors), VulkanMod Reforged (Rindw) | LGPL-3.0-only | the whole tree | the code this build modifies |
| LWJGL 3.3.3 (`lwjgl-vulkan`, `lwjgl-shaderc`, `lwjgl-vma` and their natives) | BSD-3-Clause | packed into the jar under `META-INF/jarjar/` | licence text: [third_party/lwjgl/LICENSE.md](third_party/lwjgl/LICENSE.md), also in the jar as `THIRD_PARTY_LICENSE_LWJGL.md` |
| AMD FidelityFX SDK v1.1.4: `amd_fidelityfx_vk.dll` (prebuilt, signed by AMD, unmodified) and four API headers | MIT | `src/main/resources/natives/windows-x64/`, `third_party/fidelityfx/include/` | licence text: `src/main/resources/natives/windows-x64/LICENSE_FIDELITYFX.txt`; provenance and hashes: [third_party/fidelityfx/README.md](third_party/fidelityfx/README.md) |
| SMAA (Jorge Jimenez et al.) and the smaaDemo compute port (Alternative Games Ltd / Turo Lamminen) | MIT | `third_party/smaa/`, shaders under `src/main/resources/assets/vulkanmod/visuals/shaders/post/smaa_*.comp` | licence texts: `third_party/smaa/LICENSE-smaa.txt`, `third_party/smaa/LICENSE-smaaDemo.txt`, `src/main/resources/assets/vulkanmod/visuals/LICENSE_SMAA` |
| Distant Horizons API 7.3.0 | LGPL-3.0 | `libs/DistantHorizonsApi-7.3.0.jar` | compile-time only, not packed into the jar; Distant Horizons is installed separately |
| Distant Horizons 3.3.3 | LGPL-3.0 | fetched by Gradle from Modrinth's Maven (`maven.modrinth:distanthorizons:3.3.3-1.20.1`) | compile-time only, not in this repository or the jar; the 3.3.0-3.3.4 support implements its internal render interfaces |
| Distant Horizons LOD terrain shaders (Copyright (C) 2020 James Seibel) | LGPL-3.0 | `src/main/resources/assets/vulkanmod/visuals/shaders/dh/` | translated to Vulkan GLSL; notices kept in the files |
| 32-bit hash finaliser multipliers `0x7feb352d`, `0x846ca68b` (Wellons 2018, "Prospecting for hash functions") | public domain | `post/water.glsl` | constants only, no code |
| NASA's Scientific Visualization Studio, CGI Moon Kit (https://svs.gsfc.nasa.gov/4720/): the LROC WAC colour map `lroc_color_poles_4k.tif` (from the Hapke normalised WAC mosaic, Arizona State University / LRO camera team) and the LOLA elevation map `ldem_16_uint.tif` | public domain (NASA SVS: "All of our content is in the public domain"); credit requested to NASA's Scientific Visualization Studio | `src/main/resources/assets/vulkanmod/visuals/textures/moon/moon_face.bin` | the round moon's face (2026-11-12): the near side's albedo and slopes baked by `tools/moon/bake_moon_face.py`; the source maps are not in this repository |
| Unity Technologies' free VFX image sequences and flipbooks (Thomas Iché, 2016; https://unity.com/blog/engine-platform/free-vfx-image-sequences-flipbooks): `Flame03` | CC0 1.0 (public domain dedication) | `src/main/resources/assets/vulkanmod/visuals/textures/particles/particle_atlas.png` | the realistic particles' flame (2026-11-13): every other frame of its 64-frame flipbook, as brightness, baked by `tools/particles/bake_particle_atlas.py`; the sources are not in this repository |
| Kenney (Kenney Vleugels, https://kenney.nl): Particle Pack (`circle_03`, `circle_05`, `flare_01`, `star_01`, `star_04`, `star_06`) and Smoke Particles (eight of the white puffs) | CC0 1.0 (public domain dedication); credit appreciated, not required | `src/main/resources/assets/vulkanmod/visuals/textures/particles/particle_atlas.png` | the realistic particles' smoke puffs, glows, sparkles and bubble ring (2026-11-13), baked by `tools/particles/bake_particle_atlas.py` |
| Glimmer shader pack v1.2 (Copyright (c) 2024 Josh Britain, jbritain) | MIT | adapted in `shaders/include/pbr_terrain.glsl` (the parallax march), `visuals/shaders/post/pbr.glsl` and `visuals/render/PbrMath.java` (LabPBR channel decoding, the metals' tint) | rewritten for this renderer, credited in the files; licence text below |

Mods this build works alongside but contains no code from: Xaero's Minimap and Xaero's World Map (compatibility hooks only),
Distant Horizons (through its public API), Forge and Minecraft.

## Glimmer (MIT)

The PBR materials (2026-11-10) adapt parts of the Glimmer shader pack v1.2 (https://jbritain.net), under this licence:

```
MIT License

Copyright (c) 2024 Josh Britain (jbritain)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The LabPBR material format (shaderLABS wiki, "LabPBR Material Standard") is followed as a file format; no text or code of it is
included. The porosity and wetness (rain darkening porous materials, a water film glossing closed ones) are this renderer's own,
after Lagarde 2013 and Jensen, Legakis and Dorsey 1999.
