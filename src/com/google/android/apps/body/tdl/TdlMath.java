// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.android.apps.body.tdl;

import android.opengl.Matrix;

/**
 * Helper class for math.
 */
public class TdlMath {
    public static void perspective(
            float[] m, float angle, float aspect, float near, float far) {
        float f = (float)Math.tan(0.5 * (Math.PI - angle));
        float range = near - far;

        m[0] = f / aspect;
        m[1] = 0;
        m[2] = 0;
        m[3] = 0;

        m[4] = 0;
        m[5] = f;
        m[6] = 0;
        m[7] = 0;

        m[8] = 0;
        m[9] = 0; 
        m[10] = (far + near) / range;
        m[11] = -1;

        m[12] = 0;
        m[13] = 0;
        m[14] = 2.0f * far * near / range;
        m[15] = 0;
    }

    public static void pickMatrix(
            float[] m, float x, float y, float width, float height, float[] viewport) {
        m[0] = viewport[2] / width;
        m[1] = 0;
        m[2] = 0;
        m[3] = 0;

        m[4] = 0;
        m[5] = viewport[3] / height;
        m[6] = 0;
        m[7] = 0;

        m[8] = 0;
        m[9] = 0; 
        m[10] = 1;
        m[11] = 0;

        m[12] = (viewport[2] + (viewport[0] - x)*2) / width;
        m[13] = (viewport[3] + (viewport[1] - y)*2) / height;
        m[14] = 0;
        m[15] = 1;
    }

    public static float[] subVector(float[] a, float[] b) {
        float[] result = { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
        return result;
    }

    public static float dot(float[] a, float[] b) {
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    }

    public static float[] normalize(float[] a) {
        float f = 1 / (float)Matrix.length(a[0], a[1], a[2]);
        float[] result = { a[0] * f, a[1] * f, a[2] * f };
        return result;
    }

    public static float[] cross(float[] a, float[] b) {
        float[] result = {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
        return result;
    }
}
