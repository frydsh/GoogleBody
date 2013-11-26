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

import java.util.ArrayList;
import java.util.List;

import com.google.android.apps.body.tdl.TdlMath;

/**
 * Manages the camera state.
 */
public class Navigate {

    public static class Camera {
        public float[] eye;
        public float[] target;
        public float[] up;
        public float fov;
    }

    private Camera camera;

    // When to start doing capsule top rotation
    private static final float rotationStartPercent = 0.01f;

    private List<Interpolant> mInterpolants = new ArrayList<Interpolant>();
    private Interpolant mDollyY;
    private Interpolant mDollyZ;
    private Interpolant mTheta;
    private float mAspect;

    // Constants for reducing the deltas for movement
    private static final float ROTATION_REDUCTION = 0.01f;
    private static final float VERTICAL_REDUCTION = 0.1f;
    private static final float ZOOM_REDUCTION = 0.05f;

    // Constants for limits on movement
    private static final float VERTICAL_ADJUSTMENT = 100;
    private static final float VERTICAL_PAN_LIMIT = 150;
    private static final float ZOOM_NEAR_LIMIT = 0.1f;
    private static final float ZOOM_FAR_LIMIT = 250;
    private static final float START_PAN = 0.1f;

    public void initialize() {
        mInterpolants.add(mTheta = new Interpolant((float)Math.PI/2));
        mInterpolants.add(mDollyY = new Interpolant(120.0f));
        mInterpolants.add(mDollyZ = new Interpolant(160.0f));

        // This is the camera used by Render to calculate LookAt
        camera = new Camera();
        camera.eye = new float[]{ 0, 120, 160 };
        camera.target = new float[]{ 0, 120, 0 };
        camera.up = new float[]{ 0, 1, 0 };
        camera.fov = 40;
    }

    float projectedMinMax(Base.EntityInfo bbox, float[] projectionVector) {
        float[][] verts = {
            {bbox.bblx, bbox.bbly, bbox.bblz },
            {bbox.bblx, bbox.bbhy, bbox.bblz },
            {bbox.bblx, bbox.bbly, bbox.bbhz },
            {bbox.bblx, bbox.bbhy, bbox.bbhz },
            {bbox.bbhx, bbox.bbly, bbox.bblz },
            {bbox.bbhx, bbox.bbhy, bbox.bblz },
            {bbox.bbhx, bbox.bbly, bbox.bbhz },
            {bbox.bbhx, bbox.bbhy, bbox.bbhz }
        };

        float[] proj = new float[8];
        for (int v = 0; v < 8; v++) {
            float[] vertVector = TdlMath.subVector(verts[v], camera.eye);
            proj[v] = TdlMath.dot(projectionVector, vertVector);
        }

        float maxVal = Float.MIN_NORMAL;
        float minVal = Float.MAX_VALUE;
        for (int v = 0; v < 8; v++) {
            maxVal = Math.max(maxVal, proj[v]);
            minVal = Math.min(minVal, proj[v]);
        }

        return maxVal - minVal;
    }

    // Goes to a particular entity.
    public void goTo(Base.EntityInfo metadata, float urgency) {
        float cx = (metadata.bblx + metadata.bbhx) / 2;
        float cy = (metadata.bbly + metadata.bbhy) / 2;
        float cz = (metadata.bblz + metadata.bbhz) / 2;
        float dYAxis = (float)Math.sqrt(cz * cz + cx * cx);

        // axes: x goes right
        //       y goes up
        //       z toward viewer

        // x = angle around the y axis
        // y = height
        // z = zoom
        float x = (float)Math.atan2(cz, cx);

        // ideal Y
        float projectedHeight = projectedMinMax(metadata, camera.up);
        float y_angle = 0.5f * (float)Math.toRadians(camera.fov);
        float zy_dist = projectedHeight / (float)Math.tan(y_angle);

        // ideal X
        float[] sideVector = TdlMath.cross(
                camera.up,
                TdlMath.subVector(camera.eye, camera.target));
        sideVector = TdlMath.normalize(sideVector);
        float projectedWidth = projectedMinMax(metadata, sideVector);
        float x_angle = 0.5f * (float)Math.toRadians(camera.fov * mAspect);
        float zx_dist = projectedWidth / (float)Math.tan(x_angle);

        float z_dist = Math.max(zy_dist, zx_dist);

        doNavigate(
                x,
                cy,
                dYAxis + z_dist,
                urgency);
    }


    // The opposite of absClamp: if the current value is between -absLimit and
    // absLimit then it returns the newValue. Useful for ignoring a value until
    // it reaches a certain threshold.
    private float absLimit(float value, float absLimit, float newValue) {
        if (value < absLimit && value > -absLimit)
            return newValue;
        return value;
    }

    public void recalculate() {
        Interpolant.tweenAll(mInterpolants);

        // Camera rotates and translates around the body. Body always
        // considered to be at the origin.
        float angle = mTheta.getPresent();
        float z_val = mDollyZ.getPresent();
        float y_val = mDollyY.getPresent();
        float cx = z_val * (float)Math.cos(angle);
        float cy = y_val;
        float cz = z_val * (float)Math.sin(angle);
        float ty = y_val;
        float rotLimit = rotationStartPercent * VERTICAL_PAN_LIMIT;
        float phi_multiplier = 0;
        float vertDist = cy;
        float topStartRotation = VERTICAL_PAN_LIMIT - rotLimit;

        // Determine if we're in the top hemisphere or lower hemisphere
        if (cy < rotLimit) {
            phi_multiplier = -1;
            ty = rotLimit;
            vertDist = rotLimit - vertDist;
        }
        else if(cy > topStartRotation) {
            phi_multiplier = 1;
            ty = topStartRotation;
            vertDist = rotLimit - (VERTICAL_PAN_LIMIT - cy);
        }
        // If we are in a hemisphere, adjust our camera accordingly
        if (phi_multiplier != 0) {
            float phi = phi_multiplier * (float)Math.PI/2 *
                    (vertDist/VERTICAL_ADJUSTMENT);
            // Fix camera position to account for rotation
            cx *= Math.cos(phi);
            cy = ty + z_val * (float)Math.sin(phi);
            cz *= Math.cos(phi);

            float up_phi = (float)Math.PI/2 - phi;
            camera.up[0] = (float)(-Math.cos(angle) * Math.cos(up_phi));
            camera.up[1] = (float)Math.sin(up_phi);
            camera.up[2] = (float)(-Math.sin(angle) * Math.cos(up_phi));
        }
        else {
            camera.up[0] = 0; camera.up[1] = 1; camera.up[2] = 0;
        }
        // TODO(rlp): If arcball do something different -- different target
        camera.eye[0] = cx; camera.eye[1] = cy; camera.eye[2] = cz;
        camera.target[0] = 0; camera.target[1] = ty; camera.target[2] = 0;
    }

    private void doNavigate(float angle, float y, float zoom) {
        doNavigate(angle, y, zoom, 0.25f);
    }

    private void doNavigate(float angle, float y, float zoom, float urgency) {
        mTheta.setFuture(angle, urgency);

        float verticalLowerLimit = -VERTICAL_ADJUSTMENT;
        float verticalUpperLimit = VERTICAL_PAN_LIMIT + VERTICAL_ADJUSTMENT;
        if (y < verticalLowerLimit) {
            y = verticalLowerLimit;
        }
        if (y > verticalUpperLimit) {
            y = verticalUpperLimit;
        }
        mDollyY.setFuture(y, urgency);

        if (zoom < ZOOM_NEAR_LIMIT) {
            zoom = ZOOM_NEAR_LIMIT;
        }
        if (zoom > ZOOM_FAR_LIMIT) {
            zoom = ZOOM_FAR_LIMIT;
        }
        mDollyZ.setFuture(zoom, urgency);
    }

    public void reset() {
        doNavigate((float)Math.PI/2, 120, 160);
    }

    // Navigates to an offset from the current location.
    private void doNavigateDelta(float dx, float dy, float dz) {
        float camera_scale = mDollyZ.getPresent() / 80;
        doNavigate(
                mTheta.getFuture() + camera_scale*dx,
                mDollyY.getFuture() + camera_scale*dy,
                mDollyZ.getFuture() + camera_scale*dz);    
    }

    // The primary drag function.
    public void drag(float dx, float dy) {
        // We modulate the deltas by constants to make the movement less jumpy
        float deltaRotate = ROTATION_REDUCTION * dx;
        float deltaPan = VERTICAL_REDUCTION * dy;
        // We limit the delta for panning so that it only occurs if the user
        // really intends it to. This eliminates the sort of "bouncy" motion
        // while rotating.
        deltaPan = absLimit(deltaPan, START_PAN, 0);
        doNavigateDelta(deltaRotate, deltaPan, 0);
    }

    // Takes care of the scrolling by changing the z component of our camera
    void scroll(float dy) {
        doNavigateDelta(0, 0, -dy * ZOOM_REDUCTION);
    }

    public void setAspect(float aspect) {
        this.mAspect = aspect;
    }

    public Camera getCamera() {
        return camera;
    }
}
