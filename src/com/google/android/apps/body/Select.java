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

/** Helper class that stores the current selection. */
public class Select {

    private static String entity;
    private static int layer;

    // Opacity of the selected entity.
    private static Interpolant entityOpacity;

    // Opacity of other entities in the same layer.
    private static Interpolant layerOpacity;

    private static List<Interpolant> interpolants
        = new ArrayList<Interpolant>();

    public static void initialize() {
        interpolants.add(entityOpacity = new Interpolant(0));
        interpolants.add(layerOpacity = new Interpolant(1));
    }

    /** 
     * Updates opacity animations caused by selection changes.
     * @return true if anything was changed.
     */
    public static boolean update() {
        boolean updates = Interpolant.tweenAll(interpolants);

        if (!updates) {
            // Check to see if selection has been deleted.
            if (entity != null && !haveSelectedEntity()) {
                entity = null;
            }
        }

        return updates;
    }

    /**
     * @return The id of the currently selected entity. Call
     *     {@code haveSelectedEntity()} to check if there is a selection.
     */
    public static String getEntity() {
        return entity;
    }

    /** @return The opacity of the selected entity. */
    public static float getSelectedEntityOpacity() {
        return entityOpacity.getPresent();
    }

    /** @return The opacity of the layer of the selected entity. */
    public static float getSelectedLayerOpacity() {
        return layerOpacity.getPresent();
    }

    /** @return true if there is a selection at the moment. */
    public static boolean haveSelectedEntity() {
        return
            entity != null &&
            (entityOpacity.getPresent() != 0 ||
             layerOpacity.getPresent() != 1);
    }

    /** Selects an entity.. */
    public static void selectEntity(
            String entity, Label label, String labelText, Base.EntityInfo info) {
        // Don't re-select, don't break.
        if (entity == null ||
                (haveSelectedEntity() && entity.equals(Select.entity))) {
            return;
        }


        Select.entity = entity;
        Select.layer = info.layer;

        // Label.
        label.label(labelText, info);

        // Highlight.
        entityOpacity.setFuture(1);
        layerOpacity.setFuture(0.3f);
    }

    /** Starts an animation that clears the current selection.. */
    public static void clearSelectedEntity(Label label) {
        entityOpacity.setFuture(0);
        layerOpacity.setFuture(1);

        label.clearLabel();
    }

    /** Returns the layer the currently selected entity is in. */
    public static int getLayer() {
        return layer;
    }
}
