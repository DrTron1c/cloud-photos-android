package io.github.drtron1c.cloudphotos.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Draws the Liquid Glass pieces over colourful "photos" and saves screenshots for review. */
@RunWith(AndroidJUnit4::class)
class GlassRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(mode: String) {
        rule.setContent {
            CloudPhotosTheme(themeMode = mode) {
                CompositionLocalProvider(LocalGlassEnabled provides true) {
                    GlassHost(Modifier.fillMaxSize(), content = {
                        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize()) {
                            items(80) { i ->
                                val hue = (i * 37) % 360
                                Box(
                                    Modifier.aspectRatio(1f).padding(1.dp).background(
                                        Brush.linearGradient(listOf(Color.hsv(hue.toFloat(), 0.8f, 0.95f), Color.hsv(((hue + 60) % 360).toFloat(), 0.9f, 0.6f)))
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) { Text("$i", color = Color.White, fontSize = 22.sp) }
                            }
                        }
                    }) {
                        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BannerCard {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Update available", color = LocalPalette.current.label, fontSize = 17.sp)
                                    Text("Cloud Photos build 99 · 40 MB", color = LocalPalette.current.secondaryLabel, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(text = "Select") {}
                                Pill(icon = Icons.Filled.Settings) {}
                                Pill(text = "Light", onDark = false) {}
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            GlassBar(24.dp) {
                                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Library", color = LocalPalette.current.accent)
                                    Text("Albums", color = LocalPalette.current.secondaryLabel)
                                    Text("Search", color = LocalPalette.current.secondaryLabel)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(500)
        save(rule.onRoot().captureToImage().asAndroidBitmap(), "glass-$mode.png")
    }

    private fun save(bmp: Bitmap, name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun glassLight() = render("light")

    @Test
    fun glassDark() = render("dark")
}
