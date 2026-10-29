# VulkanMod-shaded (Minecraft 1.21.1, NeoForge)

[English](README.md) | 한국어

**NeoForge 1.21.1 모드팩**을 위한 Vulkan 렌더러입니다. [VulkanMod](https://github.com/xCollateral/VulkanMod)의 포크인
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged)를 수정한 비공식 빌드입니다. 이 브랜치는 1.21.1 이식판이고,
Forge 1.20.1 빌드는 `main` 브랜치에 있습니다.

> **AI로 만들었습니다.** 이 빌드가 VulkanMod Reforged에 더한 모든 변경(호환성 수정, 후처리 셰이더, Distant Horizons 지원,
> 프레임 생성, 1.21.1 이식, 이 README)은 mockmock69401의 지시와 게임 내 테스트 아래 AI 코딩 도구(Anthropic의 Claude,
> Claude Code)가 작성했습니다. 이 점을 감안해 읽고, 테스트하고, 문제를 알려 주세요.
>
> Rindw(VulkanMod Reforged), xCollateral과 VulkanMod 기여자들, Distant Horizons 개발자, 아래에 나오는 모드의 개발자,
> AMD가 **보증하거나 승인한 빌드가 아닙니다.**

## 목적

대형 모드팩은 OpenGL을 기준으로 만들어집니다. 많은 모드가 OpenGL로 직접 그리기 때문에(자체 셰이더, 프레임버퍼, HUD·지도
모드, 화면 효과) Vulkan 렌더러는 이것을 변환하거나 우회해야 합니다. 이 빌드는 그런 모드팩을 Vulkan으로 돌리기 위해
만들어졌고, 그 위에 셰이더 느낌의 화면, 원거리 지형, 프레임 생성을 더합니다.

실험적인 빌드입니다. 호환성과 기능은 아직 완전하지 않습니다.

## VulkanMod Reforged에 더한 것

- **모드팩 호환성 수정**: 모드가 가진 OpenGL 셰이더를 더 많은 경우에 변환합니다(Lodestone 같은 지오메트리 셰이더,
  GLSL 인터페이스 블록, import한 셰이더 파일 안의 uniform, `%` 나머지 연산). AAA Particles, Vivecraft, FancyMenu,
  Iceberg용 수정과 아래 모드팩을 테스트하며 찾은 렌더러 안정성 수정도 들어 있습니다. Xaero's Minimap과 World Map,
  JourneyMap, FTB Quests가 작동하는 것을 확인했습니다.
- **셰이더(후처리)**: VulkanMod 비디오 설정의 "Shaders" 페이지에 있습니다. 햇빛 음영과 그림자, 물 반사와 물결(비가 오면 빗방울 파문), 안개와
  빛줄기, 블룸, 구름, 앰비언트 오클루전, 식물 흔들림, 손에 든 광원, 안티에일리어싱(SMAA, TAA). 셰이더팩이나 LOD 모드가
  없어도 됩니다. 패스와 셰이더는 요구사항 문서와 공개된 그래픽스 문헌을 바탕으로 이 빌드를 위해 새로 작성했으며,
  셰이더팩 코드는 들어 있지 않습니다.
- **빛 색**(Shaders → Lighting(조명) 페이지): 해가 낮게 뜬 아침·저녁에 가장 따뜻한 햇빛(햇빛 따뜻함), 광원 가까이는
  더 밝고 멀어질수록 빨리 어두워지는 횃불·빛 블록의 주황빛(횃불빛 색), 밤의 푸른 달빛(빛 색에 포함). 각각 슬라이더가
  있고, 0이면 이전 색 그대로입니다.
- **높은 셰이더 프리셋 수준의 그림자와 반사**: 32블록 안의 몹·플레이어·떨어진 아이템이 태양 그림자를 드리우고(엔티티
  그림자), 지형의 선명한 그림자가 2~10청크까지 닿습니다(그림자 거리). 둘 다 Lighting(조명) 페이지에 있습니다. 광택 돌·석영·
  유약 테라코타·단단한 얼음·보석 블록과 금속 블록은 해와 달빛에 반짝이고 하늘과 주변 풍경을 부드럽게 비춥니다(금속은 제
  색으로). Water(물) 페이지의 블록 반사: 끄기, 하이라이트, 반사.
- **발광**(Shaders → Lighting(조명) 페이지): 용암, 마그마, 발광석 등 빛 블록이 블룸으로 번지며 빛나고(블록 발광), 네더 석영
  광석의 흰 알갱이가 어두운 곳에서도 은은하게 빛납니다(광석 발광, 다른 광석은 바닐라 그대로). 물도 더 투명해져 호수와 강의 바닥이
  보입니다(Water Depth를 0으로 하면 바닐라 물 그대로).
- **날씨 안개**(Shaders → Atmosphere(대기) 페이지): 비와 뇌우가 오면 높이와 상관없이 먼 곳이 푸른 회색 안개로 흐려지고,
  가까운 곳은 선명하게 남습니다(셰이더 팩처럼; 날씨 안개 0이면 맑은 날의 안개 그대로). 햇빛 받은 눈처럼 밝은 면도 하얗게
  날아가지 않고 질감이 남습니다.
- **Distant Horizons 지원**: Distant Horizons의 LOD를 Vulkan 렌더러가 그리고, 바닐라 지형 아래에 합성하며, 셰이더의
  조명과 그림자를 받습니다. 출시된 Distant Horizons **3.3.1 이상**에서 작동하고(Distant Horizons 내부의 렌더 정의를
  대신 맡습니다), Distant Horizons의 렌더 API가 출시되면(3.3.5) 그것으로도 작동합니다.
- **프레임 생성**(Shaders → Image 페이지의 "Frame Generation" 항목): 두 가지 중 하나를 고릅니다.
  - **FSR 3.1 Frame Generation**: AMD FidelityFX Super Resolution 3.1 프레임 생성입니다(실제 프레임 사이에 생성한
    프레임 하나를 넣고, HUD는 선명하게 유지). Windows x64와 Vulkan 1.3을 지원하는 GPU에서만 쓸 수 있고, 그 밖의
    환경에서는 이유와 함께 비활성화됩니다.
  - **Optical Flow**: 카메라 움직임에 맞춰 직전 프레임을 다시 투영하는 내장 생성기입니다.
- **성능**: 애니메이션 텍스처는 화면에 보일 때만 갱신하고, 대형 모드팩에서 보이던 멈춤을 고쳤습니다.
- **FPS 표시**(비디오 설정 → FPS 표시, 기본은 끄기): 최근 5초의 프레임을 왼쪽 위에 보여줍니다. 평균만, 평균과
  최소·최대, 평균과 1%·0.1% low 중에서 고를 수 있습니다.

## 요구 사항

- Minecraft 1.21.1과 NeoForge 21.1 (21.1.228, 21.1.244, 21.1.250, 21.1.259에서 테스트)
- Java 21
- Vulkan 1.2를 지원하는 GPU와 드라이버
- FSR 3.1 프레임 생성: Windows x64와 Vulkan 1.3을 지원하는 GPU
- 선택, LOD용: **Distant Horizons 3.3.1 이상** (3.3.1과 3.3.3에서 테스트).
  1.21.1용 Distant Horizons 3.3.0은 실행은 되지만 LOD가 그려지지 않습니다. 이 버전의 NeoForge 빌드는 대신 맡을 수
  없는 구조여서, 로그에 업데이트하라는 안내가 남습니다. `main`의 1.20.1 빌드도 같은 버전을 요구합니다.
  **Lost Cities**가 설치되어 있으면 Distant Horizons의 먼 지형 생성을 INTERNAL_SERVER 모드로 돌립니다(메모리에서만,
  `DistantHorizons.toml`은 그대로). 더 빠른 모드에서는 Lost Cities 건물을 만들다 실패해 채팅에 "There was an error
  generating a chunk"가 뜨고 LOD에 구멍이 생기기 때문입니다. 대신 먼 지형이 더 천천히 채워지고, 생성된 청크가 월드에
  저장됩니다.

## 설치

1. 배포용 jar 하나(`VulkanMod-shaded_1.21.1-<버전>.jar`)를 모드팩의 `mods` 폴더에 넣습니다.
2. 다른 VulkanMod나 VulkanMod Reforged jar는 지우거나 비활성화합니다.
3. 아래 표에서 해당 모드팩의 모드를 비활성화합니다. 일반적으로 Sodium, Iris와 그 애드온, OpenGL 렌더링을 바꾸거나
   프레임을 직접 표시하는 모드는 Vulkan 렌더러와 충돌합니다.

프레임 생성은 GPU 시간을 씁니다. 켜면 실제로 렌더링하는 프레임이 줄고, 프레임이 높을 때는 전체 표시 프레임이 오히려
줄 수 있습니다. 프레임이 모니터 주사율보다 낮을 때 쓰세요. 몹, 파티클, HUD는 실제 프레임 속도로 움직입니다.

## 테스트한 모드팩: 비활성화할 모드

아래 버전으로 CurseForge에서 테스트했습니다(설치한 팩의 복사본에서 새 월드, HUD, 지도, 퀘스트북 확인. 2026-10-25 클린 설치본으로 다시 확인). "비활성화"는
jar 이름을 `<이름>.jar.disabled`로 바꾸는 것입니다(CurseForge의 "disable" 스위치도 같은 일을 합니다).

| 모드팩 (테스트한 버전) | 비활성화 |
|---|---|
| Tensura Neo Otherworld (1.1.2a) | `sodium`, `sodium-extra`, `reeses-sodium-options`, `iris`, `chloride`, `flerovium`, `Kerria` |
| Better MC [BMC5] (v53) | `sodium`, `sodiumextras`, `sodiumdynamiclights`, `sodiumleafculling`, `sodiumoptionsapi`, `sodiumoptionsmodcompat`, `reeses-sodium-options`, `iris`, `iris_shader_folder`, `IrisSearch`, `colorwheel`, `immersivelanterns`, `modpack-update-checker` |
| All the Mods 10 (8.3) | `sodium`, `sodium-extra`, `iris`, `IrisSearch`, `colorwheel` |

참고:

- Kerria와 Modpack Update Checker는 Vulkan 렌더러가 대체하는 OpenGL 텍스처 업로드에 끼어들어, 게임 시작 시 크래시를
  냅니다.
- Immersive Lanterns와 Colorwheel은 Sodium Dynamic Lights나 Iris가 있어야 해서 함께 비활성화합니다.
- ComputerCraft 모니터는 기본 모니터 렌더러("best")에서 작동합니다. "tbo" 렌더러 옵션은 작동하지 않습니다.
- 나중에 추가한 모드가 로드되지 않으면, 비활성화한 모드가 필요한 모드인지 확인하세요.

## 빌드

```
./gradlew assemble
```

jar는 `build/libs/VulkanMod-shaded_1.21.1-0.1.0.jar`에 만들어집니다(옆의 `-sources.jar`는 개발용입니다). 테스트는
`./gradlew test`로 실행합니다. 빌드에는 JDK 21이 필요하고, NeoForge와 LWJGL을 포함한 나머지는 Gradle이 내려받습니다.
`tools/`의 FSR 구조체 레이아웃 확인 프로그램은 Windows에서 MSVC 2022로 컴파일했고 그 출력이 저장소에 들어 있어서,
빌드에 C 컴파일러는 필요하지 않습니다.

## 프로젝트 구조

| 경로 | 내용 |
|---|---|
| `src/main/java/net/vulkanmod/` | VulkanMod Reforged: Vulkan 백엔드(`vulkan/`, 모드 셰이더 변환기 `vulkan/shader/parser/` 포함), 렌더러(`render/`), 믹스인(`mixin/`), 설정과 호환성 코드 |
| `src/main/java/net/vulkanmod/visuals/` | 이 빌드가 렌더러에 더한 모든 것 |
| `  render/` | 후처리 패스와 그 계산(조명, 그림자, 반사, 안개, 구름, 블룸, 앰비언트 오클루전, TAA) |
| `  vk/` | 패스에서 쓰는 작은 Vulkan 도우미(이미지, 버퍼, 업로드/다운로드 스트림, 파이프라인) |
| `  api/`, `lod/` | LOD 렌더러가 구현하는 인터페이스, LOD 타깃과 합성 |
| `  dh/` | Distant Horizons 지원: 렌더 API 등록(3.3.5+), 3.3.1~3.3.4 렌더 정의 대체(`Legacy*`, 믹스인은 `mixin/visuals/legacydh/`), 버텍스 버퍼, 컬링, 그림자 캐스터 |
| `  framegen/` | Optical Flow 프레임 생성 |
| `  fsr/` | AMD FSR 3.1 프레임 생성: 라이브러리 로더, 구조체 레이아웃, 스왑체인 연결, 프레임별 구동 |
| `  config/` | "Shaders" 설정 페이지와 저장되는 옵션 |
| `  dev/` | 스크립트 테스트 파일럿, `-Dvulkanmod.visuals.pilot=<파일>`일 때만 작동 |
| `src/main/java/dev/vulkanfix/` | 믹스인으로 유지하는 모드팩 호환성 수정(`vulkanfix.mixins.json`) |
| `src/main/resources/assets/vulkanmod/visuals/shaders/` | GLSL 셰이더(후처리, 그림자, LOD 지형, 프레임 생성) |
| `src/main/resources/natives/windows-x64/` | AMD FidelityFX Vulkan 라이브러리(미리 빌드되고 서명된 원본 그대로)와 라이선스 |
| `src/test/` | JUnit 테스트(계산, 구조체 레이아웃, 셰이더 변환기, 설정과 연결 확인; 게임 불필요) |
| `third_party/` | SMAA 소스, FidelityFX 헤더와 출처 정보, LWJGL 라이선스 |
| `tools/` | FidelityFX 구조체 레이아웃을 고정하는 MSVC 확인 프로그램과 그 출력 |
| `branding/` | CurseForge 아이콘(512, 1024)과 그것을 그리는 스크립트 |
| `libs/DistantHorizonsApi-7.3.0.jar` | Distant Horizons API, 컴파일에만 사용(jar에 포함하지 않음). Distant Horizons 3.3.3 자체는 빌드할 때 Modrinth Maven에서 받으며 역시 컴파일에만 사용 |
| `libs/flywheel-neoforge-1.21.1-1.0.6.jar` | Flywheel, 컴파일에만 사용(VulkanMod Reforged에서 이어받음, jar에 포함하지 않음) |

## 알려진 한계

- FSR 3.1 프레임 생성은 Windows에서만 작동합니다. 테스트한 드라이버 중 하나에서는 포함된 FidelityFX 라이브러리의 내부
  상태를 읽는 우회가 필요했기 때문에, 그 코드를 고치지 않고 다른 버전의 라이브러리로 바꿀 수 없습니다(라이브러리가
  다르면 우회는 스스로 꺼집니다).
- 프레임 생성 중에도 몹과 파티클은 실제 프레임 속도로 움직여서, 빠르게 움직이는 물체가 겹쳐 보일 수 있습니다.
- 일부 모드의 OpenGL 셰이더는 아직 변환되지 않습니다(예: 텍스처 버퍼를 읽는 셰이더). 그런 효과는 크래시 대신 일반
  셰이더로 대체됩니다.

## 라이선스

VulkanMod, VulkanMod Reforged와 같은 LGPL-3.0-only입니다. [LICENSE](LICENSE)(LGPLv3)와 [COPYING](COPYING)(LGPLv3가
포함하는 GPLv3 원문)을 보세요. [NOTICE](NOTICE)에 저작자, 수정 내용과 날짜가 있고,
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 서드파티 구성 요소(LWJGL, AMD FidelityFX SDK, SMAA,
Distant Horizons, Flywheel)와 그 라이선스가 있습니다.

"VulkanMod-shaded"는 비공식 빌드입니다. 코드 라이선스는 상표권을 주지 않으며, 업스트림의 이름과 로고는 각 소유자의
것입니다.

이 문서는 [README.md](README.md)(영문)의 번역입니다. 내용이 다르면 영문 README가 기준입니다.
