package io.github.drtron1c.cloudphotos.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens Cloud Photos (bringing it back where you left it) and gets out of the way. */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName("io.github.drtron1c.cloudphotos", "io.github.drtron1c.cloudphotos.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cloud Photos isn't installed", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
