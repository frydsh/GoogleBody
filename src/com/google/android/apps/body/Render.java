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
package com.google.android.apps.body;

import com.google.android.apps.body.tdl.Programs;
import com.google.android.apps.body.tdl.TdlMath;
import com.google.android.apps.body.tdl.Textures;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.ETC1Util.ETC1Texture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsible for rendering the GL display. Lives on the GL thread.
 */
public class Render implements Layers.Listener, LayersLoader.Callback {

    private Navigate mNavigate;
    private int mClientWidth, mClientHeight;

    public Render(Navigate navigate) {
        this.mNavigate = navigate;
    }

    void setClientSize(int width, int height) {
        mClientWidth = width;
        mClientHeight = height;
        mNavigate.setAspect(width / (float)height);
    }

    private List<Interpolant> layerOpacityInterpolants = new ArrayList<Interpolant>();

    // The layers should be added from inside out
    @SuppressWarnings("serial")
    static final Map<Integer, Integer> layerInfo = new LinkedHashMap<Integer, Integer>() {{
        put(Layers.NERVOUS, R.raw.f_nervous);
        put(Layers.CIRCULATORY, R.raw.f_circulatory);
        put(Layers.ORGANS, R.raw.f_organs_no_breasts);
        put(Layers.CONNECTIVE, R.raw.f_connective);
        put(Layers.SKELETON, R.raw.f_skeleton);
        put(Layers.MUSCLE, R.raw.f_muscles);
        put(Layers.SKIN, R.raw.f_skin_with_breasts);
    }};

    float selectionColorScale = 1;

    private static final String VERTEX_SHADER_SELECTION =
        "precision highp float; \n" +
        "uniform mat4 worldViewProjection; \n" +
        "uniform float colorScale; \n" +
        "attribute vec3 position; \n" +
        "attribute float colorIndex; \n" +
        "varying vec4 pColor;\n" +
        "void main() { \n" +
        "  float scaledColor = colorIndex * colorScale; \n" +
        "  float redColor = floor(scaledColor / (256.0 * 256.0)); \n" +
        "  float greenColor = floor((scaledColor - redColor * 256.0 * 256.0) / 256.0); \n" +
        "  float blueColor = (scaledColor - greenColor * 256.0 - redColor * 256.0 * 256.0); \n" +
        "  pColor = vec4(redColor / 255.0, greenColor / 255.0, blueColor / 255.0, 1.0); \n " +
        "  gl_Position = worldViewProjection * vec4(position, 64.0); \n" +
        "}";

    private static final String FRAGMENT_SHADER_SELECTION =
        "precision highp float; \n" +
        "varying vec4 pColor; \n" +
        "void main() { \n" +
        "  gl_FragColor = pColor; \n" +
        "}";

    private static final String VERTEX_SHADER_LIGHT =
        "precision mediump float; \n" +
        "uniform mat4 worldViewProjection; \n" +
        "uniform mat4 worldView; \n" +
        "attribute vec3 position; \n" +
        "attribute vec2 texCoord; \n" +
        "attribute vec3 normal; \n" +
        "varying vec3 pNormal; \n" +
        "varying vec2 pTexCoord; \n" +
        "void main() { \n" +
        "  pNormal = (worldView * vec4(normal, 0.0)).xyz; \n" +
        "  pTexCoord = texCoord / 512.0; \n" +
        "  gl_Position = worldViewProjection * vec4(position, 64.0); \n" +
        "}";

    // This is slightly simpler than the web version for performanc reasons,
    // for example no specular highlighting is done.
    // It also uses |precision mediump|. |lowp| made the textures very blocky on
    // the tablet, and |mediump| looks better and is the same speed on tablet.
    // On my Nexus S, |lowp| looked as good as |mediump| and was faster though.
    // On the tablet, it's ~1ms/frame faster to have the whole shader |mediump|
    // (as opposed to making just |pTextCoord| mediump).
    private static final String FRAGMENT_SHADER_LIGHT =
        "precision mediump float; \n" +
        "uniform float opacity; \n" +
        "uniform sampler2D textureSampler; \n" +
        "varying vec3 pNormal; \n" +
        "varying vec2 pTexCoord; \n" +
        "void main() { \n" +
        "  vec3 lightVector = vec3(0.3, 0.3, 0.9); \n" +
        "  vec3 normal = normalize(pNormal); \n" +
        "  float light = 0.1; \n" +
        "  light += 0.9*max(dot(normal, lightVector), 0.0); \n" +
        "  vec3 textureColor = texture2D(textureSampler, pTexCoord).rgb; \n" +
        "  gl_FragColor = vec4(light*textureColor*opacity, opacity); \n" +  // Real thing
        "}";

    private int mShaderWithLights;
    private int mWorldViewProjectionLoc;
    private int mWorldViewLoc;
    private int mOpacityLoc;
    private int mTextureSamplerLoc;
    private float[] mWvpMatrix = new float[16];
    private float[] mViewMatrix = new float[16];

    private Layer[] mLayers = new Layer[Layers.NUM_LAYERS];

    private int mSelectionShader;
    private int mSelectionWorldViewProjectionLoc;
    private int mColorScaleLoc;
    private Map<Integer, Draw> mSelectionColorMap;
    private int mMaxColorIndex = 1;
    private ByteBuffer mSelectionSurfaceBuffer;

    private int createShortBuffer(int target, ShortBuffer buffer) {
        int[] buffers = { 0 };
        GLES20.glGenBuffers(1, buffers, 0);
        GLES20.glBindBuffer(target, buffers[0]);
        GLES20.glBufferData(target, buffer.capacity() * 2, buffer, GLES20.GL_STATIC_DRAW);
        return buffers[0];
    }

    private Layer initLayer(int info) {
        Layer layer = new Layer(info);
        float initialOpacity = 0;
        if (info == Layers.SKIN)
            initialOpacity = 1;
        layerOpacityInterpolants.add(layer.opacity = new Interpolant(initialOpacity));
        layer.renderOpacity = initialOpacity;
        return layer;
    }

    private void loadLayers(Context context, final BodyActivity ui) {
        for (Integer info : layerInfo.keySet()) {
            if (mLayers[info] == null) mLayers[info] = initLayer(info);
        }

        final Map<Integer, Integer> layerResources = new LinkedHashMap<Integer, Integer>();
        // layerInfo is inside out, but we want to load the skin first.
        List<Integer> layers = new ArrayList<Integer>(layerInfo.keySet());
        Collections.reverse(layers);
        for (int i : layers)
            layerResources.put(i, layerInfo.get(i));

        ui.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    ui.load(this, layerResources);
                }});
    }

    private void prepareDraw(Layer layer, float opacity) {
        float renderOpacity = opacity;
        renderOpacity = (renderOpacity > 1 ? 1 : renderOpacity);
        renderOpacity = (renderOpacity < 0 ? 0 : renderOpacity);

        if (renderOpacity != layer.renderOpacity) {
            layer.renderOpacity = renderOpacity;
        }

        GLES20.glUniform1f(mOpacityLoc, layer.renderOpacity);

        GLES20.glUniform1i(mTextureSamplerLoc, 0);  // Not in js version.    
    }

    private void drawOneGeometryOnly(Layer layer, String geometry) {
        // Only drawing the selected group.
        for (DrawGroup drawGroup : layer.drawGroups) {
            for (Render.Draw draw : drawGroup.draws) {
                if (draw.geometry.equals(geometry)) {
                    drawElements(
                            drawGroup.vertexBuffer,
                            drawGroup.indexBuffer,
                            drawGroup.diffuseTexture,
                            draw.offset,
                            draw.count);
                }
            }
        }
    }

    private void drawLayer(Layer layer, float opacity) {
        if (layer.drawGroups == null) {
            return;
        }

        prepareDraw(layer, opacity);

        for (DrawGroup drawGroup : layer.drawGroups) {
            drawElements(
                    drawGroup.vertexBuffer,
                    drawGroup.indexBuffer,
                    drawGroup.diffuseTexture,
                    0,
                    drawGroup.numIndices);
        }
    }

    private void drawElements(int vertexBuffer, int indexBuffer,
            int diffuseTexture, int offset, int numIndices) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_SHORT, false, 2 * (3 + 3 + 2), 0);

        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_SHORT, false, 2 * (3 + 3 + 2), 2 * 3);

        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_SHORT, false, 2 * (3 + 3 + 2), 2 * (3 + 3));
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, diffuseTexture);
        GLES20.glDrawElements(
                GLES20.GL_TRIANGLES, numIndices, GLES20.GL_UNSIGNED_SHORT, offset * 2);    
    }

    private void updateMatrices(int w, int h) {
        Navigate.Camera camera = mNavigate.getCamera();
        float[] eyePosition = camera.eye;
        float[] targetPosition = camera.target;
        float[] upPosition = camera.up;

        Matrix.setLookAtM(mViewMatrix, 0,
                eyePosition[0], eyePosition[1], eyePosition[2],
                targetPosition[0], targetPosition[1], targetPosition[2],
                upPosition[0], upPosition[1], upPosition[2]);

        // Mobile devices have only 16 bits of depth buffer, so limit the depth
        // range. This is a heuristic which works pretty well except if you
        // look at the model from above, in which case the skull gets clipped
        // failry early. But this almost looks like a feature.
        // TODO(thakis): Make this smarter once we have toplevel bounding boxes.
        float tz = mViewMatrix[3 * 4 + 2];
        tz = -tz;  // tz is always negative, make it positive (max 250).

        final float BODY_HEIGHT = 400;  // TODO: tweak?
        final float F = 1/5.f;

        float[] projectionMatrix = new float[16];
        float aspect = w / (float)h;
        TdlMath.perspective(
                projectionMatrix,
                (float)Math.toRadians(camera.fov),
                aspect,
                Math.max(10.f, tz - BODY_HEIGHT * F),
                tz + (1 - F)*BODY_HEIGHT);

        // The world matrix is always the identity.
        Matrix.multiplyMM(mWvpMatrix, 0, projectionMatrix, 0, mViewMatrix, 0);
    }

    private static class OffscreenSurface {
        int framebuffer;
        int renderbuffer;
        int colorTexture;
    }

    OffscreenSurface createOffscreenSurface(int width, int height) {
        OffscreenSurface result = new OffscreenSurface();

        int[] framebuffers = { 0 };
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        result.framebuffer = framebuffers[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, result.framebuffer);

        // Color buffer.
        int[] textures = { 0 };
        GLES20.glGenTextures(1, textures, 0);
        result.colorTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, result.colorTexture);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        // Depth buffer.
        int[] renderbuffers = { 0 };
        GLES20.glGenRenderbuffers(1, renderbuffers, 0);
        result.renderbuffer = renderbuffers[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, result.renderbuffer);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                width, height);

        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, result.colorTexture, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER,
                result.renderbuffer);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w("Body", "Incomplete framebuffer");
            result.framebuffer = 0;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return result;
    }

    void drawBodyForSelection(int x, int y, int fboWidth, int fboHeight) {
        updateMatrices(mClientWidth, mClientHeight);

        // Zoom in on the picking rectangle.
        float[] pickingMatrix = new float[16];
        TdlMath.pickMatrix(
                pickingMatrix, x, y, fboWidth, fboHeight,
                new float[]{0, 0, mClientWidth, mClientHeight});
        Matrix.multiplyMM(mWvpMatrix, 0, pickingMatrix, 0, mWvpMatrix.clone(), 0);

        GLES20.glUseProgram(mSelectionShader);
        GLES20.glUniformMatrix4fv(mSelectionWorldViewProjectionLoc, 1, false, mWvpMatrix, 0);
        selectionColorScale = (float)Math.floor((256*256*256-1) / (float)mMaxColorIndex );
        GLES20.glUniform1f(mColorScaleLoc, selectionColorScale);

        GLES20.glViewport(0, 0, fboWidth, fboHeight);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glDisableVertexAttribArray(2);

        for (int info : layerInfo.keySet()) {
            Layer layer = mLayers[info];

            if (layer.drawGroups == null)
                continue;

            if (layer.renderOpacity < 0.5 && !layer.isVisibleTarget)
                continue;

            for (DrawGroup drawGroup : layer.drawGroups) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, drawGroup.vertexBuffer);
                GLES20.glVertexAttribPointer(0, 3, GLES20.GL_SHORT, false, 2 * (3 + 3 + 2), 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, drawGroup.colorBuffer);
                GLES20.glVertexAttribPointer(1, 1, GLES20.GL_UNSIGNED_SHORT, false, 2 * 1, 0);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, drawGroup.indexBuffer);
                GLES20.glDrawElements(
                        GLES20.GL_TRIANGLES, drawGroup.numIndices, GLES20.GL_UNSIGNED_SHORT, 0);
            }
        }

        if (mSelectionSurfaceBuffer != null) {
            GLES20.glReadPixels(
                    0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    mSelectionSurfaceBuffer);
        }
    }

    private int getPixel(int sx, int sy, int width, int height, ByteBuffer data) {
        if (sx < 0 || sx >= width || sy < 0 || sy >= height) return 0;

        int startByte = (sy * width + sx) * 4;
        short red = (short) (((short) data.get(startByte + 0)) & 0xff);
        short green = (short) (((short) data.get(startByte + 1)) & 0xff);
        short blue = (short) (((short) data.get(startByte + 2)) & 0xff);
        return blue + green * 256 + red * 256 * 256;
    }

    // It's hard to select thin entites when tapping. Search in concentric
    // growing rectangles instead of checking just a single pixel to
    // rectify this.
    private int findPixelInRect(
            int sx, int sy, int windowSize, int width, int height, ByteBuffer data) {
        // Check center.
        int value = getPixel(sx, sy, width, height, data);
        if (value != 0) return value;

        // Walk growing rectangle edges.
        for (int d = 1; d <= windowSize / 2; ++d) {
            for (int y = sy - d; y <= sy + d; ++y) {
                if (y < 0) continue;
                if (y >= height) break;

                value = getPixel(sx - d, y, width, height, data);
                if (value != 0) return value;
                value = getPixel(sx + d, y, width, height, data);
                if (value != 0) return value;
            }
            for (int x = sx - d + 1; x <= sx + d - 1; ++x) {
                if (x < 0) continue;
                if (x >= width) break;

                value = getPixel(x, sy - d, width, height, data);
                if (value != 0) return value;
                value = getPixel(x, sy + d, width, height, data);
                if (value != 0) return value;
            }
        }
        return 0;
    }

    public String getEntityAtCoord(int x, int y) {
        if (x < 0 || x > mClientWidth || y < 0 || y > mClientHeight)
            return "";

        final int kSelectionRectWidth = 20;

        // Render at a much smaller resolution (only 20x20 pixel around touch point).
        int fboWidth = kSelectionRectWidth;
        int fboHeight = kSelectionRectWidth;

        OffscreenSurface selection = createOffscreenSurface(fboWidth, fboHeight);
        if (selection == null) {
            Log.w("Body", "Failed to create framebuffer");
            return "";
        }
        int selectionSurfaceSize = fboWidth * fboHeight * 4;
        mSelectionSurfaceBuffer = ByteBuffer.allocateDirect(selectionSurfaceSize);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, selection.framebuffer);

        drawBodyForSelection(x, mClientHeight - 1 - y, fboWidth, fboHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        int sx = kSelectionRectWidth / 2, sy = kSelectionRectWidth / 2;
        int value = findPixelInRect(
                sx, sy, kSelectionRectWidth, fboWidth, fboHeight, mSelectionSurfaceBuffer);

        // Clear stuff.
        mSelectionSurfaceBuffer = null;
        int[] framebuffers = { selection.framebuffer };
        GLES20.glDeleteFramebuffers(1, framebuffers, 0);
        int[] renderbuffers = { selection.renderbuffer };
        GLES20.glDeleteRenderbuffers(1, renderbuffers, 0);
        int[] textures = { selection.colorTexture };
        GLES20.glDeleteTextures(1, textures, 0);

        value = (int)Math.floor(value / selectionColorScale);
        if (value != 0 && mSelectionColorMap.containsKey(value)) {
            return mSelectionColorMap.get(value).geometry;
        } else {
            return "";
        }
    }

    void drawBody() {
        Interpolant.tweenAll(layerOpacityInterpolants);

        updateMatrices(mClientWidth, mClientHeight);

        GLES20.glUseProgram(mShaderWithLights);

        GLES20.glUniformMatrix4fv(mWorldViewProjectionLoc, 1, false, mWvpMatrix, 0);
        GLES20.glUniformMatrix4fv(mWorldViewLoc, 1, false, mViewMatrix, 0);

        GLES20.glActiveTexture(0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glEnableVertexAttribArray(2);

        GLES20.glViewport(0, 0, mClientWidth, mClientHeight);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glClearColor(1, 1, 1, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        Layer skinLayer = mLayers[Layers.SKIN];
        if (Select.haveSelectedEntity()) {
            String targetEntity = Select.getEntity();
            List<Integer> targetLayers = new ArrayList<Integer>();
            List<Integer> otherLayers = new ArrayList<Integer>();
            for (int info : layerInfo.keySet()) {
                mLayers[info].isVisibleTarget = false;
                int targetLayerIndex = Select.getLayer();

                // Skeleton and connective layers are conjoined.
                if (info == targetLayerIndex||
                        (targetLayerIndex == Layers.SKELETON && info == Layers.CONNECTIVE ||
                         targetLayerIndex == Layers.CONNECTIVE && info == Layers.SKELETON)) {
                    targetLayers.add(info);
                } else {
                    otherLayers.add(info);
                }
            }

            // Switch to transparenting layers.
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            // We draw the layers in the same order they draw when there's
            // nothing selected to avoid snapping when switching between selection
            // rendering and regular rendering.
            for (int info : layerInfo.keySet()) {
                Layer layer = mLayers[info];
                boolean isTarget = false;

                // Check if we've come across one of the target layers.
                for (int targetLayerIndex : targetLayers) {
                    Layer targetLayer = mLayers[targetLayerIndex];

                    if (layer == targetLayer && layer.drawGroups != null) {
                        isTarget = true;

                        // First render the selected entity...
                        float targetOpacity = targetLayer.opacity.getPresent();
                        targetOpacity += (1 - targetOpacity) * Select.getSelectedEntityOpacity();
                        prepareDraw(layer, targetOpacity);
                        drawOneGeometryOnly(targetLayer, targetEntity);

                        // ... and then its layer
                        float targetLayerOpacity =
                                targetLayer.opacity.getPresent() * Select.getSelectedLayerOpacity();
                        if (targetLayerOpacity > 0.05) {
                            prepareDraw(layer, targetLayerOpacity);
                            drawLayer(targetLayer, targetLayerOpacity);
                            targetLayer.isVisibleTarget = true;
                        } else {
                            targetLayer.renderOpacity = 0;
                        }
                    }
                }

                if (!isTarget)
                    layer.renderOpacity = 0;
            }
            GLES20.glDisable(GLES20.GL_BLEND);

        } else if (skinLayer.opacity.getPresent() >= 0.95) {
            drawLayer(skinLayer, skinLayer.opacity.getPresent());
        } else {
            List<Layer> opaqueLayers = new ArrayList<Layer>();
            List<Layer> transparentLayers = new ArrayList<Layer>();
            for (Integer info : layerInfo.keySet()) {
                Layer layer = mLayers[info];
                layer.isVisibleTarget = false;

                if (layer.opacity.getPresent() >= 0.95) {
                    opaqueLayers.add(layer);
                } else if (layer.opacity.getPresent() > 0.05) {
                    transparentLayers.add(layer);
                } else {
                    layer.renderOpacity = 0;
                }
            }

            int lastLayer = 0;
            GLES20.glDisable(GLES20.GL_BLEND);
            for (int ii = opaqueLayers.size() - 1; ii >= 0; --ii) {
                Layer layer = opaqueLayers.get(ii);
                drawLayer(layer, 1.0f);
                lastLayer = layer.type;
            }

            if (transparentLayers.size() > 0) {
                GLES20.glEnable(GLES20.GL_BLEND);
                // This loop will run at most twice.
                for (int ii = 0; ii < transparentLayers.size(); ++ii) {
                    Layer layer = transparentLayers.get(ii);

                    // It looks weird if the inner layer fades out and z-blocks the outer layer.
                    // Always draw the outer layer on top.
                    if (ii > 0) {
                        boolean connectiveAndSkeleton =
                                lastLayer == Layers.CONNECTIVE && layer.type == Layers.SKELETON;

                        // connective and skeleton are rendered as one unit
                        // TODO: put them in a single layer
                        if (!connectiveAndSkeleton)
                            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
                    }

                    drawLayer(layer, layer.opacity.getPresent());
                    lastLayer = layer.type;
                }
                GLES20.glDisable(GLES20.GL_BLEND);
            }
        }
    }


    public float[] viewportCoords(float[] p) {
        float[] cameraMatrix = mWvpMatrix;
        float x =
            cameraMatrix[0]  * p[0] +
            cameraMatrix[4]  * p[1] +
            cameraMatrix[8]  * p[2] +
            cameraMatrix[12];
        float y =
            cameraMatrix[1]  * p[0] +
            cameraMatrix[5]  * p[1] +
            cameraMatrix[9]  * p[2] +
            cameraMatrix[13];
        float w =
            cameraMatrix[3]  * p[0] +
            cameraMatrix[7]  * p[2] +
            cameraMatrix[11] * p[2] +
            cameraMatrix[15];

        x = x / w;
        y = y / w;

        x = (x + 1) * mClientWidth / 2;
        y = (2 - (y + 1)) * mClientHeight / 2;

        float[] result = { x, y };
        return result;
    }

    public void initialize(Context context, BodyActivity ui) {
        Layers.addView(this);

        // Selection shader
        mSelectionShader = Programs.loadProgram(VERTEX_SHADER_SELECTION, FRAGMENT_SHADER_SELECTION);
        GLES20.glBindAttribLocation(mSelectionShader, 0, "position");
        GLES20.glBindAttribLocation(mSelectionShader, 1, "colorIndex");
        GLES20.glLinkProgram(mSelectionShader);
        mSelectionWorldViewProjectionLoc =
                GLES20.glGetUniformLocation(mSelectionShader, "worldViewProjection");
        mColorScaleLoc = GLES20.glGetUniformLocation(mSelectionShader, "colorScale");

        // Shader with lights
        Log.i("Body", "Loading shader");
        mShaderWithLights = Programs.loadProgram(VERTEX_SHADER_LIGHT, FRAGMENT_SHADER_LIGHT);
        GLES20.glBindAttribLocation(mShaderWithLights, 0, "position");
        GLES20.glBindAttribLocation(mShaderWithLights, 1, "normal");
        GLES20.glBindAttribLocation(mShaderWithLights, 2, "texCoord");
        GLES20.glLinkProgram(mShaderWithLights);
        mWorldViewProjectionLoc =
                GLES20.glGetUniformLocation(mShaderWithLights, "worldViewProjection");
        mWorldViewLoc = GLES20.glGetUniformLocation(mShaderWithLights, "worldView");
        mOpacityLoc = GLES20.glGetUniformLocation(mShaderWithLights, "opacity");
        mTextureSamplerLoc = GLES20.glGetUniformLocation(mShaderWithLights, "textureSampler");
        GLES20.glReleaseShaderCompiler();

        // Kick of load.
        loadLayers(context, ui);
    }

    @Override
    public void opacitiesChanged() {
        float[] opacities = Layers.getOpacities();
        for (int i = 0; i < opacities.length; ++i) {
            mLayers[i].opacity.setFuture(opacities[i]);
        }
    }

    static final class Draw {
        String geometry;
        int offset, count;
    }

    static final class DrawGroup {
        public ShortBuffer vertexBufferData;
        public ShortBuffer indexBufferData;
        public ShortBuffer colorBufferData;
        public int numIndices;
        public int indexBuffer;
        public int vertexBuffer;
        public int colorBuffer;
        String texture;
        int diffuseTexture;

        // |loadedDiffuseTexture| is used for dynamically generated textures,
        // currently only one-color bitmaps. |loadedCompressedDiffuseTexture|
        // is used for "normal" textures.
        Bitmap loadedDiffuseTexture;
        public ETC1Texture loadedCompressedDiffuseTexture;

        public float[] diffuseColor;
        public ArrayList<Draw> draws;
    }

    private static final class Layer {
        public boolean isVisibleTarget;  // Used for picking in transparent layer.
        public int type;
        public Interpolant opacity;
        public DrawGroup[] drawGroups;
        public float renderOpacity;

        public Layer(int info) {
            this.type = info;
        }
    }

    @Override
    public void finishLayerLoad(LayersLoader.Results r, boolean isLoadDone) {
        Layer layer = mLayers[r.layerId];
        layer.drawGroups = r.groups;
        this.mSelectionColorMap = r.selectionColorMap;
        this.mMaxColorIndex = r.maxColorIndex;

        for (DrawGroup group : layer.drawGroups) {
            // TODO: maybe have a finer-grained callback so that not all layer textures
            // need to be in memory at once?
            if (group.loadedCompressedDiffuseTexture != null) {
              group.diffuseTexture = Textures.loadTexture(group.loadedCompressedDiffuseTexture);
              group.loadedCompressedDiffuseTexture = null;
            } else {
              group.diffuseTexture = Textures.loadTexture(group.loadedDiffuseTexture);
              group.loadedDiffuseTexture.recycle();
            }

            group.indexBuffer =
                    createShortBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, group.indexBufferData);
            group.indexBufferData = null;

            group.vertexBuffer = createShortBuffer(GLES20.GL_ARRAY_BUFFER, group.vertexBufferData);
            group.vertexBufferData = null;

            group.colorBuffer = createShortBuffer(GLES20.GL_ARRAY_BUFFER, group.colorBufferData);
            group.colorBufferData = null;
        }
    }
}
