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

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A simple renderer that delegates the actual rendering to {@code Render}.
 */
public class BodyOpenGLRenderer implements GLSurfaceView.Renderer {
    boolean mInitialized = false;
    private Context mContext;
    private Navigate mNavigate = new Navigate();
    private Render mRender = new Render(mNavigate);
    private Label mLabel = new Label();
    private BodyActivity mUi;
    private int mCanvasWidth, mCanvasHeight;

    public BodyOpenGLRenderer(Context context, BodyActivity ui) {
        this.mContext = context;
        this.mUi = ui;
        Log.i("Body", "BodyOpenGLRenderer created");
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        startLoading();
        Log.i("Body", "BodyOpenGLRenderer surface created");
    }

    private void startLoading() {
        if (!mInitialized) {
            Layers.initialize();
            Select.initialize();
            mNavigate.initialize();
        }

        // Re-upload OpenGL state. TODO(thakis): Keep decoded data cached.
        getLabel().initialize(mContext);
        if (getLabel().isLabelVisible()) {
            Log.d("Body", "\nreuploading label\n");
            getLabel().reupload();
        }
        mRender.initialize(mContext, mUi);
        mInitialized = true;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!mInitialized) {
            GLES20.glClearColor(1, 1, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        Select.update();
        mNavigate.recalculate();
        mRender.drawBody();

        getLabel().updateDisplay(mRender, mCanvasWidth, mCanvasHeight);
        mFpsFrameCount++;
        if (mFpsFrameCount % 50 == 0)
            logFps();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        mRender.setClientSize(width, height);
        mCanvasWidth = width;
        mCanvasHeight = height;
    }

    private int mFpsFrameCount = 0;
    private long mFpsStartTime = 0;

    private void resetFpsCounters() {
        mFpsFrameCount = 0;
        mFpsStartTime = System.nanoTime();
    }

    /** Call this when the user interacted with the screen. */
    public void gesturesStarted() {
        resetFpsCounters();
    }

    /** Call this when the user stopped interaction with the screen. */
    public void gesturesEnded() {
        logFps();
    }

    private void logFps() {
        if (mFpsFrameCount == 0)
            return;

        float elapsedSec = (System.nanoTime() - mFpsStartTime) / 1.0e9f;
        float mspf = 1000 * elapsedSec / mFpsFrameCount;
        float fps = mFpsFrameCount / elapsedSec;
        Log.i("Body", "Millisecs for the last " + mFpsFrameCount + " frames: " +
                mspf + " (" + fps + " fps)");
        resetFpsCounters();
    }

    public Render getRender() {
        return mRender;
    }

    public Navigate getNavigate() {
        return mNavigate;
    }

    public Label getLabel() {
        return mLabel;
    }
}
