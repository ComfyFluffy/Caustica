# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Super Resolution, Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlss.dll`
- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlss.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## NVIDIA Real-Time Denoisers (NRD) SDK

Caustica can build a platform-specific `nvidia-nrd` artifact containing a
project-owned native shim that statically incorporates NVIDIA NRD and NRI
object code. NRD is proprietary third-party software and is not licensed under
the LGPL. NRI is third-party software licensed under the MIT License.

The integration is pinned to NVIDIA NRD revision
`b233cc3ec5b1db2763e45fd18c9bb19793016355`. Native builds use the pinned
`third_party/NRD` submodule by default. Bundled artifacts contain NRD's
`LICENSE.txt` and NRI's license as `NRI_LICENSE.txt` beside the native library
under `caustica/natives/nrd/<revision>/<platform>/`.

NRI builds apply the repository's `nri-destroy-lifetime.patch`, which captures
allocation callbacks before object destruction. The pinned upstream revision and
MIT license are unchanged.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA-RTX/NRD/blob/b233cc3ec5b1db2763e45fd18c9bb19793016355/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of the native artifact must comply with the
NVIDIA license, including its object-code incorporation and distribution
requirements. The NRD SDK may not be redistributed as a stand-alone product.

NRI license and source:

<https://github.com/NVIDIA-RTX/NRI/blob/main/LICENSE.txt>

## NVIDIA SHaRC (Spatially Hashed Radiance Cache) SDK

Caustica can use the NVIDIA SHaRC 1.8.0.0 shader headers (commit
`e19ccacd511f42a3df6f850052d508c13c9e9737`) when a build is made with
`-PsharcSdk=<SDK checkout>`. The headers are an external input supplied by
whoever builds or runs Caustica: they are not part of this repository, and no
build artifact contains them or code compiled from them. A `-PsharcSdk` build
records only the SDK location, in `caustica/sharc.properties`. At run time
Caustica verifies the SHA-256 of `SharcCommon.h`, `SharcTypes.h`,
`HashGridCommon.h` and `HashGridTypes.h` at that location and compiles them on
the local machine. Without the property, nothing SHaRC-related is read or
packaged.

SHaRC is proprietary software provided by NVIDIA Corporation and is not
licensed under the LGPL. Use of the SDK is subject to the NVIDIA RTX SDKs
license:

<https://github.com/NVIDIA-RTX/SHARC/blob/e19ccacd511f42a3df6f850052d508c13c9e9737/License.md>

The LGPL license grant for Caustica does not grant rights to the SHaRC SDK.

## Slang

Caustica bundles the Slang 2026.14.1 compiler shared libraries and standard module
for in-game shader compilation. Slang is licensed under
`Apache-2.0 WITH LLVM-exception`:

<https://github.com/shader-slang/slang/blob/master/LICENSE>

The Slang distribution incorporates or can depend on components under their
own permissive licenses, including glslang, LZ4, miniz, SPIR-V Headers, and
SPIR-V Tools. The upstream dependency and license list is maintained at:

<https://github.com/shader-slang/slang#license>

Caustica's `causticaslang` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## Khronos Box Vertex Colors glTF asset

Caustica includes the Box Vertex Colors sample model and its binary buffer from
the Khronos glTF Sample Assets repository as a renderer integration fixture.
The asset was created by Marco Hutter and is dedicated to the public domain
under Creative Commons CC0 1.0 Universal:

<https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/BoxVertexColors>

<https://creativecommons.org/publicdomain/zero/1.0/legalcode>

## Khronos Lantern glTF asset

The standalone glTF viewer example includes the binary Lantern sample model from the Khronos glTF Sample
Assets repository as a textured renderer integration fixture. The asset is
dedicated to the public domain under Creative Commons CC0 1.0 Universal:

<https://github.com/KhronosGroup/glTF-Sample-Assets/tree/0e3a605bda7c758293ab58432f1d51a2a355d47a/Models/Lantern>

Bundled GLB source:

<https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/0e3a605bda7c758293ab58432f1d51a2a355d47a/Models/Lantern/glTF-Binary/Lantern.glb>

<https://creativecommons.org/publicdomain/zero/1.0/legalcode>

## PsychoV24 Test24 adaptations

The PsychoV24 Test24 tone-mapping adaptations in
`packages/renderer-presentation/shaders/pipelines/display/psychov24.slang` (the
CIE 170-2 PsychoV24 operator) and
`packages/renderer-presentation/shaders/pipelines/display/psychovisual.slang`
(the PsychoVisual operator, with the gamut squeeze routed through the BT.2020
primary triangle and a hue-restore blend) are derived from RenoDX commit
`fc85b7b15585050442ba35412597ecefc9e04cea`.

Copyright (C) 2026 Carlos Lopez. SPDX-License-Identifier: MIT.

The adaptation remains subject to the MIT license. The complete license text is
available at <https://opensource.org/license/mit/>:

Copyright (c) 2026 Carlos Lopez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## PsychoV30 Test30 adaptation

The PsychoV30 tone-mapping adaptation in
`packages/renderer-presentation/shaders/pipelines/display/psychov30.slang` is
adapted from the RenoDX PsychoV Test29/Test30 cores at the PsychoV30
integration snapshot, with the integration profile's fixed arguments pinned
and the dead reference branches omitted.

Copyright (C) 2026 Carlos Lopez. SPDX-License-Identifier: MIT.

The adaptation remains subject to the MIT license. The complete license text is
available at <https://opensource.org/license/mit/>:

Copyright (c) 2026 Carlos Lopez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## PsychoV31 Test31 adaptation

The PsychoV31 Test31 tone-mapping adaptation in
`packages/renderer-presentation/shaders/pipelines/display/psychov31.slang` is
adapted from the custom Test31 core (`custom_psychotm_test31`) of the RenoDX
PsychoV Test31 shader over the PsychoV30 layer in `psychov30.slang`, with the
integration profile's fixed arguments pinned and the dead reference branches
omitted.

Copyright (C) 2026 Carlos Lopez. Modifications Copyright (C) 2026 Musa Haji.
SPDX-License-Identifier: MIT.

The adaptation remains subject to the MIT license. The complete license text is
available at <https://opensource.org/license/mit/>:

Copyright (C) 2026 Carlos Lopez
Copyright (C) 2026 Musa Haji

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## PsychoV69 adaptation

The PsychoV69 tone-mapping adaptation in
`packages/renderer-presentation/shaders/pipelines/display/psychov69.slang` is
adapted from the PsychoV69 0.1.0 experimental source release
(`dist/PsychoV69.hlsl`), derived in part from the PsychoV30/V31 cores, with the
integration profile's fixed arguments pinned (scene reference white 1.0,
BT.709 source boundary, dual neutral anchors) and the diagnostic status bits
omitted.

Copyright (C) 2026 Carlos Lopez. SPDX-License-Identifier: MIT.

The adaptation remains subject to the MIT license. The complete license text is
available at <https://opensource.org/license/mit/>:

Copyright (c) 2026 Carlos Lopez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Prism adaptation

The Prism tone-mapping adaptation in
`packages/renderer-presentation/shaders/pipelines/display/prism.slang` is a
stripped port of the `prism.hlsl` tone-mapping functions built for RenoDX,
pinned to the `prismTM.hlsl` snapshot, with working-space primaries derived
by TheGreatHmmmmm. It keeps the gamut squeeze and the anchored C-infinity
shoulder; no RenoDX runtime or game-specific code is included.

Copyright (c) 2026 Musa Haji. Copyright (c) 2026 OopyDoopy / KickFister / Jon.
SPDX-License-Identifier: MIT.

The adaptation remains subject to the MIT license. The complete license text is
available at <https://opensource.org/license/mit/>:

Copyright (c) 2026 Musa Haji
Copyright (c) 2026 OopyDoopy / KickFister / Jon

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
