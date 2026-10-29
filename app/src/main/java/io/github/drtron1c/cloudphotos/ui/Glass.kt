package io.github.drtron1c.cloudphotos.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * Liquid Glass: controls float over the content as frosted, lens-like glass, the way iOS 26 does
 * it. The content behind is recorded once per frame (GraphicsLayer) and redrawn inside each piece
 * of glass, blurred, a little more saturated and bent near the edges (Android 13+), with a bright
 * rim. On Android 12 there's no lens; below that, or with the setting off, plain bars are used.
 */

/** Whether Liquid Glass is switched on (Settings › Appearance) and the phone can draw it. */
val LocalGlassEnabled = compositionLocalOf { false }

/** Content that glass on top of it can show through. Null = no live backdrop here. */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

fun glassSupported() = Build.VERSION.SDK_INT >= 31

/** Records the content it is applied to, for glass drawn above it. */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

/**
 * [content] is drawn normally; glass inside [overlay] shows it through. Glass inside [content]
 * never sees this backdrop (a layer can't contain itself).
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val backdrop = if (LocalGlassEnabled.current) rememberGlassBackdrop() else null
    Box(modifier) {
        Box(Modifier.fillMaxSize().glassSource(backdrop)) {
            CompositionLocalProvider(LocalGlassBackdrop provides null) { content() }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { overlay() }
    }
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * A piece of glass. [cornerRadius] null = capsule (fully rounded ends). [dark] picks a smoky
 * tint with light content (for use over photos); otherwise it follows the theme. [flat] is the
 * colour used when Liquid Glass is off.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    dark: Boolean? = null,
    elevation: Dp = 0.dp,
    flat: Color = LocalPalette.current.bar,
    blur: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val radiusPx = cornerRadius?.let { with(density) { it.toPx() } } ?: (min(size.width, size.height) / 2f)
    val shape = if (cornerRadius == null) RoundedCornerShape(50) else RoundedCornerShape(cornerRadius)
    val enabled = LocalGlassEnabled.current
    val backdrop = LocalGlassBackdrop.current
    val smoky = dark ?: p.isDark

    if (!enabled) {
        Box(modifier.then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier).clip(shape).background(flat), content = content)
        return
    }

    var pos by remember { mutableStateOf(Offset.Zero) }
    val blurPx = with(density) { blur.toPx() }
    val effect = remember(size, blurPx, radiusPx) {
        if (size.width > 0 && size.height > 0 && glassSupported()) glassEffect(size, blurPx, radiusPx) else null
    }
    // Enough frosting that text on top stays readable over busy, colourful photos.
    val tint = if (smoky) Color(0xFF121214).copy(alpha = if (backdrop != null) 0.44f else 0.74f)
    else Color.White.copy(alpha = if (backdrop != null) 0.46f else 0.84f)
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (smoky) 0.42f else 0.85f),
        0.45f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = if (smoky) 0.22f else 0.5f),
    )
    Box(
        modifier
            .then(
                if (elevation > 0.dp) {
                    Modifier.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
                } else Modifier
            )
            .onGloballyPositioned { pos = it.positionInRoot(); size = it.size }
            .clip(shape),
    ) {
        if (backdrop != null && effect != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = effect
                        clip = true
                        this.shape = shape
                    }
                    .drawBehind {
                        translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(tint)
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (smoky) 0.10f else 0.22f), 0.5f to Color.Transparent))
                .border(1.dp, rim, shape),
        )
        content()
    }
}

/** Blur → vibrancy → (Android 13+) edge refraction. */
private fun glassEffect(size: IntSize, blurPx: Float, radiusPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    val vibrant = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.45f) }),
        blur,
    )
    if (Build.VERSION.SDK_INT < 33) return vibrant.asComposeRenderEffect()
    val shader = RuntimeShader(LENS_SHADER).apply {
        setFloatUniform("size", size.width.toFloat(), size.height.toFloat())
        setFloatUniform("radius", radiusPx)
        setFloatUniform("band", min(size.width, size.height) * 0.42f)
        setFloatUniform("strength", min(size.width, size.height) * 0.16f)
    }
    val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
    return RenderEffect.createChainEffect(lens, vibrant).asComposeRenderEffect()
}

/** Pulls the picture inwards near the rim, so the edges magnify like a glass lens. */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float strength;

float roundRect(float2 p, float2 half_size, float r) {
    float2 q = abs(p) - half_size + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float d = roundRect(p, c, radius);
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    float e = 1.0;
    float2 n = float2(
        roundRect(p + float2(e, 0.0), c, radius) - roundRect(p - float2(e, 0.0), c, radius),
        roundRect(p + float2(0.0, e), c, radius) - roundRect(p - float2(0.0, e), c, radius));
    float len = length(n);
    if (len > 0.0001) n = n / len;
    float bend = t * t * t * strength;
    half4 col = content.eval(coord - n * bend);
    // A faint bright edge where the glass curves most.
    float edge = smoothstep(0.82, 1.0, t);
    return half4(mix(col.rgb, half3(1.0), half(edge * 0.10)), col.a);
}
"""

/**
 * A bottom bar: with Liquid Glass a floating glass capsule above the navigation bar, otherwise
 * the classic full-width bar.
 */
@Composable
fun GlassBar(navBottom: Dp, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    if (LocalGlassEnabled.current) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = navBottom + 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            GlassSurface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), cornerRadius = null, elevation = 10.dp) { content() }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(p.bar)) {
            HorizontalDivider(thickness = 0.5.dp, color = p.separator)
            content()
            Spacer(Modifier.height(navBottom))
        }
    }
}
