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

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Provides search suggestions to the android search framework.
 */
public class BodySearchProvider extends ContentProvider {

    public static String AUTHORITY = "com.google.android.apps.body.bodysearchprovider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String ENTITY_MIME_TYPE =
            "vnd.android.cursor.item/vnd.yourcompanyname.contenttype";

    private List<String> mSuggestions;  // Cache for base.getFilteredSearchList()
    private Base mBase;

    @Override
    public boolean onCreate() {
        // Load data on a background thread.
        new Thread(new Runnable() {
                @Override
                public void run() {
                    Base base = new Base();
                    base.loadMetadata(getContext());
                    setBase(base);
                }}).start();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case GET_SUGGESTIONS:
                return SearchManager.SUGGEST_MIME_TYPE;
            case GET_DETAILS:
                return ENTITY_MIME_TYPE;
            default:
                throw new IllegalArgumentException("Unknown Uri: " + uri);
        }
    }

    // UriMatcher stuff
    private static final int GET_SUGGESTIONS = 0;
    private static final int GET_DETAILS = 1;
    private static final int GET_DETAILS_BY_NAME = 2;
    private static final UriMatcher uriMatcher = buildUriMatcher();

    private static UriMatcher buildUriMatcher() {
        UriMatcher matcher =  new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(AUTHORITY, "entities/#", GET_DETAILS);
        matcher.addURI(AUTHORITY, "entities_by_name/*", GET_DETAILS_BY_NAME);
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, GET_SUGGESTIONS);
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", GET_SUGGESTIONS);
        return matcher;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        Log.d("Body", "query for uri " + uri + " " + uriMatcher.match(uri));
        switch (uriMatcher.match(uri)) {
            case GET_SUGGESTIONS:
                return getSuggestions(uri);
            case GET_DETAILS:
                return getDetails(uri);
            case GET_DETAILS_BY_NAME:
                return getDetailsByName(uri);
            default:
                throw new IllegalArgumentException("Unknown Uri: " + uri);
        }
    }

    /**
     * @return A list of suggestions matching the query {@code uri}.
     */
    private Cursor getSuggestions(Uri uri) {
        List<String> candidates = getSuggestions();
        if (candidates == null) return null;

        String query = uri.getLastPathSegment().toLowerCase();

        int id = 0;
        MatrixCursor cursor = new MatrixCursor(new String[]{
                BaseColumns._ID,
                SearchManager.SUGGEST_COLUMN_TEXT_1,
                SearchManager.SUGGEST_COLUMN_INTENT_DATA
        });

        Pattern p = Pattern.compile("\\b" + Pattern.quote(query));
        for (String s : candidates) {
            if (p.matcher(s).find()) {
                cursor.newRow().add(id).add(s).add(CONTENT_URI + "/entities/" + (id + 1));
            }
            id++;
        }
        return cursor;
    }

    /**
     * @return A single row of details such as bounding box for the entity id
     *         represented by {@code uri}.
     */
    private Cursor getDetails(Uri uri) {
    	Base base = getBase();
        List<String> candidates = getSuggestions();
        if (base == null || candidates == null) return null;

        int rowId = Integer.parseInt(uri.getLastPathSegment()) - 1;
        if (rowId < 0 || rowId >= candidates.size()) return null;
        String search = candidates.get(rowId);

        // would be way nicer if base was index-based.
        String entityName = base.getEntityNameForSearch(search);
        if (entityName == null) return null;
        return getDetailsByNameImpl(entityName);
    }

    /**
     * @return A single row of details such as bounding box for the entity name
     *         represented by {@code uri}.
     */
    private Cursor getDetailsByName(Uri uri) {
        String entityName = uri.getLastPathSegment();
        return getDetailsByNameImpl(entityName);
    }

    private Cursor getDetailsByNameImpl(String entityName) {
        Base base = getBase();
        if (base == null) return null;
        Base.EntityInfo info = base.getInfoForEntityName(entityName);
        if (info == null) return null;

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "layer",
                "bblx", "bbly", "bblz",
                "bbhx", "bbhy", "bbhz",
                "entity",
                "entityName",
        });
        cursor.newRow()
                .add(info.layer)
                .add(info.bblx).add(info.bbly).add(info.bblz)
                .add(info.bbhx).add(info.bbhy).add(info.bbhz)
                .add(entityName)
                .add(info.displayName);

        return cursor;
    }

    private synchronized List<String> getSuggestions() {
        return mSuggestions;
    }

    private synchronized void setBase(Base base) {
        this.mBase = base;
        this.mSuggestions = base.getFilteredSearchList();
    }

    private synchronized Base getBase() {
        return mBase;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
