package io.github.reoutlook;

import android.app.Activity;
import android.os.Bundle;

/** Exported launcher trampoline that never forwards external actions, extras, or data. */
public final class ReBrowserLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModeRouter.openReBrowser(this);
        finish();
    }
}
