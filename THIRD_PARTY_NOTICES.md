# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## PsychoV24 Test24 adaptation

The PsychoV24 Test24 tone-mapping adaptation in
`shaders/pipelines/display/psychov24.slang` is derived from RenoDX commit
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

## PsychoV30 Test30 reference source

`shaders/pipelines/display/test30.hlsl` is a verbatim copy of the RenoDX
PsychoV Test30 tone-mapping shader, kept as the pinned reference for the
Caustica Slang adaptation. It is not compiled by Caustica builds, which
compile `*.slang` sources only; its relative include resolves against the
RenoDX tree.

Copyright (C) 2026 Carlos Lopez. SPDX-License-Identifier: MIT.

The reference remains subject to the MIT license. The complete license text is
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

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/a291cc7d2cc642a51566f3dfd5376f635cd1b284/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## Joe-Kuo Sobol direction numbers

`RtSobolDirectionNumbers.java` expands the first four dimensions of the
`new-joe-kuo-6.21201` data set by Frances Y. Kuo and Stephen Joe (2008).

Copyright (c) 2008, Frances Y. Kuo and Stephen Joe. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the copyright notice, conditions,
and disclaimer are retained. Neither the copyright holders nor the
University of New South Wales or University of Waikato may be used to endorse
derived products without prior written permission.

The data is provided without warranty; the copyright holders are not liable
for damages arising from its use. The complete notice is retained in the
source file.

## NVIDIA SHaRC SDK

Caustica can build release artifacts with NVIDIA SHaRC SDK 1.8.0.0 shader
inputs and runtime resources. SHaRC is proprietary software provided by NVIDIA
Corporation and is not licensed under Caustica's LGPL-3.0-or-later license.

The SHaRC SDK remains subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA-RTX/SHARC/blob/e19ccacd511f42a3df6f850052d508c13c9e9737/License.md>

The complete accepted SHaRC license is packaged in release artifacts at
`META-INF/licenses/nvidia/NVIDIA-SHARC-SDK.txt`. The LGPL license grant for
Caustica does not grant rights to the SHaRC SDK.
