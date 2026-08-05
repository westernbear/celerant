# Celerant VRM

Minecraft 26.2 Fabric 클라이언트에서 MCglTF로 로컬 VRM 0.x/1.0 모델을 로드하고, Iris가 변환한 entity shader를 메모리에서 VRM 드로우에만 카툰 스타일로 보정합니다.

## 요구 모드

- Fabric Loader 0.19.3+
- Fabric API 0.156.0+26.2
- [MCglTF 26.2-Fabric-2.3.0.0](https://github.com/westernbear/MCglTF-1.20.4/releases)
- Iris 1.11.2+와 Iris가 요구하는 Sodium 0.9.x

MCglTF와 Iris는 Celerant JAR에 포함하지 않습니다. Gradle은 MCglTF 릴리스 태그를 JitPack에서 빌드 의존성으로 사용합니다.

## 사용

1. self-contained GLB 형식의 `.vrm` 파일을 `.minecraft/celerant/models/`에 둡니다.
2. 월드에서 `/celerant vrm load <파일명>`을 실행합니다.
3. `/celerant vrm here`, `/celerant vrm scale <값>`, `/celerant vrm expression <이름> <0..1>`로 조정합니다.
4. `/celerant vrm info` 또는 `/celerant vrm unload`를 사용합니다.

로더는 디렉터리 탈출, 심볼릭 링크 탈출, 256 MiB 초과 파일, 외부 참조가 필요한 glTF를 거부합니다.

## ShaderPack 경계

Celerant는 ShaderPack ZIP이나 GLSL 원본을 수정·저장·재배포하지 않습니다. Iris의 GLSL 변환이 끝난 런타임 문자열에 `celerant_` 심볼만 주입하며, 지원하지 않는 stage 구성이나 앵커를 만나면 원본 결과를 그대로 사용합니다. 카툰 수학은 특정 게임 또는 ShaderPack 코드를 복사하지 않은 일반적인 ramp, rim, specular-band 기법입니다.

현재 런타임 패치는 Iris의 표준 vertex+fragment entity 프로그램 중 단일 색상 attachment를 쓰는 팩을 대상으로 합니다. geometry/tessellation stage 또는 여러 G-buffer attachment를 쓰는 deferred 팩은 데이터 계약을 훼손하지 않도록 패치하지 않습니다.

`VRMC_materials_mtoon`은 현재 모델 전체에 적용되는 일반 NPR ramp/rim/specular 보정으로 처리합니다. 재질별 shade texture, matcap, outline 폭까지 정확히 전달하려면 MCglTF의 primitive별 material 신호 경로가 추가로 필요합니다.

VRM 모델과 사용자가 설치한 ShaderPack의 라이선스·이용 조건은 각각 사용자가 확인해야 합니다.

Celerant는 Iris에 포함된 AGPL-3.0 `glsl-transformer` API를 직접 사용하므로 AGPL-3.0-only로 배포됩니다. 해당 라이브러리는 Celerant JAR에 중복 포함하지 않습니다.

## 테스트

Linux에서 실제 Fabric 클라이언트, Iris, Sodium을 llvmpipe로 실행해 전체 사용자 흐름을 검증합니다.

```bash
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --offline
```

테스트용 VRM과 ShaderPack은 실행 디렉터리에 동적으로 생성되며 배포 JAR에는 포함되지 않습니다.

## CI와 릴리스

main 브랜치와 pull request는 Gradle 빌드 및 실제 Xvfb 클라이언트 게임 테스트를 실행합니다. 새 GitHub Release는 깨끗하고 원격과 동기화된 main 브랜치에서 다음 명령으로 생성합니다.

```bash
./scripts/release.sh 1.0.1
```

스크립트가 버전 변경, 빌드, 커밋, 주석 태그와 main의 원자적 push를 수행합니다. `v*` 태그의 Release workflow가 클라이언트 게임 테스트를 다시 통과한 뒤 모드 JAR과 SHA-256 체크섬을 게시합니다.
