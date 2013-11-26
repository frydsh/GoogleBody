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
import android.database.Cursor;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;

/**
 * The main view class.
 *
 * Has a Renderer object that draws the body display, and interprets touch
 * input.
 */
public class BodyGLSurfaceView extends GLSurfaceView implements
        ScaleGestureDetector.OnScaleGestureListener, GestureDetector.OnGestureListener {
    private BodyOpenGLRenderer mRenderer;
    private GestureDetector mTapDetector;
    private ScaleGestureDetector mScaleDetector;
    private boolean mTouchInProgress = false;
    private float mLastSpan = 0;
    private BodyActivity mUi;
    private long mLastNonTapTouchEventTimeNS = 0;

    public BodyGLSurfaceView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public void initialize(BodyActivity ui) {
        this.mUi = ui;

        // Tell the surface view we want to create an OpenGL ES 2.0-compatible
        // context, and set an OpenGL ES 2.0-compatible renderer. ES 2.0 is
        // declared as a requirement in the manifest, so this is ok.
        setEGLContextClientVersion(2);
        setRenderer(mRenderer = new BodyOpenGLRenderer(getContext(), ui));
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);  // Call after |setRenderer()|.

        mTapDetector = new GestureDetector(getContext(), this);
        mTapDetector.setIsLongpressEnabled(false);
        mScaleDetector = new ScaleGestureDetector(getContext(), this);
        Log.i("Body", "BodyGLSurfaceView created");
    }

    public BodyOpenGLRenderer getRenderer() {
        return mRenderer;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        // Start real-time rendering mode while touches are happening, stop it
        // about a second after touches stopped (to allow fade-out animations to finish).

        mTouchInProgress = false;  // Possibly set to |true| in the scroll callback.

        mScaleDetector.onTouchEvent(event);
        mTapDetector.onTouchEvent(event);

        maybeStopRendering();

        return true;
    }


    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mUi.hasPendingGLViewPause()) {
            // If the (phone) search dialog is open, we do not forward
            // onPause() to the surface view, so that we don't have to
            // reload all resources after a search. However, we do want
            // to forward the call if the user switches away from Body
            // while the search dialog is open.
            // A good place to do this is BodyActivity.onStop(), but
            // 1. that's called after this method
            // 2. this method destroys the surface view's egl surface
            // 3. GLSurfaceView's onPause() doesn't release its gl context
            //    if it doesn't have an egl surface.
            // Hence, the delayed forward needs to happen here, before the
            // call to super.
            onPause();
            if (BodyActivity.DEVELOPER_MODE) Log.d("Body", "delayed view pause");
            mUi.clearPendingGLViewPause();
        }
        super.surfaceDestroyed(holder);
    }

    /**
     * Puts the renderer into continuous mode. Always call
     * {@link #maybeStopRendering(long)} after calling this.
     */
    public void startRendering() {
        ++mRenderId;
        if (getRenderMode() ==  GLSurfaceView.RENDERMODE_CONTINUOUSLY) return;

        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        queueEvent(new Runnable(){
                public void run() {
                    mRenderer.gesturesStarted();
                }});
    }

    /*
     * Shortly after touch interaction stops, the renderer is put into
     * on-demand drawing mode. The way this works is that every time a touch
     * ends, a task with a render ID is scheduled to run after the timeout.
     * This ID is incremented every time a touch happens. If the cancel job
     * executes and the current render ID hasn't changed, no new touches have
     * occurred in the mean time and rendering should really be paused. This
     * stores the last render ID that's been used.
     */
    private int mRenderId = 0;
    private Handler uiHandler = new Handler();

    // If no interaction is happening, stops rendering after a short period (to
    // allow animations to finish).
    private void maybeStopRendering() {
        final long delayMs = 1000;
        maybeStopRendering(delayMs);
    }

    /**
     * Stops rendering after a given delay, if no touch interaction happens
     * during that delay.
     */
    public void maybeStopRendering(long delayMs) {
        if (!mTouchInProgress && !mScaleDetector.isInProgress()) {
            final int currentId = mRenderId;
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    // Runs on UI thread.
                    if (currentId != mRenderId) return;

                    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                    queueEvent(new Runnable(){
                            public void run() {
                                // Runs on render thread.
                                mRenderer.gesturesEnded();
                            }});
                }
            };

            uiHandler.postDelayed(task, delayMs);
        }
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        final float amount = detector.getCurrentSpan() - mLastSpan;
        startRendering();
        queueEvent(new Runnable(){
                public void run() {
                    mRenderer.getNavigate().scroll(2 * amount);
                }});
        mLastSpan = detector.getCurrentSpan();
        mLastNonTapTouchEventTimeNS = System.nanoTime();
        return true;  // Event has been handled.
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        mLastSpan = detector.getCurrentSpan();
        return true;  // Continue handling event.
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
    }

    /**
     * This sets the body opacity. 1.0 means that the skin layer is visible,
     * 0 means that everything is transparent. Values in between show the
     * different layers.
     */
    public void setBodyOpacity(float opac) {
        // On Android, show only a single layer at a time for now.
        final float[] newOpacities = new float[Layers.NUM_LAYERS];

        for (int i = 0; i < Layers.NUM_LAYERS; ++i) newOpacities[i] = 0;
        int index = (int)((1 - opac) * (Layers.NUM_LAYERS - 1) + 0.5);
        newOpacities[index] = 1;

        // Special case: The connective layer appears/disappears with the skeleton.
        if (index == Layers.SKELETON) newOpacities[Layers.CONNECTIVE] = 1;
        if (index == Layers.CONNECTIVE) newOpacities[Layers.SKELETON] = 1;

        // Trigger a repaint.
        startRendering();
        queueEvent(new Runnable(){
                public void run() {
                    Layers.changeOpacities(newOpacities, -1);
                }});
        maybeStopRendering();
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, final float dx, final float dy) {
        mTouchInProgress = true;
        startRendering();
        queueEvent(new Runnable(){
                public void run() {
                    mRenderer.getNavigate().drag(-dx, -dy);
                }});
        mLastNonTapTouchEventTimeNS = System.nanoTime();
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(final MotionEvent e) {
        if (mUi.hasSearchViewFocus()) {
            // Tapping the GL view while the search widget is open cancels the search.
            mUi.clearSearchViewFocus();
            return true;
        }

        // Have a short time after rotating and zooming, to make erratic taps less likely
        final double kDeadTimeS = 0.3;
        if ((System.nanoTime() - mLastNonTapTouchEventTimeNS) / 1e9f < kDeadTimeS)
            return true;

        startRendering();

        // Copy x/y into local variables, because |e| is changed and reused for
        // other views after this has been called.
        final int x = Math.round(e.getX());
        final int y = Math.round(e.getY());

        queueEvent(new Runnable(){
                public void run() {
                    final String entity = mRenderer.getRender().getEntityAtCoord(x, y);
                    if ("".equals(entity)) {
                        Select.clearSelectedEntity(mRenderer.getLabel());
                    } else {
                        uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Uri data = Uri.withAppendedPath(
                                            BodySearchProvider.CONTENT_URI,
                                            "entities_by_name/" + entity);
                                    Cursor cursor = mUi.managedQuery(data, null, null, null, null);
                                    if (cursor == null) {
                                        Log.w("Body", "Found no results for query " + data);
                                        return;
                                    }
                                    mUi.handleEntityResults(cursor, false, "/tap/");
                                }});
                    }}});
        maybeStopRendering();
        return true;
    }
}
