# Changelog

## 1.1.1

- Iris의 프로그램 이름이 포함되지 않은 변환 캐시 밖에서 VRM toon 패치를 적용합니다.
- 실제 `entities_*` 변환 GLSL과 고정 장면 patch ON/OFF/복구 ON GPU 픽셀 차이를 Client GameTest 릴리스 게이트로 검증합니다.
- 환경변수로 지정한 로컬 VRM에도 같은 GPU 대조 검증을 적용하고, 점프 상승과 하강 포즈를 명확히 구분합니다.
- three-vrm의 normalized humanoid 수식을 기준으로 bone roll과 부모 회전/스케일을 보존하고, matrix 기반 humanoid node와 VRM0 first-person Z축을 처리합니다.
- 흰 재질 highlight clipping과 양면 hair-card의 texture-edge 파편을 줄이도록 toon ramp/rim/edge를 조정합니다.
- 교정: 1.1.0의 스크린샷은 ShaderPack 로드와 합성 GLSL 변환만 확인했으며, Jingburger 픽셀에 런타임 toon 패치가 적용됐다는 자동 증거는 아니었습니다.
