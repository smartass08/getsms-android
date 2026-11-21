{
  description = "getsms-android development shell (JDK 17 + Android SDK 35)";

  inputs = {
    nixpkgs.url = "flake:nixpkgs";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f system);
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            buildToolsVersions = [ "35.0.0" ];
            platformVersions = [ "35" ];
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;
          androidHome = "${androidSdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk17
              androidSdk
            ];

            JAVA_HOME = "${pkgs.jdk17}";
            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;

            shellHook = ''
              export PATH="${androidHome}/platform-tools:${androidHome}/build-tools/35.0.0:$PATH"
            '';
          };
        });
    };
}
