{
  description = "PipePipe Android development shell (JDK 25 + Android SDK 37)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEachSystem = f: nixpkgs.lib.genAttrs systems (system: f system);
    in
    {
      devShells = forEachSystem (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          jdk = pkgs.temurin-bin-25;
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37.0" ];
            # AGP's default build-tools (used when build.gradle doesn't pin one)
            # plus the newest for good measure.
            buildToolsVersions = [ "36.0.0" "37.0.0" ];
            includeNDK = false;
            includeEmulator = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;
          # AGP downloads its own aapt2 from Maven (unpatched ELF) which the
          # NixOS stub loader refuses to run. Rewrite its interpreter/rpath.
          aapt2FixRpath = pkgs.lib.makeLibraryPath [
            pkgs.stdenv.cc.cc.lib
            pkgs.stdenv.cc.libc
            pkgs.zlib
          ];
        in
        {
          default = pkgs.mkShell {
            name = "pipepipe-dev";
            packages = [
              jdk
              androidSdk
              pkgs.patchelf
            ];

            JAVA_HOME = jdk.home;
            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

            shellHook = ''
              export PATH="$ANDROID_HOME/platform-tools:$PATH"
              # Patch AGP-fetched aapt2 binaries (see above) so they run on NixOS.
              # Runs on every shell entry to cover Gradle cache updates.
              for aapt in "''${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/*/transforms/*/transformed/aapt2-*-linux/aapt2; do
                [ -e "$aapt" ] || continue
                if ! "$aapt" version >/dev/null 2>&1; then
                  echo "patching $aapt for NixOS"
                  patchelf --set-interpreter "${pkgs.stdenv.cc.bintools.dynamicLinker}" \
                    --set-rpath "${aapt2FixRpath}" "$aapt"
                fi
              done
              echo "PipePipe devshell: java $(java -version 2>&1 | head -n 1), SDK $ANDROID_HOME"
            '';
          };
        });
    };
}
