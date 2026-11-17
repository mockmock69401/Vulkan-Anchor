# Vulkan Anchor

[English](README.md) | 한국어

**Forge 1.20.1 모드팩**을 위한 Vulkan 렌더러입니다. [VulkanMod](https://github.com/xCollateral/VulkanMod)의 포크인
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged)를 수정한 비공식 빌드입니다. OpenGL 기준으로 만들어진
대형 모드팩을 Vulkan으로 돌리고, 내장 셰이더, Distant Horizons 지원, 프레임 생성을 더합니다. 이 브랜치는 Forge 1.20.1
빌드이고, NeoForge 1.21.1 빌드는 `1.21.1` 브랜치에 있습니다.

실험적인 빌드입니다. 호환성과 기능은 아직 완전하지 않습니다.

> **AI로 만들었습니다.** 이 빌드가 VulkanMod Reforged에 더한 모든 변경은 mockmock69401의 지시와 게임 내 테스트 아래
> AI 코딩 도구(Anthropic의 Claude, Claude Code)가 작성했습니다. Rindw(VulkanMod Reforged), xCollateral과 VulkanMod
> 기여자들, Distant Horizons 개발자, 아래에 나오는 모드의 개발자, AMD가 **보증하거나 승인한 빌드가 아닙니다.**

## 기능

- **모드팩 호환성**: OpenGL로 직접 그리는 모드(자체 셰이더, 프레임버퍼, Xaero 같은 HUD·지도 모드, 화면 효과)가
  Vulkan에서 작동합니다.
- **내장 셰이더**(셰이더팩 불필요): 햇빛 그림자, 물 반사, 사실적·애니메이션 구름, 하늘, 별과 오로라, 비·눈·모래폭풍,
  블룸, 안개, 안티에일리어싱(SMAA, TAA). 이 빌드를 위해 새로 작성했으며 셰이더팩 코드는 들어 있지 않습니다.
- **프리셋**: 성능 프리셋(Potato~Ultra)과 색상 프리셋(기본, 현실적, 시네마틱, 애니메이션, 공포). 비디오 설정의
  "Shaders" 페이지에 있습니다.
- **LabPBR 리소스팩**: 리소스팩의 재질로 지형에 빛을 입힙니다. 설정에서 켜고 끌 수 있습니다.
- **Distant Horizons**: LOD를 Vulkan 렌더러로 그리고, 셰이더로 빛과 그림자를 입힙니다.
- **프레임 생성**: AMD FSR 3.1 또는 내장 Optical Flow.

## 요구 사항

- Minecraft 1.20.1과 Forge 47.1.106 이상 (47.4.0~47.4.28에서 테스트)
- Java 17
- Vulkan 1.2를 지원하는 GPU와 드라이버
- FSR 3.1 프레임 생성: Windows x64와 Vulkan 1.3을 지원하는 GPU
- 선택: Distant Horizons 3.3.1 이상 (1.20.1용으로는 3.3.2가 첫 출시 버전). Lost Cities가 있으면 Distant Horizons가
  서버를 통해 지형을 만들어, 더 느리지만 LOD에 구멍이 생기지 않습니다.

## 설치

1. `VulkanAnchor_1.20.1-<버전>.jar`를 모드팩의 `mods` 폴더에 넣고, 다른 VulkanMod나 VulkanMod Reforged jar는 지웁니다.
2. 게임을 한 번 실행합니다. Vulkan Anchor가 함께 실행할 수 없는 모드(Sodium 계열과
   [전체 목록](src/main/resources/assets/vulkanmod/compat/incompatible_mod_ids.txt)의 모드)의 이름을
   `<이름>.jar.disabled`로 바꾸고, 목록을 보여 준 뒤 게임을 닫습니다. 다시 실행하면 플레이할 수 있습니다.
3. 그중 하나를 계속 켜 두려면 `.disabled`를 지우고 `config/vulkanmod_keep_mods.txt`에 모드 ID를 한 줄에 하나씩 적습니다.

프레임 생성은 프레임이 모니터 주사율보다 낮을 때만 도움이 됩니다.

## 테스트한 모드팩

아래 버전으로 CurseForge에서 테스트했습니다. 표의 모드는 첫 실행 때 Vulkan Anchor가 직접 비활성화합니다.

| 모드팩 (테스트한 버전) | 비활성화되는 모드 |
|---|---|
| Conquest the Kingdom's Throne (12.8.0) | `embeddium`, `oculus`, `embeddium_extra` (Rubidium Extra), `createbetterfps`, `gbf` |
| Craft to Exile 2 (2.1.4) | `embeddium`, `oculus`, `chloride`, `ModernUI`, `gbf` |
| DeceasedCraft – Urban Zombie Apocalypse (Beta 5.10.17) | `embeddium`, `oculus`, `flerovium`, `sodiumextras`, `sodiumdynamiclights`, `sodiumoptionsapi`, `colorwheel`, `createbetterfps`, `immersivelanterns` |
| NightfallCraft – The Casket of Reveries (2.2.9.7) | `embeddium`, `oculus`, `chloride`, `flerovium`, `acceleratedrendering`, `sodiumdynamiclights`, `ModernUI` |
| Society: Sunlit Valley (4.1.5A) | `embeddium`, `oculus`, `flerovium`, `sodiumdynamiclights`, `sodiumoptionsapi`, `colorwheel` |
| Zero to Engineering (0.5.1) | `embeddium`, `oculus`, `mekalus`, `pfg`, `colorwheel`, `createbetterfps`, `sodiumdynamiclights`, `sodiumoptionsapi`, `sodiumoptionsmodcompat` |
| Better MC [FORGE] BMC4 (v62hf) | `xenon`, `oculus`, `IrisSearch`, `iris_shader_folder`, `colorwheel`, `sodiumdynamiclights`, `sodiumoptionsapi`, `immersivelanterns`, `particle_core`, `modpack-update-checker` |

- Conquest는 로딩 중 메모리를 많이 씁니다. ZGC 대신 기본 G1 가비지 컬렉터를 쓰고, 메모리를 10GB 이상 할당하세요.
- 나중에 추가한 모드가 로드되지 않으면, 비활성화된 모드가 필요한 모드인지 확인하세요.

## 빌드

```
./gradlew assemble
```

jar는 `build/libs/VulkanAnchor_1.20.1-<버전>.jar`에 만들어집니다. 빌드에는 JDK 17이 필요하고, 테스트는
`./gradlew test`로 실행합니다.

## 알려진 제한

- FSR 3.1 프레임 생성은 Windows에서만 작동합니다.
- 프레임 생성을 켜면 빠르게 움직이는 몹과 파티클이 겹쳐 보일 수 있습니다.
- 일부 모드의 자체 OpenGL 셰이더는 아직 Vulkan에서 컴파일되지 않습니다. 이때는 크래시 대신 그 효과만 빠집니다.

## 라이선스

VulkanMod, VulkanMod Reforged와 같은 LGPL-3.0-only입니다. [LICENSE](LICENSE)와 [COPYING](COPYING)을 보세요.
[NOTICE](NOTICE)에 저작자와 수정 내역이, [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 서드파티 구성 요소와 그
라이선스가 있습니다. "Vulkan Anchor"는 비공식 빌드이며, 원작 프로젝트의 이름과 로고는 각 소유자의 것입니다.

이 문서는 [README.md](README.md)(영문)의 번역입니다. 내용이 다르면 영문 README가 기준입니다.
