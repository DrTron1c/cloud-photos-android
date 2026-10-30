package io.github.drtron1c.cloudphotos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A cropped video never shows (or keeps offline) iCloud's copies of the uncropped original. */
class EditedVideoTest {
    private fun v(name: String, w: Int, h: Int) = Version("https://example.invalid/$name", 1, name, null, w, h)

    private fun video(edited: Version?, medium: Version?, thumb: Version?, videoMedium: Version?) = Asset(
        "id", "a", "CPLAsset", "t", 0, 0, false, false, "com.apple.quicktime-movie", "IMG_1.MOV", 1920, 1080, true, 5.0,
        thumb, medium, edited, v("original", 1920, 1080), null, videoMedium,
    )

    @Test
    fun croppedVideoSkipsStillsOfTheOriginal() {
        val crop = v("edit", 1080, 1080)
        val a = video(crop, medium = v("medium", 1920, 1080), thumb = v("thumb", 480, 270), videoMedium = v("vmed", 1280, 720))
        assertNull("no still has the crop's shape", a.stillFor(a.playbackVideo))
        assertSame("offline copy is the edit, not the original's smaller stream", crop, a.offlineVideo)
        assertSame(crop, a.playbackVideo)
    }

    @Test
    fun croppedVideoUsesAStillOfTheCrop() {
        val crop = v("edit", 1080, 1080)
        val cropThumb = v("thumb", 360, 360)
        val a = video(crop, medium = v("medium", 1920, 1080), thumb = cropThumb, videoMedium = v("vmed", 720, 720))
        assertSame(cropThumb, a.stillFor(a.playbackVideo))
        assertEquals("vmed", a.offlineVideo?.checksum)
    }

    @Test
    fun uneditedVideoKeepsItsStills() {
        val medium = v("medium", 1920, 1080)
        val a = video(null, medium = medium, thumb = v("thumb", 480, 270), videoMedium = v("vmed", 1280, 720))
        assertSame(medium, a.stillFor(a.playbackVideo))
        assertEquals("vmed", a.offlineVideo?.checksum)
    }
}
