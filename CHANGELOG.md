# Changelog

## Unreleased

- Migrate to Fabric + NeoForge multiloader layout (`api`, `common`, `fabric`, `neoforge`) aligned with MCglTF 26.2-2.4.0.
- Add public `celerant-api` module (`CelerantApi`, `VrmAvatarHandle`, lifecycle listeners, `LocoParams`).
- Introduce `ICelerantPlatformHelper` / `ServiceLoader` platform bridge for rendering, networking, config, and Iris probes.
- CI matrix: common unit tests, Fabric client GameTest, NeoForge client smoke harness; release ships Fabric, NeoForge, and API JARs.
- Release workflow builds Fabric / NeoForge / API as separate jobs with per-loader Actions artifacts, then attaches only the main loader JARs to the GitHub Release.
- Fix Fabric GameTest public class filename (`CelerantFabricClientGameTest.java`) and NeoForge GameTest `modId` (`celerant_gametest`, no hyphens).

## 1.2.2

- MCglTF 26.2-Fabric-2.3.2.8로 올리고, **Generate Toon assets**를 MCglTF `ToonAssetGenerator`에 연결해 Python 도구와 동일한 sidecar/시트를 게임 안에서 생성합니다.
- Python `vrm_toon_assets.py`와 Java 생성기 모두 얼굴 albedo·기하에서 blush/eye 중심을 추정하도록 바꿨고, Sendagaya CC0 예시 sidecar를 재생성했습니다.
- Unity 스타일 outline view-space expansion, outline-pass depth bias, outline-pass scene-depth/alpha skip으로 끊긴 outline과 alpha speckle을 고쳤습니다.
- 2026-08-25 cross-pack 검증(BSL, Complementary Reimagined, Complementary Unbound)에서 face SDF, material ramp, smooth normal, outline, rim/specular, compositing 게이트를 모두 PASS했습니다.
- README/예시 이미지는 CC0 Sendagaya Shino만 사용하고, 저작권 있는 로컬 검증 VRM은 저장소에 포함하지 않습니다.
- `-PlocalMcgltf=...`와 `scripts/toon-run-pack.sh`의 `LOCAL_MCGLTF`로 로컬 MCglTF JAR을 쓰는 절차를 문서화했습니다.
- CI/Release가 JitPack cold start 대신 GitHub Release JAR을 `-PlocalMcgltf`로 받아 빌드하도록 바꿨습니다.

## 1.2.1

- MCglTF 26.2-Fabric-2.3.2.6의 분리된 Genshin-style ToonShader 경로를 사용해 공식 face SDF·LightMap·ramp, smooth normal, 재질별 outline, rim/specular와 HDR 합성을 지원합니다.
- BSL R10.1.3, Complementary Reimagined r5.8.1, Complementary Unbound r5.8.1에서 로컬 검증 VRM으로 ON/OFF/restored·반대 방향 얼굴광·원본/4× 크롭을 수집하고 공식 레퍼런스 대비 모든 시각·기술 게이트를 검증합니다.
- 대표 ShaderPack을 각각 새 Minecraft JVM과 제한된 memory scope에서 실행하도록 OOM 방지 절차를 문서화합니다.

## 1.2.0

- 모든 VRM 사용자 작업을 OneConfig 1.1.6 제어 센터에서 수행하고, native `.vrm` 선택기, 알림, `V` 단축키와 Minecraft/OneConfig 키 설정을 제공합니다.
- 실제 OneConfig 화면 열기·재진입부터 파일 선택, 입력, 슬라이더, 스위치와 모든 버튼을 pointer/key 이벤트로 조작하고, 로드·배치·표정·플레이어 교체·Iris toon·언로드 결과를 Client GameTest로 검증합니다.

## 1.1.1

- Iris의 프로그램 이름이 포함되지 않은 변환 캐시 밖에서 VRM toon 패치를 적용합니다.
- 실제 `entities_*` 변환 GLSL과 고정 장면 patch ON/OFF/복구 ON GPU 픽셀 차이를 Client GameTest 릴리스 게이트로 검증합니다.
- 환경변수로 지정한 로컬 VRM에도 같은 GPU 대조 검증을 적용하고, 점프 상승과 하강 포즈를 명확히 구분합니다.
- three-vrm의 normalized humanoid 수식을 기준으로 bone roll과 부모 회전/스케일을 보존하고, matrix 기반 humanoid node와 VRM0 first-person Z축을 처리합니다.
- 흰 재질 highlight clipping과 양면 hair-card의 texture-edge 파편을 줄이도록 toon ramp/rim/edge를 조정합니다.
- 교정: 1.1.0의 스크린샷은 ShaderPack 로드와 합성 GLSL 변환만 확인했으며, 로컬 검증 VRM 픽셀에 런타임 toon 패치가 적용됐다는 자동 증거는 아니었습니다.
