{
  description = "PipePipe Android development shell (JDK 25 + Android SDK 37)";

  inputs = {
    nixpkgs.url = "nixpkgs/unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          # Centralized Android SDK configuration.
          # AGP 9.2 uses build-tools 36.0.0 by default (its compat table);
          # platform 37.0 matches compileSdk 37 in PipePipeClient.
          buildToolsVersion = "37.0.0";
          defaultBuildToolsVersion = "36.0.0";
          platformVersions = [ "37.0" ];

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            buildToolsVersions = [ defaultBuildToolsVersion buildToolsVersion ];
            inherit platformVersions;
            includeNDK = false;
            includeEmulator = false;
            includeSystemImages = false;
          };

          androidSdk = androidComposition.androidsdk;
          androidSdkRoot = "${androidSdk}/libexec/android-sdk";
          # Use the nixpkgs-patched SDK aapt2 instead of the unpatched ELF
          # AGP would otherwise fetch from Maven (fails on NixOS stub-ld).
          aapt2Path = "${androidSdkRoot}/build-tools/${buildToolsVersion}/aapt2";
          jdk = pkgs.temurin-bin-25;
        in
        {
          default = pkgs.mkShell {
            name = "pipepipe-dev-shell";

            buildInputs = [
              jdk
              androidSdk
            ];

            JAVA_HOME = jdk.home;
            ANDROID_HOME = androidSdkRoot;
            ANDROID_SDK_ROOT = androidSdkRoot;
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

            shellHook = ''
              REPO_ROOT="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null | sed -E 's#/\.git(/.*)?$##')"
              if [ -z "$REPO_ROOT" ] || [ ! -d "$REPO_ROOT" ]; then
                REPO_ROOT="''${PWD}"
              fi
              # Keep all Gradle/Android caches inside the repo, never ~/.gradle.
              export GRADLE_USER_HOME="''${REPO_ROOT}/.gradle-home"
              export ANDROID_USER_HOME="''${REPO_ROOT}/.gradle-home/android"
              echo "PipePipe Development Environment Loaded"
              echo "Java Version: $(${jdk}/bin/java -version 2>&1 | head -n 1)"
              echo "ANDROID_HOME: $ANDROID_HOME"
              echo "GRADLE_USER_HOME: $GRADLE_USER_HOME"
            '';
          };
        }
      );
    };
}
