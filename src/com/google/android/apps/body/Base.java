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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.util.Log;

/**
 * A database that stores bounding boxes and search terms for entities.
 */
public class Base {

    static private final char FIELD_SEPARATOR = ',';

    /**
     * Stores the bounding box of an entity.
     */
    public static class EntityInfo {
        int layer;
        float bblx, bbly, bblz;
        float bbhx, bbhy, bbhz;
        String displayName;
    }

    //List of entity names. The same as mEntityToName.values(), but uniq'd.
    private List<String> mSearchList = new ArrayList<String>();

    // FIXME: should map to Entity directly
    private Map<String, List<String>> mSearchToEntity = new HashMap<String, List<String>>();

    //Entity metadata (bounding box, center). FIXME: Make this an array instead?
    private Map<String, EntityInfo> mEntities = new HashMap<String, EntityInfo>();


    private void loadEntities(InputStream is) throws IOException {
        BufferedReader reader= new BufferedReader(new InputStreamReader(is));
        String line;

        Set<String> uniqueDisplayNames = new HashSet<String>();

        // TODO(dkogan): We're currently dropping entities with the same
        // name on the floor (e.g. we keep only one rib). Need to come
        // up with a better scheme so we can cycle through 'dups'.
        while ((line = reader.readLine()) != null) {
            String layer = line;
            int layerId = Layers.fromName(layer);

            while ((line = reader.readLine()) != null) {
                if ("".equals(line))
                    break;

                // Using substring() instead of split() is about 30% faster.
                // Explicitly use |new String()| because just substring() makes
                // the substring a view of the original string, which wastes
                // memory.
                int startIndex = 0, endIndex = line.indexOf(FIELD_SEPARATOR);
                String entityId = new String(line.substring(startIndex, endIndex));
                startIndex = endIndex + 1;
                endIndex = line.indexOf(FIELD_SEPARATOR, startIndex);
                String entityName= new String(line.substring(startIndex, endIndex));

                // Map of entity id to coordinate info (bbox, ctr).
                EntityInfo entityInfo = parseEntityInfo(line, endIndex + 1);
                entityInfo.layer = layerId;
                entityInfo.displayName = entityName;
                mEntities.put(entityId, entityInfo);

                // Map of display name to entity id.
                if (!uniqueDisplayNames.contains(entityName)) {
                    mSearchList.add(entityName);
                    uniqueDisplayNames.add(entityName);
                    mSearchToEntity.put(entityName, new ArrayList<String>());
                }
                mSearchToEntity.get(entityName).add(entityId);
            }
        }
    }

    // This is a simplified version of Float.parseFloat(). It doesn't support
    // hex floats or exponents. Float.parseFloat() is slow in Android
    // (see http://b/issue?id=4544814) -- using this function here makes
    // entity loading 3.75x as fast.
    // Based on http://bitplane.net/2010/08/java-float-fast-parser/, but with
    // fewer charAt() calls (this made about a 20% difference).
    private static float parseFloat(String f, int pos) {
        final int len = f.length();
        float ret = 0f;  // return value
        boolean neg = false;  // true if part is a negative number

        // sign
        if (f.charAt(pos) == '-') { 
            neg = true; 
            pos++; 
        }

        // integer part
        for (; pos < len; pos++) {
            char c = f.charAt(pos);
            if (c > '9' || c < '0') break;
            ret = ret*10 + (c - '0');
        }

        // decimal part
        if (pos < len && f.charAt(pos) == '.') {
            pos++;
            float part = 0;
            float mul = 1;
            for (; pos < len; pos++) {
                char c = f.charAt(pos);
                if (c > '9' || c < '0') break;
                part = part*10 + (c - '0'); 
                mul *= 10;
            }
            ret = ret + part / mul;
        }

        return neg ? -ret : ret;
    }

    private EntityInfo parseEntityInfo(String line, int pos) {
        EntityInfo result = new EntityInfo();
        result.bblx = parseFloat(line, pos);
        pos = line.indexOf(FIELD_SEPARATOR, pos + 1) + 1;
        result.bbly = parseFloat(line, pos);
        pos = line.indexOf(FIELD_SEPARATOR, pos + 1) + 1;
        result.bblz = parseFloat(line, pos);
        pos = line.indexOf(FIELD_SEPARATOR, pos + 1) + 1;
        result.bbhx = parseFloat(line, pos);
        pos = line.indexOf(FIELD_SEPARATOR, pos + 1) + 1;
        result.bbhy = parseFloat(line, pos);
        pos = line.indexOf(FIELD_SEPARATOR, pos + 1) + 1;
        result.bbhz = parseFloat(line, pos);
        return result;
    }

    void loadMetadata(Context context) {
        try {
            long start = System.nanoTime();
            Log.w("Body", "\nLoading entities");
            InputStream is = context.getResources().openRawResource(R.raw.f_entities);
            loadEntities(is);
            Log.w("Body", "Entities took " + (System.nanoTime() - start) / 1e9f + " s");
        } catch (IOException e) {
            Log.e("Body", e.toString());
        }
    }

    public List<String> getSearchList() {
        return mSearchList;
    }

    public List<String> getFilteredSearchList() {
        // This is in search.js's initialize() in the web version.
        List<String> filteredSearchList = new ArrayList<String>();
        for (String search : getSearchList()) {
            if (mEntities.containsKey(mSearchToEntity.get(search).get(0))) {
                filteredSearchList.add(search);
            }
        }
        Collections.sort(filteredSearchList, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return a.length() - b.length();
                }});
        return filteredSearchList;
    }

    public String getEntityNameForSearch(String search) {
        return mSearchToEntity.get(search).get(0);
    }

    public EntityInfo getInfoForEntityName(String name) {
        return mEntities.get(name);
    }
}
