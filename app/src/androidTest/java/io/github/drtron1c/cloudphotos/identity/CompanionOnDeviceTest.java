package io.github.drtron1c.cloudphotos.identity;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.apksig.ApkVerifier;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Runs on a real Android device / emulator: makes a companion app exactly as the app does,
 * signed with the Android Keystore key, and checks the result. (Java, so it doesn't depend on
 * Kotlin library code the release build may have trimmed.)
 */
@RunWith(AndroidJUnit4.class)
public class CompanionOnDeviceTest {
    @Test
    public void makesSignedCompanionWithKeystoreKey() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
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

        // For the CI smoke test to install.
        File out = new File(ctx.getExternalFilesDir(null), "device-made.apk");
        Files.copy(again.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
