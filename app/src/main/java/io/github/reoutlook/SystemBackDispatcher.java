package io.github.reoutlook;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedDispatcher;

/** Routes button and gesture navigation through the same application back policy. */
final class SystemBackDispatcher {
    private SystemBackDispatcher() {}

    static void register(Activity activity, Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, action::run);
        }
    }
}
