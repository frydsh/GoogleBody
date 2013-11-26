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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.graphics.Bitmap;
import android.opengl.ETC1Util;
import android.opengl.GLES20;
import android.opengl.GLUtils;

/**
 * Helper class for uploading textures..
 */
public class Textures {

  private static int genTex() {
      int[] textures = { 0 };
      GLES20.glGenTextures(1, textures, 0);
      int tex = textures[0];
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
      GLES20.glTexParameteri(
      GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
              GLES20.GL_TEXTURE_2D,
              GLES20.GL_TEXTURE_MIN_FILTER,
              GLES20.GL_LINEAR);

      // If this is CLAMP_TO_EDGE instead of REPEAT, the face is all white :-/
      GLES20.glTexParameteri(
              GLES20.GL_TEXTURE_2D,
              GLES20.GL_TEXTURE_WRAP_S,
              GLES20.GL_REPEAT);
      GLES20.glTexParameteri(
              GLES20.GL_TEXTURE_2D,
              GLES20.GL_TEXTURE_WRAP_T,
              GLES20.GL_REPEAT);
      return tex;    
  }

  public static int loadTexture(Bitmap bitmap) {
      int tex = genTex();
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);

      // BitmapFactory by default resamples images to the resolution of the
      // device.  For textures, this is not what's wanted (the resources should
      // be made smaller instead).
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

      return tex;
  }

  public static int loadTexture(ETC1Util.ETC1Texture etc1Texture) {
      // Note that unlike the js version, this does not cache textures. There
      // was just one cache hit.
      int tex = genTex();
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
      ETC1Util.loadTexture(
              GLES20.GL_TEXTURE_2D, 0, 0, GLES20.GL_RGB,
              GLES20.GL_UNSIGNED_SHORT_5_6_5, etc1Texture);

      return tex;
  }

  public static int loadTexture(float[] diffuseColor) {
      int tex = genTex();
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
      ByteBuffer buffer = ByteBuffer.allocateDirect(4);
      for (int i = 0; i < 3; ++i)
        buffer.put((byte)(diffuseColor[i] * 255 + 0.5));
      buffer.put((byte)255);
      buffer.position(0);
      buffer.order(ByteOrder.nativeOrder());  // FIXME: should not be necessary
      GLES20.glTexImage2D(
              GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
              1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
      return tex;
  }
}
