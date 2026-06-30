{
  description = "Development environment for the Minecraft DLSS/upscaler mod";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    dlssSdk = {
      url = "github:NVIDIA/DLSS/v310.7.0";
      flake = false;
    };
  };

  outputs =
    { nixpkgs, dlssSdk, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          lib = pkgs.lib;
          jdk = pkgs.jdk25;
          llvm = pkgs.llvmPackages_latest;
          vulkanSdk = pkgs.symlinkJoin {
            name = "vulkan-sdk";
            paths = [
              pkgs.glslang
              pkgs.spirv-tools
              pkgs.vulkan-headers
            ];
          };

          runtimeLibs = with pkgs; [
            flite
            alsa-lib
            libGL
            libx11
            libxcb
            libxcursor
            libxext
            libxi
            libpulseaudio
            libxrandr
            libxrender
            libxtst
            libxxf86vm
            openal
            vulkan-loader
            wayland
          ];
        in
        {
          default = (pkgs.mkShell.override { stdenv = llvm.stdenv; }) {
            packages =
              with pkgs;
              [
                gradle_9
                jdk

                bash
                coreutils
                git
                unzip
                which

                cmake
                glslang
                llvm.clang
                llvm.clang-tools
                llvm.lld
                llvm.llvm
                ninja
                spirv-tools
                vulkan-headers
                vulkan-tools
                vulkan-validation-layers
              ]
              ++ runtimeLibs;

            JAVA_HOME = jdk.home;
            CC = "${llvm.clang}/bin/clang";
            CXX = "${llvm.clang}/bin/clang++";
            AR = "${llvm.llvm}/bin/llvm-ar";
            RANLIB = "${llvm.llvm}/bin/llvm-ranlib";
            CMAKE_GENERATOR = "Ninja";
            LD_LIBRARY_PATH = lib.makeLibraryPath runtimeLibs;
            VK_LAYER_PATH = "${pkgs.vulkan-validation-layers}/share/vulkan/explicit_layer.d";

            shellHook = ''
              export CC=clang
              export CXX=clang++
              export AR=llvm-ar
              export RANLIB=llvm-ranlib
              export CMAKE_GENERATOR=Ninja
              export JAVA_HOME="${jdk.home}"
              export VULKAN_SDK="${vulkanSdk}"
              export DLSS_SDK="${dlssSdk}"
              export PATH="$VULKAN_SDK/bin:$PATH"

              echo "dlss-mod dev shell"
              echo "  Java:       $JAVA_HOME"
              echo "  C compiler: $CC"
              echo "  C++ compiler: $CXX"
              echo "  DLSS_SDK:   $DLSS_SDK"
              echo "  VULKAN_SDK: $VULKAN_SDK"
            '';
          };
        }
      );
    };
}
