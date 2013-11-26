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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.LinearLayout.LayoutParams;
import android.widget.PopupWindow;

class QuickactionPopupWindow extends PopupWindow {
    private View mAnchor;
    private View mRoot;
    private QuickactionBackgroundDrawable mBackground;

    public QuickactionPopupWindow(View anchor, View root) {
        super(anchor.getContext());

        mBackground = new QuickactionBackgroundDrawable();
        mBackground.configure(anchor.getContext().getResources(), 0);
        setBackgroundDrawable(mBackground);

        mAnchor = anchor;
        mRoot = root;
        setContentView(mRoot);

        setTouchInterceptor(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    dismiss();
                    return true;
                }
                return false;
            }
        });
    }

    public void showLikeQuickAction() {
        setAnimationStyle(R.style.Animations_QuickactionAnimation);
        setWidth(WindowManager.LayoutParams.WRAP_CONTENT);
        setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
        setFocusable(true);
        setTouchable(true);
        setOutsideTouchable(true);

        int[] anchorLocation = new int[2];
        mAnchor.getLocationOnScreen(anchorLocation);

        mRoot.setLayoutParams(
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        mRoot.measure(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        WindowManager windowManager =
            (WindowManager) mAnchor.getContext().getSystemService(Context.WINDOW_SERVICE);
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        int xPos = Math.max(0, screenWidth - (anchorLocation[0] + mAnchor.getWidth()));
        int yPos = anchorLocation[1] + mAnchor.getHeight();

        // Center arrow below target position.
        int anchorMidX = mRoot.getMeasuredWidth() - mAnchor.getWidth() / 2;
        mBackground.configure(mAnchor.getResources(), anchorMidX);

        showAtLocation(mAnchor, Gravity.RIGHT | Gravity.TOP, xPos, yPos);
    }
}

