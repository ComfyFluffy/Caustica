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
              export VULKAN_SDK="$PWD/third_party/nix-vulkan-sdk"
              export DLSS_SDK="$PWD/third_party/nix-dlss-sdk"
              export PATH="$VULKAN_SDK/bin:$DLSS_SDK:$PATH"

              # The Gradle shader task and NGX CMake file expect the Windows
              # Vulkan SDK directory shape: $VULKAN_SDK/bin and $VULKAN_SDK/include.
              mkdir -p "$VULKAN_SDK/bin" "$VULKAN_SDK/include" "$DLSS_SDK"
              ln -sfn "${pkgs.glslang}/bin/glslangValidator" "$VULKAN_SDK/bin/glslangValidator"
              ln -sfn "${pkgs.spirv-tools}/bin/spirv-val" "$VULKAN_SDK/bin/spirv-val"
              for header in "${pkgs.vulkan-headers}/include/vulkan/"*; do
                ln -sfn "$header" "$VULKAN_SDK/include/$(basename "$header")"
              done
              ln -sfn "${dlssSdk}/include" "$DLSS_SDK"
              ln -sfn "${dlssSdk}/lib" "$DLSS_SDK"

              ngx_vendor_out="$PWD/build/native/ngx_shim/vendor/linux-x64"
              mkdir -p "$ngx_vendor_out"
              rm -f "$ngx_vendor_out"/libnvidia-ngx-dlss.so* "$ngx_vendor_out"/libnvidia-ngx-dlssg.so*
              for ngx_lib in "$DLSS_SDK"/lib/Linux_*/rel/libnvidia-ngx-dlssd.so*; do
                if [ -e "$ngx_lib" ]; then
                  install -m 0755 "$ngx_lib" "$ngx_vendor_out/$(basename "$ngx_lib")"
                fi
              done

              echo "dlss-mod dev shell"
              echo "  Java:       $JAVA_HOME"
              echo "  C compiler: $CC"
              echo "  C++ compiler: $CXX"
              echo "  DLSS_SDK:   $DLSS_SDK"
              echo "  VULKAN_SDK: $VULKAN_SDK"
              echo "  NGX vendor: $ngx_vendor_out"
            '';
          };
        }
      );
    };
}
