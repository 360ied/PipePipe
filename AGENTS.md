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

## Release signing

`assembleRelease` signs automatically when key material is available.
Resolution order: `KEY_PATH` / `KEY_STORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` env vars first (CI), otherwise
`PipePipeClient/keystore.properties` (local dev fallback):

```properties
storeFile=release.keystore
storePassword=<store password>
keyAlias=pipepipefork
keyPassword=<key password>
```

`release.keystore` and `keystore.properties` are gitignored — never commit
them. Back up the keystore offline; losing it means the app can never be
updated under the same package name. Regenerate with:

```sh
nix develop  # for keytool (openssl is NOT in the devshell; use /dev/urandom)
keytool -genkeypair -keystore PipePipeClient/release.keystore \
  -alias pipepipefork -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=PipePipe Fork, O=ca.bualy.pipepipefork"
```

Note: the keystore is PKCS12, which requires store and key passwords to be
identical. Verify a signed APK with:

```sh
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/<apk>
```
