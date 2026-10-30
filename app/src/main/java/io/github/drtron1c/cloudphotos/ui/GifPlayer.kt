package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.droidsonroids.gif.GifDecoder
import pl.droidsonroids.gif.InputSource

/**
 * Plays a GIF like a video — same picture, controls, scrub bar, frame strip, frame-by-frame
 * stepping and speeds — by decoding its frames on the device and drawing them onto the video's
 * TextureView. Loops like the iPhone does.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class GifPlayer(
    private val cacheKey: String,
    private val load: suspend () -> ByteArray,
) : SimpleBasePlayer(Looper.getMainLooper()), FrameSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The decoder isn't thread-safe: everything touching it runs here, one at a time. */
    private val decoding = Dispatchers.Default.limitedParallelism(1)

    private var bytes: ByteArray? = null
    private var decoder: GifDecoder? = null
    private var buffer: Bitmap? = null
    private var shownFrame = -1
    /** When each frame starts (ms) and the whole length. */
    @Volatile private var starts = LongArray(0)
    @Volatile private var lengthMs = 0L

    private var state = Player.STATE_IDLE
    private var wantPlay = false
    private var repeat = Player.REPEAT_MODE_ONE
    private var speed = 1f
    private var vol = 1f
    private var size = VideoSize.UNKNOWN
    private var error: PlaybackException? = null
    // Position = anchorPos + time since anchorClock × speed, while playing.
    private var anchorPos = 0L
    private var anchorClock = 0L
    private var ticker: Job? = null

    private val surfaceLock = Any()
    private var surface: Surface? = null
    private var ownsSurface = false
    private var textureView: TextureView? = null
    private val wanted = Channel<Int>(Channel.CONFLATED)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        scope.launch(decoding) { for (i in wanted) draw(i) }
    }

    private val running get() = wantPlay && state == Player.STATE_READY
    private fun now() = SystemClock.elapsedRealtime()

    private fun rawPosition(): Long = anchorPos + if (running) ((now() - anchorClock) * speed).toLong() else 0L

    private fun position(): Long {
        val len = lengthMs
        if (len <= 0) return 0
        val p = rawPosition()
        return if (repeat != Player.REPEAT_MODE_OFF) Math.floorMod(p, len) else p.coerceIn(0, len)
    }

    private fun reanchor() {
        anchorPos = position()
        anchorClock = now()
    }

    private fun frameAt(ms: Long): Int {
        val s = starts
        if (s.isEmpty()) return 0
        val k = s.binarySearch(ms)
        return (if (k >= 0) k else -k - 2).coerceIn(0, s.size - 1)
    }

    override fun getState(): SimpleBasePlayer.State {
        val item = SimpleBasePlayer.MediaItemData.Builder("gif:$cacheKey")
            .setDurationUs(if (lengthMs > 0) lengthMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(lengthMs > 0)
            .build()
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_GET_VOLUME,
                    Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                ).build(),
            )
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setRepeatMode(repeat)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(vol)
            .setVideoSize(size)
            .setPlaylist(listOf(item))
            .setContentPositionMs { position() }
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (bytes != null) {
            if (state == Player.STATE_IDLE) state = Player.STATE_READY
            anchorClock = now()
            restartTicker()
            return Futures.immediateVoidFuture()
        }
        if (state != Player.STATE_IDLE) return Futures.immediateVoidFuture()
        state = Player.STATE_BUFFERING
        error = null
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { load() }
                val info = withContext(decoding) { open(data) }
                bytes = data
                starts = info.first
                lengthMs = info.second
                size = VideoSize(info.third.first, info.third.second)
                state = Player.STATE_READY
                anchorPos = 0
                anchorClock = now()
                invalidateState()
                restartTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = Player.STATE_IDLE
                error = PlaybackException("This GIF couldn't be played", e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Opens the GIF (on the decoding thread): frame start times, length and size. */
    private fun open(data: ByteArray): Triple<LongArray, Long, Pair<Int, Int>> {
        val d = GifDecoder(InputSource.ByteArraySource(data))
        decoder = d
        buffer = Bitmap.createBitmap(d.width.coerceAtLeast(1), d.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val n = d.numberOfFrames.coerceAtLeast(1)
        val s = LongArray(n)
        var t = 0L
        for (i in 0 until n) {
            s[i] = t
            // Like browsers: frames with no (or a tiny) delay show for 0.1 s.
            val ms = d.getFrameDuration(i).let { if (it < 20) 100 else it }
            t += ms
        }
        return Triple(s, t.coerceAtLeast(1), d.width to d.height)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        reanchor()
        wantPlay = playWhenReady
        restartTicker()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val len = lengthMs
        val p = if (positionMs == C.TIME_UNSET) 0L else positionMs
        anchorPos = if (len > 0) p.coerceIn(0, len - 1) else 0
        anchorClock = now()
        if (state == Player.STATE_ENDED) state = Player.STATE_READY
        restartTicker()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        reanchor()
        speed = playbackParameters.speed.coerceIn(0.05f, 8f)
        restartTicker()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        reanchor()
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        vol = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is TextureView -> attach(videoOutput)
            is Surface -> { detach(); setSurface(videoOutput, owned = false) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        detach()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        reanchor()
        ticker?.cancel()
        if (state != Player.STATE_IDLE) state = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        ticker?.cancel()
        detach()
        wanted.close()
        scope.cancel()
        // Let any frame being drawn finish, then free the decoder.
        CoroutineScope(decoding).launch {
            decoder?.recycle()
            decoder = null
            buffer = null
        }
        return Futures.immediateVoidFuture()
    }

    /** Keeps the picture moving while playing; one frame at a time, on time. */
    private fun restartTicker() {
        ticker?.cancel()
        show(frameAt(position()))
        if (!running || lengthMs <= 0) return
        ticker = scope.launch {
            while (isActive) {
                if (repeat == Player.REPEAT_MODE_OFF && rawPosition() >= lengthMs) {
                    anchorPos = lengthMs
                    anchorClock = now()
                    state = Player.STATE_ENDED
                    show(starts.size - 1)
                    invalidateState()
                    break
                }
                val p = position()
                val i = frameAt(p)
                show(i)
                val next = if (i + 1 < starts.size) starts[i + 1] else lengthMs
                delay(((next - p) / speed).toLong().coerceIn(4L, 1000L))
            }
        }
    }

    private fun show(i: Int) {
        if (i >= 0) wanted.trySend(i)
    }

    /** On the decoding thread: decode frame [i] (if it isn't the one in the buffer) and draw it. */
    private fun draw(i: Int) {
        val d = decoder ?: return
        val b = buffer ?: return
        if (i != shownFrame) {
            runCatching { d.seekToFrame(i, b) }.onFailure { return }
            shownFrame = i
        }
        synchronized(surfaceLock) {
            val s = surface ?: return
            if (!s.isValid) return
            val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                c.drawColor(Color.BLACK)
                c.drawBitmap(b, null, Rect(0, 0, c.width, c.height), paint)
            } finally {
                runCatching { s.unlockCanvasAndPost(c) }
            }
        }
    }

    private fun setSurface(s: Surface?, owned: Boolean) {
        synchronized(surfaceLock) {
            if (ownsSurface) surface?.release()
            surface = s
            ownsSurface = owned && s != null
        }
        show(frameAt(position()))
    }

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = setSurface(Surface(st), owned = true)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = show(frameAt(position()))
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { setSurface(null, owned = false); return true }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun attach(view: TextureView) {
        detach()
        textureView = view
        view.surfaceTextureListener = listener
        if (view.isAvailable) view.surfaceTexture?.let { setSurface(Surface(it), owned = true) }
    }

    private fun detach() {
        textureView?.let { if (it.surfaceTextureListener === listener) it.surfaceTextureListener = null }
        textureView = null
        setSurface(null, owned = false)
    }

    // ---- FrameSource: the strip and the frame-by-frame buttons ----

    override val key: String get() = "gif:$cacheKey"

    override val aspect: Float get() = if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 1f

    override fun step(positionMs: Long, frames: Int, durationMs: Long): Long {
        val s = starts
        if (s.isEmpty()) return positionMs
        return s[(frameAt(positionMs) + frames).coerceIn(0, s.size - 1)]
    }

    override suspend fun pictures(timesMs: List<Long>, px: Int, onPicture: suspend (Int, Bitmap) -> Unit) {
        val data = bytes ?: return
        withContext(Dispatchers.Default) {
            // Its own decoder, so playback isn't disturbed; frames in time order (decoding runs forwards).
            val d = runCatching { GifDecoder(InputSource.ByteArraySource(data)) }.getOrNull() ?: return@withContext
            try {
                val buf = Bitmap.createBitmap(d.width.coerceAtLeast(1), d.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                for ((i, t) in timesMs.withIndex().sortedBy { it.value }) {
                    ensureActive()
                    d.seekToFrame(frameAt(t), buf)
                    onPicture(i, buf.scaledTo(px).let { if (it === buf) buf.copy(Bitmap.Config.ARGB_8888, false) else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                d.recycle()
            }
        }
    }
}
