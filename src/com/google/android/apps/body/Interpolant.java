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

import java.util.List;

/**
 * A class that smoothly interpolates from a current value to a desired value.
 */
public class Interpolant {
    private static final float EPSILON = 0.001f;
    private float mPast;
    private float mPresent;
    private float mFuture;
    private float mUrgency;

    public Interpolant(float value) {
        this.mPast = value;
        this.mPresent = value;
        this.mFuture = value;
        this.mUrgency = 0.25f;
    }

    public float getPresent() {
        return mPresent;
    }

    public float getFuture() {
        return mFuture;
    }

    public void setFuture(float value) {
        this.mFuture = value;    
    }

    public void setFuture(float value, float urgency) {
        setFuture(value);
        mUrgency = urgency;
    }

    public boolean tween() {
        // TODO(thakis, wonchun): This should be time-based, not frame based.
        // TODO(thakis, wonchun): There should be a CircularInterpolant for
        //                        angles.
        if (Math.abs(mFuture - mPresent) < EPSILON) {
            mPast = mFuture;
            mPresent = mFuture;
            return false;
        }
        Bezier b = new Bezier(
                mPast,
                2*mPresent - mPast,
                2*mFuture - mPresent,
                mFuture);
        mPast = mPresent;
        mPresent = b.getPoint(mUrgency);
        return true;
    }

    public static boolean tweenAll(List<Interpolant> interpolants) {
        boolean ret = false;
        for (Interpolant interpolant : interpolants) {
            ret |= interpolant.tween();
        }
        return ret;
    }

    private static class Bezier {
        float x0, x1, x2, x3;
        public Bezier(float x0, float x1, float x2, float x3) {
            this.x0 = x0;
            this.x1 = x1;
            this.x2 = x2;
            this.x3 = x3;
        }

        float getPoint(float t) {
            if (t == 0) {
                return x0;
            } else if(t == 1) {
                return x3;
            }

            float ix0 = lerp(x0, x1, t);
            float ix1 = lerp(x1, x2, t);
            float ix2 = lerp(x2, x3, t);
            
            ix0 = lerp(ix0, ix1, t);
            ix1 = lerp(ix1, ix2, t);

            return lerp(ix0, ix1, t);
        }

        private float lerp(float x0, float x1, float t) {
            return x0 + t*(x1 - x0);
        }
    }
}
