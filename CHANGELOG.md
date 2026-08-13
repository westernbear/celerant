# Changelog

## Unreleased

## 1.2.1

- MCglTF 26.2-Fabric-2.3.2.6의 분리된 Genshin-style ToonShader 경로를 사용해 공식 face SDF·LightMap·ramp, smooth normal, 재질별 outline, rim/specular와 HDR 합성을 지원합니다.
- BSL R10.1.3, Complementary Reimagined r5.8.1, Complementary Unbound r5.8.1에서 Jingburger ON/OFF/restored·반대 방향 얼굴광·원본/4× 크롭을 수집하고 공식 레퍼런스 대비 모든 시각·기술 게이트를 검증합니다.
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
- 교정: 1.1.0의 스크린샷은 ShaderPack 로드와 합성 GLSL 변환만 확인했으며, Jingburger 픽셀에 런타임 toon 패치가 적용됐다는 자동 증거는 아니었습니다.
