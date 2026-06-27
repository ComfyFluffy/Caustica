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

          runtimeLibs = with pkgs; [
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
          default = pkgs.mkShell {
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
                ninja
                pkg-config
                spirv-tools
                vulkan-headers
                vulkan-tools
                vulkan-validation-layers
              ]
              ++ runtimeLibs;

            JAVA_HOME = jdk.home;
            LD_LIBRARY_PATH = lib.makeLibraryPath runtimeLibs;
            VK_LAYER_PATH = "${pkgs.vulkan-validation-layers}/share/vulkan/explicit_layer.d";

            shellHook = ''
              export JAVA_HOME="${jdk.home}"
              export VULKAN_SDK="$PWD/third_party/nix-vulkan-sdk"
              export DLSS_SDK="$PWD/third_party/nix-dlss-sdk"
              export PATH="$VULKAN_SDK/bin:$PATH"
              export PATH="$DLSS_SDK:$PATH"

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

              ngx_shim_out="$PWD/native/ngx_shim/out"
              mkdir -p "$ngx_shim_out"
              for dlssd_lib in "$DLSS_SDK"/lib/Linux_*/rel/libnvidia-ngx-dlssd.so*; do
                if [ -e "$dlssd_lib" ]; then
                  install -m 0755 "$dlssd_lib" "$ngx_shim_out/$(basename "$dlssd_lib")"
                fi
              done

              echo "dlss-mod dev shell"
              echo "  Java:       $JAVA_HOME"
              echo "  DLSS_SDK:   $DLSS_SDK"
              echo "  VULKAN_SDK: $VULKAN_SDK"
            '';
          };
        }
      );
    };
}
