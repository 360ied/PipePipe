# AGENTS.md

## Dev environment (Nix flake)

This repo builds inside the Nix devshell defined in `flake.nix`. Enter it with:

```sh
nix develop
```

The shell provides everything needed — no system JDK, Gradle, or Android SDK required:

- **JDK**: Temurin 25.0.3 (`JAVA_HOME` is set)
- **Android SDK**: platform `android-37.0`, build-tools `36.0.0` + `37.0.0`,
  platform-tools, cmdline-tools (`ANDROID_HOME`/`ANDROID_SDK_ROOT` are set,
  licenses pre-accepted)
- AGP is forced onto the nixpkgs-patched SDK `aapt2` via
  `android.aapt2FromMavenOverride` (the Maven-downloaded `aapt2` cannot run on
  NixOS, so do not remove this override)

Gradle state stays inside the repo: `GRADLE_USER_HOME` points at
`./.gradle-home` (gitignored). Do not use or rely on `~/.gradle`.

## Building

```sh
cd PipePipeClient
./gradlew assembleDebug --stacktrace -DskipFormatKtlint
```

APKs land in `PipePipeClient/app/build/outputs/apk/debug/` (one per ABI).
Always use the Gradle wrapper (`./gradlew`); never a system `gradle`.
