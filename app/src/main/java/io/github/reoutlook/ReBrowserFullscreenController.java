package io.github.reoutlook;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

/** Owns ReBrowser's global orientation policy and native fullscreen custom view. */
final class ReBrowserFullscreenController {
    private final Activity activity;
    private final ReBrowserPreferences preferences;
    private View styledRoot;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int systemUiBeforeFullscreen;

    ReBrowserFullscreenController(Activity activity, ReBrowserPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;
    }

    void attachStyledRoot(View root) {
        styledRoot = root;
    }

    boolean isFullscreen() {
        return fullscreenContainer != null;
    }

    boolean isLandscape() {
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    String toggleGlobalOrientationLock() {
        boolean landscape = isLandscape();
        preferences.setGlobalOrientation(landscape
                ? ReBrowserPreferences.ORIENTATION_PORTRAIT
                : ReBrowserPreferences.ORIENTATION_LANDSCAPE);
        applyGlobalOrientationPreference();
        return landscape ? ReBrowserPreferences.ORIENTATION_PORTRAIT
                : ReBrowserPreferences.ORIENTATION_LANDSCAPE;
    }

    void clearGlobalOrientationLock() {
        preferences.setGlobalOrientation(ReBrowserPreferences.ORIENTATION_UNLOCKED);
        applyGlobalOrientationPreference();
    }

    void applyGlobalOrientationPreference() {
        String mode = preferences.globalOrientation();
        if (ReBrowserPreferences.ORIENTATION_LANDSCAPE.equals(mode)) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if (ReBrowserPreferences.ORIENTATION_UNLOCKED.equals(mode)) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    void show(View customView, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenContainer != null) {
            callback.onCustomViewHidden();
            return;
        }
        systemUiBeforeFullscreen = activity.getWindow().getDecorView().getSystemUiVisibility();
        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(activity);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        if (customView.getParent() instanceof ViewGroup) {
            ((ViewGroup) customView.getParent()).removeView(customView);
        }
        fullscreenContainer.addView(customView, ReBrowserUi.matchMatch());
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(fullscreenContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setFullscreenSystemUi(true);
        activity.setRequestedOrientation(effectiveVideoOrientation());
    }

    boolean hideIfVisible() {
        if (fullscreenContainer == null) return false;
        hide();
        return true;
    }

    void hide() {
        if (fullscreenContainer == null) return;
        ViewGroup parent = (ViewGroup) fullscreenContainer.getParent();
        if (parent != null) parent.removeView(fullscreenContainer);
        fullscreenContainer.removeAllViews();
        fullscreenContainer = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        applyGlobalOrientationPreference();
        setFullscreenSystemUi(false);
        if (callback != null) callback.onCustomViewHidden();
    }

    void onConfigurationChanged() {
        if (styledRoot != null) styledRoot.requestApplyInsets();
        if (fullscreenContainer != null) fullscreenContainer.requestLayout();
    }

    void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && fullscreenContainer != null) setFullscreenSystemUi(true);
    }

    private int effectiveVideoOrientation() {
        String mode;
        if (preferences.videoOrientationOverrideEnabled()) {
            mode = preferences.videoOrientation();
        } else {
            mode = ReBrowserPreferences.ORIENTATION_UNLOCKED.equals(
                    preferences.globalOrientation())
                    ? ReBrowserPreferences.VIDEO_ORIENTATION_AUTO
                    : ReBrowserPreferences.ORIENTATION_LANDSCAPE;
        }
        if (ReBrowserPreferences.VIDEO_ORIENTATION_AUTO.equals(mode)) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
        }
        if (ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(mode)) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    private void setFullscreenSystemUi(boolean fullscreen) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                if (fullscreen) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else if (fullscreen) {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(systemUiBeforeFullscreen);
        }
        if (!fullscreen && styledRoot != null) WindowStyling.apply(activity, styledRoot);
    }
}
