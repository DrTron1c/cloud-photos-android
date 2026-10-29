package io.github.drtron1c.cloudphotos.identity;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.apksig.ApkVerifier;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Runs on a real Android device / emulator: makes a companion app exactly as the app does,
 * signed with the Android Keystore key, and checks the result. (Java, so it doesn't depend on
 * Kotlin library code the release build may have trimmed.)
 */
@RunWith(AndroidJUnit4.class)
public class CompanionOnDeviceTest {
    @Test
    public void makesSignedCompanionWithKeystoreKey() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        Bitmap src = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        src.eraseColor(0xFFE91E63);
        Bitmap icon = IconArt.INSTANCE.render(src, 1.5f, 10f, -5f, 600f);
        byte[] png = CustomLauncher.INSTANCE.saveIcon(ctx, icon);
        File apk = CustomLauncher.INSTANCE.make(ctx, "Made On Device", png);

        ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
        assertTrue(result.getErrors().toString(), result.isVerified());
        assertTrue(result.isVerifiedUsingV2Scheme());

        // Second build must be signed by the same key, or updates would fail.
        File again = CustomLauncher.INSTANCE.make(ctx, "Made On Device 2", png);
        ApkVerifier.Result second = new ApkVerifier.Builder(again).build().verify();
        assertTrue(second.isVerified());
        assertTrue(java.util.Arrays.equals(
                result.getSignerCertificates().get(0).getEncoded(),
                second.getSignerCertificates().get(0).getEncoded()));

        // Install it through the app's own installer code. The test borrows the shell's install
        // permission so no one has to tap "Install" on the emulator.
        inst.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.INSTALL_PACKAGES);
        try {
            CustomLauncher.INSTANCE.install(ctx, again);
            String label = null;
            for (int i = 0; i < 60 && !"Made On Device 2".equals(label); i++) {
                Thread.sleep(500);
                label = CustomLauncher.INSTANCE.installedLabel(ctx);
            }
            Log.i("CompanionTest", "INSTALLED_LABEL=" + label);
            assertTrue("installed label was " + label, "Made On Device 2".equals(label));
            assertTrue(CustomLauncher.INSTANCE.canUpdateInstalled(ctx));
        } finally {
            inst.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    /**
     * Records what Android does to the open app when its launcher entry is switched off
     * (this decides whether "hide the original icon" can happen right away).
     */
    @Test
    public void recordsWhatHappensWhenOpenEntryIsDisabled() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        String pkg = ctx.getPackageName();
        ComponentName alias = new ComponentName(pkg, pkg + ".alias.CloudPhotos");
        PackageManager pm = ctx.getPackageManager();
        Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(alias).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = inst.startActivitySync(open);
        Thread.sleep(2000);
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        Thread.sleep(4000);
        boolean closed = activity.isFinishing() || activity.isDestroyed();
        Log.i("CompanionTest", "DISABLE_OPEN_ALIAS closed_activity=" + closed);
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        if (!closed) activity.finish();
    }
}
