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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * Displays a welcome message with a link to the mobile terms of services
 * on first run.
 */
public class BodyTosDialog {
    private static final String PREFERENCE_EULA_ACCEPTED = "eula.accepted";
    private static final String PREFERENCES_EULA = "eula";

    /**
     * Displays the EULA if necessary. This method should be called from the onCreate()
     * method of your main Activity.
     *
     * @param activity The Activity to finish if the user declines the dialog.
     */
    static void show(final Activity activity) {
        final SharedPreferences preferences =
            activity.getSharedPreferences(PREFERENCES_EULA,  Activity.MODE_PRIVATE);
        if (preferences.getBoolean(PREFERENCE_EULA_ACCEPTED, false))
            return;

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.welcome_title);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.eula_accept, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                accept(preferences);
            }
        });
        builder.setNegativeButton(R.string.eula_refuse, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                refuse(activity);
            }
        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                refuse(activity);
            }
        });

        LayoutInflater inflater =
            (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View layout = inflater.inflate(R.layout.tos, null);
        TextView text2 = (TextView) layout.findViewById(android.R.id.text2);
        text2.setMovementMethod(LinkMovementMethod.getInstance());

        AlertDialog dialog = builder.create();
        dialog.setView(layout);
        dialog.show();
    }

    private static void accept(SharedPreferences preferences) {
        preferences.edit().putBoolean(PREFERENCE_EULA_ACCEPTED, true).commit();
    }

    private static void refuse(Activity activity) {
        activity.finish();
    }
}