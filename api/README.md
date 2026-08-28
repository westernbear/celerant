# Celerant API

Compile-only surface for mods that integrate with Celerant at runtime.

## Gradle

```gradle
dependencies {
    compileOnly "io.github.westernbear.celerant:celerant-api-26.2:26.2-1.3.0"
    // runtime: install Celerant-Fabric or Celerant-NeoForge for the active loader
}
```

During local development, `:api` from this repository replaces the Maven coordinate.

## Entry point

```java
import io.github.westernbear.celerant.api.CelerantApi;
import io.github.westernbear.celerant.api.VrmAvatarHandle;

CelerantApi api = CelerantApi.get();
api.localAvatar().ifPresent(avatar -> {
    String path = avatar.modelPath();
    float scale = avatar.scale();
});
api.registerAvatarListener(event -> { /* load / unload / avatar toggle */ });
boolean paper = api.isPaperPluginPresent();
```

## Types

| Type | Role |
|------|------|
| `CelerantApi` | Facade registered by Fabric/NeoForge bootstrap |
| `VrmAvatarHandle` | Read-only local avatar state (path, scale, expression) |
| `AvatarLifecycleListener` | Load, unload, and avatar-mode lifecycle callbacks |
| `LocoParams` | Read-only remote-player locomotion snapshot |

The API JAR depends on Minecraft and optionally `mcgltf-api`. It does not bundle loader code or OneConfig.
