# Celerant Paper plugin

Relays Hardened avatar ciphertext chunks, session keys, and locomotion parameter blobs for the Fabric Celerant client.

## Important

- **Never stores plaintext VRM** — only opaque plugin-message bytes.
- Fabric Celerant currently targets **Minecraft 26.2**. Use a Paper build that matches your deployment; this module compiles against Paper API 1.21.4 as a stand-in until 26.2 Paper is published.
- Clients without the Fabric mod ignore these channels.

## Build

```bash
cd celerant-paper && ./gradlew build
# or from a Gradle wrapper copied into this folder
```

Drop the jar into the Paper `plugins/` folder.
