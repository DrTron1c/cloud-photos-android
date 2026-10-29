package io.github.drtron1c.cloudphotos.ai

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Downloads the AI model on the device and runs it, as Smart Search does. */
@RunWith(AndroidJUnit4::class)
class SmartSearchOnDeviceTest {
    private fun jpeg(color: Int): ByteArray {
        val b = Bitmap.createBitmap(360, 270, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test
    fun modelRunsOnDevice() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CloudPhotosApp).graph
        val smart = graph.smart
        val t0 = System.currentTimeMillis()
        assertTrue(smart.downloadModel(graph.http))
        Log.i("CompanionTest", "AI_DOWNLOAD ms=${System.currentTimeMillis() - t0}")
        val t1 = System.currentTimeMillis()
        val red = smart.embedPicture(jpeg(0xFFD01818.toInt()))!!
        val blue = smart.embedPicture(jpeg(0xFF1830D0.toInt()))!!
        Log.i("CompanionTest", "AI_EMBED ms_per_picture=${(System.currentTimeMillis() - t1) / 2}")
        val m = smart.model()
        val tRed = m.embedText("a photo of red")
        val tBlue = m.embedText("a photo of blue")
        Log.i("CompanionTest", "AI_SCORES red·red=${dot(red, tRed)} red·blue=${dot(red, tBlue)} blue·blue=${dot(blue, tBlue)} blue·red=${dot(blue, tRed)}")
        assertTrue(dot(red, tRed) > dot(red, tBlue))
        assertTrue(dot(blue, tBlue) > dot(blue, tRed))
        smart.deleteAll()
    }
}
