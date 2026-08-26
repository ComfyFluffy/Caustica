/*
 * Sobol direction-number data notice
 *
 * Copyright (c) 2008, Frances Y. Kuo and Stephen Joe
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 *    and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 *    and the following disclaimer in the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the names of the copyright holders nor the names of the University of New South Wales and
 *    the University of Waikato and its contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package dev.comfyfluffy.caustica.rt.pipeline;

/** Exact expansion of the first four Joe-Kuo D(6) Sobol dimensions. */
final class RtSobolDirectionNumbers {
    static final int DIMENSIONS = 4;

    // Rows 2..4 from new-joe-kuo-6.21201. Dimension 1 is defined analytically below.
    private static final int[][] PARAMETERS = {
            {},
            {1, 0, 1},
            {2, 1, 1, 3},
            {3, 1, 1, 3, 1}
    };

    private RtSobolDirectionNumbers() {
    }

    static int[][] createDirections() {
        int[][] directions = new int[DIMENSIONS][Integer.SIZE];
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            directions[0][bit] = 1 << (Integer.SIZE - 1 - bit);
        }
        for (int dimension = 1; dimension < DIMENSIONS; dimension++) {
            int[] parameters = PARAMETERS[dimension];
            int degree = parameters[0];
            int coefficient = parameters[1];
            for (int bit = 1; bit <= degree; bit++) {
                directions[dimension][bit - 1] = parameters[bit + 1] << (Integer.SIZE - bit);
            }
            for (int bit = degree + 1; bit <= Integer.SIZE; bit++) {
                int value = directions[dimension][bit - degree - 1]
                        ^ (directions[dimension][bit - degree - 1] >>> degree);
                for (int k = 1; k < degree; k++) {
                    if (((coefficient >>> (degree - 1 - k)) & 1) != 0) {
                        value ^= directions[dimension][bit - k - 1];
                    }
                }
                directions[dimension][bit - 1] = value;
            }
        }
        return directions;
    }
}
