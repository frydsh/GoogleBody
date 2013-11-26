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

import com.google.android.apps.analytics.GoogleAnalyticsTracker;
import com.google.android.apps.body.LayersLoader.Results;

import android.app.ActionBar;
import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.SearchView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Body's main activity.
 */
public class BodyActivity extends Activity implements
        LayersLoader.Callback {
    public static final boolean DEVELOPER_MODE = false;  // Never check this in as true.

    private BodyGLSurfaceView mView;
    private Handler mHandler = new Handler();
    private boolean[] mIsLayerLoaded = new boolean[Layers.NUM_LAYERS];
    private SearchView mSearchView;
    private int mCurrentLayer = Layers.SKIN;

    private boolean mIsInSearch;
    private boolean mHasPendingOnPause;

    // private static final String ANALYTICS_ACCOUNT_ID = "TODO(embedder)";  // Release builds.
    private static final String ANALYTICS_ACCOUNT_ID = "UA-22776086-1";  // Dogfood builds.

    private static final int ANALYTICS_REFRESH_INTERVAL_SECONDS = 300;
    private GoogleAnalyticsTracker mTracker;

    /** If a load is in progress, this is the Runnable that does the load. Else, it's null. */
    private LayersLoader mCurrentLoader;
    private Thread mCurrentLoaderThread;

    /**
     * Abstracts away the concrete UI, so that the activity doesn't have to care
     * if it's showing a phone or a tablet UI. 
     */
    interface BodyUi {
        public void onLayerLoaded(boolean[] isLayerLoaded);
        public void onCreateOptionsMenu(Menu menu);
        public boolean onOptionsItemSelected(MenuItem item);
    }
    private BodyUi mBodyUi;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DEVELOPER_MODE) {
            StrictMode.enableDefaults();
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (!DEVELOPER_MODE) {
            mTracker = GoogleAnalyticsTracker.getInstance();
            mTracker.setProductVersion(getPlatformName(), getAppVersion(this));
            mTracker.start(ANALYTICS_ACCOUNT_ID, ANALYTICS_REFRESH_INTERVAL_SECONDS, this);
        }
        trackPage("/app/create/" + getAppVersion(this));

        Arrays.fill(mIsLayerLoaded, false);

        mView = (BodyGLSurfaceView) findViewById(R.id.gl_view);
        mView.initialize(this);
        mView.setFocusableInTouchMode(true);

        if (findViewById(R.id.btn_header_layer) != null) {
        	mBodyUi = new PhoneUi(this);
        } else {
        	mBodyUi = new TabletUi(this);
        }

        // Performance cargo-culting.
        getWindow().setBackgroundDrawable(null);

        BodyTosDialog.show(this);

        SearchManager manager = (SearchManager) getSystemService(SEARCH_SERVICE);
        manager.setOnDismissListener(new SearchManager.OnDismissListener() {
            @Override
            public void onDismiss() {
                // Called both on cancel and confirm.
                Log.d("Body", "search onDismiss");
                assert mIsInSearch;
                mIsInSearch = false;
            }
        });

        // See http://developer.android.com/guide/topics/search/search-dialog.html,
        // search for "singleTop".
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())
                || Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // The search widget calls onPause()/onNewIntent()/onResume()
            // when delivering search results. Since onPause() destroys
            // our OpenGL context, there's no current GL context when this
            // method runs, and so the label texture upload done in
            // handleEntityQuery() runs without a GL context. The label is
            // reuploaded later, but running GL functions without a context
            // produces ugly logspew, so delay the handleEntityQuery() call
            // until onResume() has been called.
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
                        // Called when the user hits the "Search" button on the keyboard
                        // in the search dialog.
                        handleSearchQuery(intent.getStringExtra(SearchManager.QUERY));
                    } else {
                        // Called when user taps a suggested entry.
                        handleEntityQuery(intent.getData());
                    }
                }
            });
        }
    }

    private void handleEntityQuery(Uri data) {
        Cursor cursor = managedQuery(data, null, null, null, null);
        if (cursor == null || cursor.getCount() == 0) {
            Log.w("Body", "Found no results for query " + data);
            return;
        }
        handleEntityResults(cursor, true, "/search/");
    }

    private void handleSearchQuery(String query) {
        // Get the first suggestion for |query| from our search provider. The
        // suggestion is in the form of a content uri, which can be fed into
        // the "suggestion tapped" logic.
        SearchManager manager = (SearchManager) getSystemService(SEARCH_SERVICE);
        SearchableInfo info = manager.getSearchableInfo(getComponentName());
        int limit = 1;
        Cursor cursor = getSuggestions(info, query, limit);
        if (cursor == null || cursor.getCount() == 0) {
            trackPage("/search/failed/" + query);
            return;
        }
        cursor.moveToFirst();
        int index = cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
        handleEntityQuery(Uri.parse(cursor.getString(index)));
    }

    public void handleEntityResults(Cursor cursor, final boolean flyTo, String trackPrefix) {
        cursor.moveToFirst();
        final Base.EntityInfo info = new Base.EntityInfo();
        info.layer = cursor.getInt(0);
        info.bblx = cursor.getFloat(1);
        info.bbly = cursor.getFloat(2);
        info.bblz = cursor.getFloat(3);
        info.bbhx = cursor.getFloat(4);
        info.bbhy = cursor.getFloat(5);
        info.bbhz = cursor.getFloat(6);
        final String selectedEntity = cursor.getString(7);
        info.displayName = cursor.getString(8);

        if (trackPrefix != null) {
            trackPage(trackPrefix + info.displayName);
        }
        // TODO(thakis): Since a load is kicked off too, the layers will have
        // their opacity overwritten immediately.
        mView.setBodyOpacity((Layers.NUM_LAYERS - info.layer - 0.5f) / (float)Layers.NUM_LAYERS);
        mCurrentLayer = info.layer;

        mView.queueEvent(new Runnable(){
                public void run() {
                    mView.startRendering();
                    Select.selectEntity(
                            selectedEntity, mView.getRenderer().getLabel(),
                            info.displayName, info);
                    if (flyTo)
                        mView.getRenderer().getNavigate().goTo(info, 0.15f);
                    long delayMs = 5000;
                    mView.maybeStopRendering(delayMs);
                }});
    }

    @Override
    public boolean onSearchRequested() {
        if (DEVELOPER_MODE) Log.d("Body", "onSearchRequested");
        assert !mIsInSearch;
        mIsInSearch = true;
        return super.onSearchRequested();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mIsInSearch) {
            if (DEVELOPER_MODE) Log.d("Body", "delaying onPause");
            mHasPendingOnPause = true;
        } else {
            mView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.onResume();
        mHasPendingOnPause = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // On reactivate, the OpenGL context is recreated and everything needs
        // to be reloaded anyway, so cancel any pending loads.
        cancelPendingLoad();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mTracker.stop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mBodyUi.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mBodyUi.onOptionsItemSelected(item)) {
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    public void load(Runnable runnable, Map<Integer, Integer> layerResources) {
        Arrays.fill(mIsLayerLoaded, false);
        mBodyUi.onLayerLoaded(mIsLayerLoaded);

        // Load the currently selected layer first.
        LinkedHashMap<Integer, Integer> reorderedResources = new LinkedHashMap<Integer, Integer>();
        reorderedResources.put(mCurrentLayer, layerResources.get(mCurrentLayer));
        for (Map.Entry<Integer, Integer> resource : layerResources.entrySet()) {
            if (resource.getKey() != mCurrentLayer) {
                reorderedResources.put(resource.getKey(), resource.getValue());
            }
        }

        // If a load is currently running, cancel it before starting a new one.
        // TODO(thakis): Keep the loaded data in a cache, so that reloading is
        // faster.
        cancelPendingLoad();
        mCurrentLoader = new LayersLoader(this, this, reorderedResources, mHandler); 
        mCurrentLoaderThread = new Thread(mCurrentLoader);
        mCurrentLoaderThread.start();
    }

    private void cancelPendingLoad() {
        if (mCurrentLoader != null) {
            mCurrentLoader.cancel();
            // Wait for the current loader thread to finish.
            try {
                mCurrentLoaderThread.join();
            } catch (InterruptedException e) {
                // Nothing to do.
            }
            mCurrentLoader = null;
            mCurrentLoaderThread = null;
        }
    }

    @Override
    public void finishLayerLoad(final Results r, final boolean isLoadDone) {
        mIsLayerLoaded[r.layerId] = true;
        mBodyUi.onLayerLoaded(mIsLayerLoaded);

        mView.queueEvent(new Runnable(){
                public void run() {
                    mView.getRenderer().getRender().finishLayerLoad(r, isLoadDone);
                    mView.requestRender();
                }});

        if (isLoadDone) {
            mCurrentLoader = null;
            mCurrentLoaderThread = null;
        }
    }

    static class PhoneUi implements BodyUi {
        private BodyActivity mActivity;
        private View mLayersButton;
        private int[] mLayerToButtonIdTable;
        private ViewGroup mQuickactionRoot;


        public PhoneUi(BodyActivity bodyActivity) {
            mActivity = bodyActivity;
            mLayersButton = mActivity.findViewById(R.id.btn_header_layer);

            mLayerToButtonIdTable = new int[Layers.NUM_LAYERS];
            mLayerToButtonIdTable[Layers.SKIN] = R.id.btn_skin_layer;
            mLayerToButtonIdTable[Layers.MUSCLE] = R.id.btn_muscle_layer;
            mLayerToButtonIdTable[Layers.SKELETON] = R.id.btn_skeleton_layer;
            mLayerToButtonIdTable[Layers.CONNECTIVE] = 0;
            mLayerToButtonIdTable[Layers.ORGANS] = R.id.btn_organs_layer;
            mLayerToButtonIdTable[Layers.CIRCULATORY] = R.id.btn_circulatory_layer;
            mLayerToButtonIdTable[Layers.NERVOUS] = R.id.btn_nervous_layer;

            // Install search bar event listener.
            View findBar = mActivity.findViewById(R.id.search_bar);
            findBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mActivity.onSearchRequested();
                }
            });

            // Copy the search hint from the search service.
            TextView searchBox = (TextView)mActivity.findViewById(R.id.search_box);
            SearchManager manager = (SearchManager) mActivity.getSystemService(SEARCH_SERVICE);
            SearchableInfo info = manager.getSearchableInfo(mActivity.getComponentName());
            searchBox.setHint(mActivity.getString(info.getHintId()));

            // Install layer button event listener.
            mLayersButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showQuickAction();
                }
            });
        }

        @Override
        public void onLayerLoaded(boolean[] isLayerLoaded) {
            if (mQuickactionRoot == null) return;
            for (int i = 0; i < mLayerToButtonIdTable.length; ++i) {
                if (mLayerToButtonIdTable[i] == 0) continue;
                mQuickactionRoot.findViewById(mLayerToButtonIdTable[i]).setEnabled(isLayerLoaded[i]);
            }
        }

        @Override
        public void onCreateOptionsMenu(Menu menu) {
            // No options menu on the phone.
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            // No options menu on the phone.
            return false;
        }

        private class LayerSwitcherQuickaction extends QuickactionPopupWindow {
            public LayerSwitcherQuickaction(View anchor, View root) {
                    super(anchor, root);
            }

            @Override
            public void dismiss() {
                    mQuickactionRoot = null;
                    super.dismiss();
            }
        }

        private void showQuickAction() {
            LayoutInflater inflater =
                (LayoutInflater) mActivity.getSystemService(LAYOUT_INFLATER_SERVICE);
            mQuickactionRoot = (ViewGroup) inflater.inflate(R.layout.quickaction_bubble, null);

            final LayerSwitcherQuickaction layerSwitcher =
                new LayerSwitcherQuickaction(mLayersButton, mQuickactionRoot);

            OnClickListener listener = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // Give the button time to draw its "pressed" state.
                            // Without this delay, the pressed button is transparent
                            // while the exit animation runs.
                            layerSwitcher.dismiss();
                        }
                    }, 10);

                    switch (v.getId()) {
                    case R.id.btn_skin_layer:
                    case R.id.btn_muscle_layer:
                    case R.id.btn_skeleton_layer:
                    case R.id.btn_organs_layer:
                    case R.id.btn_circulatory_layer:
                    case R.id.btn_nervous_layer:
                        for (int i = 0; i < mLayerToButtonIdTable.length; ++i) {
                            if (mLayerToButtonIdTable[i] == v.getId()) {
                                mActivity.goToLayer(i);
                                break;
                            }
                        }
                        break;
                    }
                }
            };
            for (int i = 0; i < mLayerToButtonIdTable.length; ++i) {
                if (mLayerToButtonIdTable[i] == 0) continue;
                mQuickactionRoot.findViewById(mLayerToButtonIdTable[i]).setOnClickListener(listener);
            }

            // Make sure the load state is up to date.
            onLayerLoaded(mActivity.mIsLayerLoaded);

            layerSwitcher.showLikeQuickAction();
        }
    }

    static class TabletUi implements BodyUi {
        private BodyActivity mActivity;
        private Menu mOptionsMenu;
        private int[] mLayerToMenuIdTable;

        public TabletUi(BodyActivity bodyActivity) {
            mActivity = bodyActivity;

            mLayerToMenuIdTable = new int[Layers.NUM_LAYERS];
            mLayerToMenuIdTable[Layers.SKIN] = R.id.layer_skin;
            mLayerToMenuIdTable[Layers.MUSCLE] = R.id.layer_muscle;
            mLayerToMenuIdTable[Layers.SKELETON] = R.id.layer_skeleton;
            mLayerToMenuIdTable[Layers.CONNECTIVE] = 0;
            mLayerToMenuIdTable[Layers.ORGANS] = R.id.layer_organs;
            mLayerToMenuIdTable[Layers.CIRCULATORY] = R.id.layer_circulatory;
            mLayerToMenuIdTable[Layers.NERVOUS] = R.id.layer_nervous;
        }

        @Override
        public void onLayerLoaded(boolean[] isLayerLoaded) {
            // Layer finished loading before options menu was visible.
            if (mOptionsMenu == null) return;

            for (int i = 0; i < mLayerToMenuIdTable.length; ++i) {
                if (mLayerToMenuIdTable[i] == 0) continue;
                MenuItem item = mOptionsMenu.findItem(mLayerToMenuIdTable[i]);
                item.setEnabled(isLayerLoaded[i]);
            }
        }

        @Override
        public void onCreateOptionsMenu(Menu menu) {
            MenuInflater inflater = mActivity.getMenuInflater();
            inflater.inflate(R.menu.menu, menu);

            // Move SearchView to the left.
            mActivity.mSearchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();

            menu.removeItem(R.id.menu_search);
            mActivity.getActionBar().setDisplayOptions(
                    ActionBar.DISPLAY_SHOW_CUSTOM,
                    ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_TITLE);
            mActivity.getSearchView().setIconifiedByDefault(false);
            mActivity.getActionBar().setCustomView(mActivity.getSearchView());

            // Using the normal search framework lets the GLSurfaceView lose its
            // surface, which causes a reload of all the OpenGL state after each
            // search ( http://b/3346588 ). To work around this, handle search
            // results here and circumvent the normal search framework.
            SearchListener listener = new SearchListener();
            mActivity.getSearchView().setOnSuggestionListener(listener);
            mActivity.getSearchView().setOnQueryTextListener(listener);

            // Install the search service configured in the manifest as source.
            SearchManager manager = (SearchManager) mActivity.getSystemService(SEARCH_SERVICE);
            SearchableInfo info = manager.getSearchableInfo(mActivity.getComponentName());
            mActivity.getSearchView().setSearchableInfo(info);
            mActivity.getSearchView().setQueryHint(mActivity.getString(info.getHintId()));

            mOptionsMenu = menu;
            onLayerLoaded(mActivity.mIsLayerLoaded);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            // Handle item selection.
            switch (item.getItemId()) {
                case android.R.id.home:
                    mActivity.goHome();
                    return true;
                case R.id.layer_skin:
                case R.id.layer_muscle:
                case R.id.layer_skeleton:
                case R.id.layer_organs:
                case R.id.layer_circulatory:
                case R.id.layer_nervous:
                    for (int i = 0; i < mLayerToMenuIdTable.length; ++i) {
                        if (mLayerToMenuIdTable[i] == item.getItemId()) {
                            mActivity.goToLayer(i);
                            break;
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }

        /**
         * A class that circumvents the normal search mechanism.
         * 
         * The normal search framework makes the GLSurfaceView recreate its
         * renderer after a search, which causes a slow reload.
         * 
         * This is an inner class so that BodyActivity can still be used on
         * pre-3.0 OS versions (SearchView is 3.0-only).
         */
        private class SearchListener implements
                SearchView.OnSuggestionListener, SearchView.OnQueryTextListener {
            private boolean navigateToSuggestion(int position, CharSequence userInput) {
                Cursor c = mActivity.getSearchView().getSuggestionsAdapter().getCursor();
                if (c != null && c.moveToPosition(position)) {
                    int col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                    String query = c.getString(col);
                    mActivity.handleEntityQuery(Uri.parse(query));
                    return true;
                }
                mActivity.trackPage("/search/failed/" + userInput);
                return false;
            }

            @Override
            public boolean onSuggestionClick(int position) {
                mActivity.getSearchView().clearFocus();
                CharSequence userInput = mActivity.getSearchView().getQuery();
                mActivity.getSearchView().setQuery("", false);
                return navigateToSuggestion(position, userInput);
            }

            @Override
            public boolean onSuggestionSelect(int position) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String arg0) {
                return false;
            }

            @Override
            public boolean onQueryTextSubmit(String arg0) {
                onSuggestionClick(0);
                return true;
            }
        }
    }

    private SearchView getSearchView() {
        return mSearchView;
    }

    private void clearSelection() {
        mView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    Select.clearSelectedEntity(mView.getRenderer().getLabel());
                }});
    }

    public void goHome() {
        clearSelection();

        // Tell renderer to reset transform.
        mView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mView.getRenderer().getNavigate().reset();
                }});
        // Select skin layer.
        mView.setBodyOpacity(6.5f/7);
        mCurrentLayer = Layers.SKIN;
        trackPage("/layer/home");
    }

    public void goToLayer(int layer) {
        clearSelection();

        switch (layer) {
        case Layers.SKIN:
            mView.setBodyOpacity(6.5f/7);
            trackPage("/layer/skin");
            break;
        case Layers.MUSCLE:
            mView.setBodyOpacity(5.5f/7);
            trackPage("/layer/muscle");
            break;
        case Layers.SKELETON:
            mView.setBodyOpacity(4.5f/7);
            trackPage("/layer/skeleton");
            break;
        case Layers.ORGANS:
            mView.setBodyOpacity(2.5f/7);
            trackPage("/layer/organs");
            break;
        case Layers.CIRCULATORY:
            mView.setBodyOpacity(1.5f/7);
            trackPage("/layer/circulatory");
            break;
        case Layers.NERVOUS:
            mView.setBodyOpacity(0.5f/7);
            trackPage("/layer/nervous");
            break;
        }
        mCurrentLayer = layer;
    }

    public boolean hasSearchViewFocus() {
        return mSearchView != null && mSearchView.hasFocus();
    }

    public void clearSearchViewFocus() {
        if (mSearchView != null) mSearchView.clearFocus();
    }

    private static String getPlatformName() {
        return String.format("Android: %s/%s", Build.MODEL, Build.VERSION.RELEASE);
    }

    private static String getAppVersion(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            return pInfo.versionCode + "/" + pInfo.versionName;
        } catch (NameNotFoundException e) {
            Log.e("Body", "Failed to fetch version info for " + context.getPackageName(), e);
            return "unknown";
        }
    }

    public void trackPage(String page) {
        if (DEVELOPER_MODE) {
            Log.d("Body", "Tracking page view: " + page);
        } else {
            mTracker.trackPageView(page);
        }
    }

    // This is copied from Android's core/java/android/app/SearchManager.java.
    // TODO(thakis): Use SearchManager.getSuggestions() once it's available -- http://b/4599061
    private Cursor getSuggestions(SearchableInfo searchable, String query, int limit) {
        if (searchable == null) {
            return null;
        }

        String authority = searchable.getSuggestAuthority();
        if (authority == null) {
            return null;
        }

        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .query("")  // TODO: Remove, workaround for a bug in Uri.writeToParcel()
                .fragment("");  // TODO: Remove, workaround for a bug in Uri.writeToParcel()

        // if content path provided, insert it now
        final String contentPath = searchable.getSuggestPath();
        if (contentPath != null) {
            uriBuilder.appendEncodedPath(contentPath);
        }

        // append standard suggestion query path
        uriBuilder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);

        // get the query selection, may be null
        String selection = searchable.getSuggestSelection();
        // inject query, either as selection args or inline
        String[] selArgs = null;
        if (selection != null) {    // use selection if provided
            selArgs = new String[] { query };
        } else {                    // no selection, use REST pattern
            uriBuilder.appendPath(query);
        }

        if (limit > 0) {
            uriBuilder.appendQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT, String.valueOf(limit));
        }

        Uri uri = uriBuilder.build();

        // finally, make the query
        return getContentResolver().query(uri, null, selection, selArgs, null);
    }


    public boolean hasPendingGLViewPause() {
        return mHasPendingOnPause;
    }

    public void clearPendingGLViewPause() {
        mHasPendingOnPause = false;
    }
}
