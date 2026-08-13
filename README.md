# Celerant VRM

Minecraft 26.2 Fabric 클라이언트에서 MCglTF로 로컬 VRM 0.x/1.0 모델을 로드하고, Iris ShaderPack의 최종 장면 위에 선택적 VRM `ToonShader` 재질을 합성합니다.

## 요구 모드

- Fabric Loader 0.19.3+
- Fabric API 0.156.0+26.2
- [MCglTF 26.2-Fabric-2.3.2.6](https://github.com/westernbear/MCglTF-1.20.4/releases/tag/v26.2-Fabric-2.3.2.6)
- Iris 1.11.2+와 Iris가 요구하는 Sodium 0.9.x
- [OneConfig 1.1.6 for Fabric 26.2](https://modrinth.com/mod/oneconfig/version/UCFu181L)와 OneConfig가 요구하는 Compose Multiplatform, Fabric Language Kotlin

외부 모드는 Celerant JAR에 포함하지 않습니다. Gradle은 MCglTF 릴리스 태그를 JitPack에서 빌드 의존성으로 사용합니다.

## 사용

1. 월드에서 `V`를 눌러 OneConfig 기반 **Celerant VRM** 제어 센터를 엽니다. 단축키는 Minecraft Controls 또는 OneConfig의 전역 Keybinds 화면에서 바꿀 수 있습니다.
2. **VRM model**에서 self-contained GLB 형식의 `.vrm` 파일을 선택하고 **Load**를 누릅니다.
3. 같은 화면에서 배치 위치, 스케일, 표정과 가중치, 로컬 플레이어 교체, Iris toon shading을 조정합니다. 처리 결과와 오류는 OneConfig 알림으로 표시됩니다.
4. **Runtime status**로 현재 모델·리깅·표정·ShaderPack 상태를 확인하고 **Unload**로 vanilla 플레이어를 복원합니다.

기존 `/celerant vrm ...` 명령도 자동화와 문제 해결용으로 유지됩니다. 명령 기반 로드는 `.minecraft/celerant/models/` 아래의 상대 경로만 받지만, OneConfig 파일 선택기는 사용자가 명시적으로 고른 외부 `.vrm` 절대 경로를 안전하게 검사해 사용할 수 있습니다.

로더는 디렉터리 탈출, 심볼릭 링크 탈출, 256 MiB 초과 파일, 외부 참조가 필요한 glTF를 거부합니다. Humanoid node의 glTF matrix는 손실 없이 TRS로 분해되는 경우 지원하며, shear·특이행렬처럼 안전하게 애니메이션할 수 없는 변환은 거부합니다.

아바타 모드는 1인칭과 3인칭에서 같은 VRM을 사용합니다. VRM first-person annotation으로 머리 메시를 가리고, Minecraft `PlayerModel`의 시선·대기·걷기/달리기·공격/아이템·웅크리기·탑승·수영·겉날개 자세를 humanoid rig에 매 프레임 전달합니다. 일반 점프는 실제 수직 속도에 따라 상승과 하강 관절 포즈를 따로 합성합니다. 최소 `hips`, `spine`, `head`, 양쪽 upper/lower arm·hand, upper/lower leg·foot 15개 관절과 올바른 부모 계층이 필요합니다.

리타게팅은 [pixiv/three-vrm의 normalized humanoid 설계](https://github.com/pixiv/three-vrm/blob/cbd9a77a0d17f0099fdac5dcc2b4c7ee30342869/packages/three-vrm-core/src/humanoid/VRMHumanoidRig.ts)를 기준으로 Java/JOML에서 독립 구현했습니다. 애니메이션 delta를 각 bone의 부모 rest 좌표계로 변환한 뒤 원본 rest pose에 합성하고, hips 이동도 부모 월드 변환의 역행렬을 거쳐 적용하므로 bone roll·회전/스케일된 부모를 보존합니다.

유효한 비영점 VRM0 `firstPersonBoneOffset`은 VRM0의 Z축을 glTF 좌표로 변환해 1인칭 카메라 anchor로 사용하며, 0 또는 누락 값은 Minecraft 눈 위치로 안전하게 폴백합니다.

현재 교체 대상은 로컬 플레이어입니다. 다른 플레이어의 VRM 네트워크 동기화, IK/VR 트래커, spring-bone 물리는 포함하지 않습니다. vanilla 장비·손 아이템·망토·겉날개 렌더 레이어도 중복 메시를 피하기 위해 아바타 모드에서 숨기지만 해당 자세는 VRM rig에 반영됩니다.

## ToonShader와 ShaderPack 경계

Celerant는 ShaderPack ZIP, GLSL 원본, Iris가 변환한 프로그램과 G-buffer attachment를 수정·저장·재배포하지 않습니다. Iris의 `finalizeLevelRendering`이 끝난 뒤 MCglTF의 분리된 `ToonShader` 렌더러가 선택된 VRM primitive를 자체 HDR color/depth target에 렌더링하고 ShaderPack 장면 depth와 대조한 뒤 main color target에 premultiplied alpha로 합성합니다. 팩 이름·아카이브 해시·임계값별 분기는 없으며, 알 수 없는 ShaderPack 저장 형식에 codec을 추측하지 않습니다.

일반 MCglTF 사용자는 계속 표준 glTF/MToon 경로만 사용합니다. Celerant가 `RenderedGltfModel.MTOON_OVERLAY_REQUEST`로 명시 요청한 모델만 ToonShader queue에 들어가므로 MCglTF 전체를 툰 전용 렌더러로 바꾸지 않습니다. ShaderPack이 꺼진 경우에는 MCglTF의 기존 managed MToon pass가 사용됩니다.

`model.vrm.toon.json` v2 sidecar가 있으면 material 또는 mesh primitive별 LightMap/ramp, 분리된 face LightMap·shadow SDF와 head forward/right, authored smooth normal 또는 명시적 생성, normal map tangent, 금속/비금속 specular와 matcap, emission, blush, depth rim, outline width texture·vertex alpha·거리 스케일·재질별 색, base/outline screen offset을 명시할 수 있습니다. sidecar가 없는 표준 MToon 재질도 중립 fallback으로 동작하지만, LightMap·face SDF처럼 VRM 표준에 없는 데이터는 다른 texture에서 추측하지 않습니다. 상세 스키마는 MCglTF README의 **Optional ToonShader sidecar** 절을 따릅니다.

VRM 모델과 사용자가 설치한 ShaderPack의 라이선스·이용 조건은 각각 사용자가 확인해야 합니다.

Celerant는 저장소의 `LICENSE`에 따라 AGPL-3.0-only로 배포됩니다.

## 테스트

Linux에서 실제 Fabric 클라이언트, OneConfig, Iris, Sodium을 llvmpipe로 실행해 전체 사용자 흐름을 검증합니다. Client GameTest는 실제 `V` 단축키로 OneConfig 화면을 열고 Compose 접근성 트리에서 현재 컨트롤 좌표를 찾은 뒤, 마우스·키보드 입력으로 카테고리, 파일 선택, 숫자·텍스트·슬라이더·스위치, 모든 액션 버튼과 화면 재진입을 조작합니다. 잘못된 파일/정상 파일 로드, 배치, 스케일, 표정, 아바타, 상태, toon 토글, 언로드 결과는 런타임 상태와 OneConfig 알림으로 단언합니다. Headless 환경에서는 실제 파일 선택 버튼까지 클릭한 뒤 OS native 대화상자의 반환값만 테스트 mixin으로 대체하고, 요청된 제목과 `*.vrm` 필터도 검증합니다. X11에 실제 표시된 월드/제어 센터 프레임도 OS 수준에서 캡처해 UI가 창의 40% 이상을 바꾸는지 확인합니다. 화면을 닫은 뒤에도 Iris pipeline과 ShaderPack이 유지되어야 합니다.

같은 Client GameTest는 고정된 카메라에서 같은 VRM을 ToonShader ON/OFF/복구 ON으로 렌더링합니다. 장면과 모델 경계가 유지되고, 두 ON 프레임에 공통으로 안정된 모델 픽셀 중 30% 이상이 OFF에서만 변해야 통과합니다.

```bash
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --offline
```

테스트용 VRM과 ShaderPack은 실행 디렉터리에 동적으로 생성되며 배포 JAR에는 포함되지 않습니다. 로컬 모델을 지정하면 같은 이름의 `.toon.json`과 참조 PNG도 테스트 실행 디렉터리에만 복사됩니다.

로컬 VRM의 햇빛 카툰 렌더링은 환경변수로 모델을 지정해 정오 근접 ON/OFF/복구 ON 픽셀 단언과 함께 아침(0), 정오(6000), 해질녘(12500), 밤(18000) 네 시각의 전·후면, 1인칭, 보행과 점프 상승/하강 관절 프레임을 1280×720으로 캡처합니다. 이 경로는 저작권이 있는 모델을 CI에 포함하지 않으면서 실제 재질과 스타일을 로컬 승인하기 위한 것이며, 위의 합성 VRM CI 단언을 대체하지 않습니다.

```bash
CELERANT_VISUAL_VRM=/absolute/path/model.vrm \
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --offline
```

비교 이미지는 `build/run/clientGameTest/screenshots/`에 남고 모델은 테스트 종료 후 삭제됩니다. 로컬 VRM/툰 이미지는 배포물에 포함하지 않으며, 합성 OneConfig 화면 증거 2장만 main/PR CI artifact로 업로드합니다.

### ShaderPack 매트릭스

아래 명령은 ZIP을 임시 복사해 원본 SHA-256 보존, ToonShader ON/OFF/복구 캡처, Iris 활성 상태, entity 프로그램 비변경 수치와 12-frame 중앙값·p95·p99을 `celerant-shaderpack-matrix.tsv`로 기록합니다. 실제 GPU에서 실행해야 성능 수치를 판단할 수 있습니다.

```bash
CELERANT_SHADERPACK_DIR=/absolute/path/to/shaderpack-zips \
CELERANT_VISUAL_VRM=/absolute/path/model.vrm \
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --offline
```

2026-08-13 기준 Iris 1.11.2 / MCglTF 2.3.2.6에서 현재 Modrinth 26.2 호환판인 BSL R10.1.3, Complementary Reimagined r5.8.1, Complementary Unbound r5.8.1을 Jingburger VRM과 실제 pack 활성 상태로 직접 검사했습니다. 공식 `UnityGenshinToonShader` 이미지와 높이를 맞춘 비교판, ON/OFF/restored, 반대 방향 얼굴광, 원본 및 4× 최근접 크롭을 모두 직접 확인했으며 세 팩의 얼굴 SDF·재질 램프·smooth normal·외곽선·rim/specular·합성 게이트가 통과했습니다. 세 팩 모두 source SHA-256 보존, `patched_entity_programs=0/9`, restored 픽셀 안정성 1.000을 기록했습니다. llvmpipe 1280×720의 ON/OFF 중앙 프레임 시간은 BSL 902/751 ms, Reimagined 756/633 ms, Unbound 807/670 ms였습니다. 이는 소프트웨어 렌더러 수치이며 실제 GPU 성능 기준은 아닙니다.

## CI와 릴리스

main 브랜치와 pull request는 Gradle 빌드 및 실제 Xvfb 클라이언트 게임 테스트를 실행합니다. 새 GitHub Release는 깨끗하고 원격과 동기화된 main 브랜치에서 다음 명령으로 생성합니다.

```bash
./scripts/release.sh 1.2.1
```

스크립트가 버전 변경, 빌드, 커밋, 주석 태그와 main의 원자적 push를 수행합니다. `v*` 태그의 Release workflow가 클라이언트 게임 테스트를 다시 통과한 뒤 모드 JAR과 SHA-256 체크섬을 게시합니다.
