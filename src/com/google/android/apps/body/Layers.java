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

/**
 * Mostly an enum for layer names.
 */
public class Layers {
    // The Android performance guidelines recommend using ints instead of
    // Java enums.
    public static final int SKIN = 0;
    public static final int MUSCLE = 1;
    public static final int SKELETON = 2;
    public static final int CONNECTIVE = 3;
    public static final int ORGANS = 4;
    public static final int CIRCULATORY = 5;
    public static final int NERVOUS = 6;
    public static final int NUM_LAYERS = 7;

    private static float[] sLayerOpacity = new float[NUM_LAYERS];
    private static List<Listener> sListeners = new ArrayList<Listener>();

    public interface Listener {
        void opacitiesChanged();
    }

    public static void initialize() {
        for (int i = 0; i < NUM_LAYERS; ++i) {
            sLayerOpacity[i] = 0.f;
        }
        sLayerOpacity[Layers.SKIN] = 1.f;
    }

    public static int addView(Listener listener) {
        sListeners.add(listener);
        return sListeners.size() - 1;
    }

    public static float[] getOpacities() {
        return sLayerOpacity;
    }

    public static void changeOpacities(float[] newOpacities, int changeSource) {
        for (int i = 0; i < NUM_LAYERS; ++i) {
            sLayerOpacity[i] = newOpacities[i];
        }
        notifyModelChanged(changeSource);
    }

    private static void notifyModelChanged(int changeSource) {
        for (int i = 0; i < sListeners.size(); ++i) {
            if (i != changeSource) {
                sListeners.get(i).opacitiesChanged();
            }
        }
    }

    public static Integer fromName(String layer) {
        if ("circulatory".equals(layer)) return CIRCULATORY;
        if ("connective".equals(layer)) return CONNECTIVE;
        if ("muscle".equals(layer)) return MUSCLE;
        if ("nervous".equals(layer)) return NERVOUS;
        if ("organs".equals(layer)) return ORGANS;
        if ("skeleton".equals(layer)) return SKELETON;
        if ("skin".equals(layer)) return SKIN;
        return null;
    }
}
