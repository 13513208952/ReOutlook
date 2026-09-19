package io.github.reoutlook;

import android.content.Context;
import android.content.Intent;

/** Routes both desktop entries and in-app switches to the requested application mode. */
final class ModeRouter {
    private ModeRouter() {}

    static void openOutlook(Context context) {
        start(context, MainActivity.class);
    }

    static void openReBrowser(Context context) {
        start(context, ReBrowserActivity.class);
    }

    private static void start(Context context, Class<?> activityClass) {
        Intent intent = new Intent(context, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }
}
