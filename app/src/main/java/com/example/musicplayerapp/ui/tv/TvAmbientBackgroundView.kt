package com.example.musicplayerapp.ui.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The TV player's ambient background: a constant dark base with four large soft
 * areas of colour on it, drifting slowly, derived from the current cover.
 *
 * ## Why a view and not the artwork
 *
 * This is not an enlarged, blurred cover and does not want to be one. Nothing
 * here reads the bitmap - it is handed four colours by [TvAmbientPolicy] once per
 * cover and draws them as [RadialGradient] blobs, which is what keeps palette
 * extraction off the frame path entirely.
 *
 * ## Why it stays cheap on API 24
 *
 * There is no `RenderEffect`, no `RenderScript`, no blur, no bitmap and no
 * shader rebuilt per frame. The gradients are built once per palette change, and
 * a frame only translates, scales and fades them:
 *
 *   - position and radius come from `sin` of a single looping phase, and the
 *     radius is applied as a canvas scale, so the gradient object itself never
 *     changes;
 *   - brightness comes from `Paint.alpha`, which multiplies the shader, so
 *     neither the gradient nor a bitmap is rebuilt to breathe;
 *   - `onDraw` allocates nothing: the anchors and phase offsets are arrays in the
 *     companion, and the two passes of a crossfade share one [Paint].
 *
 * ## Lifecycle
 *
 * The drift runs only while the view is actually on screen. [onVisibilityAggregated]
 * starts it and stops it - which covers the fragment being replaced, hidden, or
 * the app going to the background - and [onDetachedFromWindow] stops it as well.
 * [stopAmbient] is the explicit half of that contract, called from the player's
 * `onDestroyView`.
 *
 * When animations are switched off system-wide (`Settings.Global.ANIMATOR_DURATION_SCALE`
 * of 0) no animator is started at all: the field is drawn once at its first
 * frame, and a palette change is applied instead of crossfaded. A value animator
 * with an infinite repeat and a zero duration scale is not a slower animation,
 * it is a loop that never advances.
 */
class TvAmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint().apply { color = TvAmbientPolicy.BASE_ARGB }

    /**
     * One paint for every blob, in both passes of a crossfade. Its shader is
     * swapped per blob and its alpha per pass; neither allocates.
     */
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The palette this view has been asked for. Compared by value, never by identity. */
    private var target: TvAmbientPalette = TvAmbientPolicy.FALLBACK

    /**
     * The colours on screen now. The brand field is what the first frame shows,
     * so the player never opens on black, and the cover's own colours crossfade
     * in over it when they arrive.
     */
    private var currentColors: List<Int> = TvAmbientPolicy.FALLBACK.colors
    private var currentShaders: Array<RadialGradient> = shadersFor(currentColors)

    /** Non-null only while a crossfade is in flight, with [fade] its progress. */
    private var incomingColors: List<Int>? = null
    private var incomingShaders: Array<RadialGradient>? = null
    private var fade = 1f

    /** Where the drift is, in loops. Kept across a stop so resuming does not jump. */
    private var phase = 0f

    private var drift: ValueAnimator? = null
    private var crossfade: ValueAnimator? = null

    /**
     * Shows the field for [palette], crossfading if there is something to
     * crossfade from.
     *
     * Calling this with the palette already showing does nothing at all: a
     * repeated metadata tick, or a new cover that reduces to the same colours,
     * must not restart the drift or replay the fade.
     */
    fun setAmbientPalette(palette: TvAmbientPalette) {
        if (palette == target) return
        target = palette

        val from = visibleFrame()
        if (from == null || !animationsEnabled()) {
            takeOver(palette.colors)
            return
        }

        // A palette that arrives mid-crossfade starts from the colours actually on
        // screen, not from the ones that were fading out. On a station that
        // changes track quickly, restarting a fade from the old palette is a
        // visible step backwards.
        currentColors = from
        currentShaders = shadersFor(from)
        incomingColors = palette.colors
        incomingShaders = shadersFor(palette.colors)
        fade = 0f

        crossfade?.cancel()
        crossfade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PALETTE_FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                fade = it.animatedFraction
                invalidate()
            }
            doOnEnd {
                val settled = incomingColors
                if (settled != null) takeOver(settled) else invalidate()
            }
            start()
        }
    }

    /**
     * Stops the drift and finishes any crossfade in flight.
     *
     * Finishing rather than rewinding is the point: the next frame the viewer
     * sees must be the palette the current cover asked for, never a half-blended
     * one left over from a fade that was interrupted when the screen went away.
     */
    fun stopAmbient() {
        drift?.cancel()
        drift = null

        val settled = incomingColors
        if (settled != null) takeOver(settled) else endCrossfade()
    }

    /** True while the drift is running. Not public behaviour: a test seam. */
    @androidx.annotation.VisibleForTesting
    internal fun isDrifting(): Boolean = drift?.isRunning == true

    // ==================== lifecycle ====================

    /**
     * `onVisibilityAggregated` rather than `onVisibilityChanged`: it is the
     * aggregate one, so hiding an ancestor or the window stops the drift too,
     * which is what the fragment being replaced from the outside looks like.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startDrift() else stopAmbient()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAmbient()
    }

    private fun startDrift() {
        if (drift != null) return
        if (!animationsEnabled()) {
            // Animations off: one good static frame, and no loop that never advances.
            invalidate()
            return
        }

        // Continue from where the last run stopped rather than from zero, so
        // coming back to the player does not snap the field to its first frame.
        val start = phase
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DRIFT_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = (start + it.animatedFraction) % 1f
                invalidate()
            }
            start()
        }
    }

    // ==================== palette plumbing ====================

    /** Drops any fade in progress and shows [colors] outright. */
    private fun takeOver(colors: List<Int>) {
        endCrossfade()
        currentColors = colors
        currentShaders = shadersFor(colors)
        incomingColors = null
        incomingShaders = null
        fade = 1f
        invalidate()
    }

    private fun endCrossfade() {
        if (crossfade?.isRunning == true) crossfade?.cancel()
        crossfade = null
    }

    /**
     * The colours on screen at this instant - a blend of both palettes while a
     * crossfade is running - or null when nothing has been drawn yet.
     */
    private fun visibleFrame(): List<Int>? {
        val incoming = incomingColors ?: return currentColors
        val progress = fade
        return List(TvAmbientPolicy.BLOB_COUNT) { blend(currentColors[it], incoming[it], progress) }
    }

    /** Two opaque colours mixed, for the frame a fade was interrupted on. */
    private fun blend(from: Int, to: Int, progress: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * progress).roundToInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun shadersFor(colors: List<Int>): Array<RadialGradient> =
        Array(TvAmbientPolicy.BLOB_COUNT) { gradientOf(colors[it]) }

    /**
     * One blob's gradient, in its own space: a circle of radius 1 at the origin,
     * solid at the centre and gone by the rim. The view scales it into place, so
     * the object outlives every frame that draws it.
     *
     * The two stops between the centre and the rim are what make it read as a
     * soft area rather than a lens. The rim stop is the same hue at zero alpha
     * rather than transparent black, which is what stops the edge going grey.
     */
    private fun gradientOf(color: Int): RadialGradient = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(color, color and 0x00FFFFFF, color and 0x00FFFFFF),
        floatArrayOf(0f, EDGE_STOP, 1f),
        Shader.TileMode.CLAMP,
    )

    // ==================== drawing ====================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(basePaint.color)

        val incoming = incomingShaders
        val progress = fade
        val now = phase

        for (slot in 0 until TvAmbientPolicy.BLOB_COUNT) {
            val cx = w * (ANCHOR_X[slot] + X_DRIFT * wobble(now, slot * 0.23f))
            val cy = h * (ANCHOR_Y[slot] + Y_DRIFT * wobble(now, 0.37f + slot * 0.19f))
            val radius = w * ANCHOR_R[slot] * (1f + R_DRIFT * wobble(now, 0.61f + slot * 0.31f))
            val alpha = (
                BASE_ALPHA[slot] * (1f + A_DRIFT * wobble(now, 0.83f + slot * 0.41f))
                ).coerceIn(MIN_ALPHA, MAX_ALPHA)

            if (incoming == null) {
                drawBlob(canvas, currentShaders[slot], cx, cy, radius, alpha)
            } else {
                // Out with the old as in with the new, so the middle of a fade is
                // two half-strength fields rather than a bright double exposure.
                drawBlob(canvas, currentShaders[slot], cx, cy, radius, alpha * (1f - progress))
                drawBlob(canvas, incoming[slot], cx, cy, radius, alpha * progress)
            }
        }
    }

    private fun drawBlob(
        canvas: Canvas,
        shader: RadialGradient,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Float,
    ) {
        if (alpha <= 0.004f || radius <= 0f) return

        blobPaint.shader = shader
        blobPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        val restore = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, blobPaint)
        canvas.restoreToCount(restore)
    }

    /**
     * A slow, bounded wander in [-1, 1].
     *
     * The second term is a harmonic at exactly twice the rate, which is what
     * stops the field reading as four pendulums. Both terms are periodic in
     * [phase] over the whole loop, so the seam where the animator restarts is
     * not visible - a non-integer multiple here would show up as a tick once per
     * period.
     */
    private fun wobble(phase: Float, offset: Float): Float {
        val first = sin(TWO_PI * (phase + offset))
        val second = sin(TWO_PI * (2f * phase + offset * 1.7f))
        return (first + 0.35f * second) / 1.35f
    }

    /** What is on screen when animations are switched off system-wide. */
    private fun animationsEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            // areAnimatorsEnabled() is API 26; this is the value it reads.
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }
    } catch (e: SecurityException) {
        // Readable in practice, but a field must not take a surface down over a
        // setting it could not read. Animating is the better failure.
        true
    }

    private companion object {
        /**
         * How long one loop of the drift takes. Inside the 24-40s the design asks
         * for: long enough that the movement is ambient rather than a screensaver.
         */
        const val DRIFT_PERIOD_MS = 32_000L

        /** Palette crossfade, at the slow end of the 1.2-1.8s the design asks for. */
        const val PALETTE_FADE_MS = 1_400L

        /** Where the gradient reaches zero alpha, as a fraction of the radius. */
        const val EDGE_STOP = 0.72f

        /** Movement, as a fraction of the frame - tens of pixels on a 1080p panel. */
        const val X_DRIFT = 0.030f
        const val Y_DRIFT = 0.026f
        const val R_DRIFT = 0.060f
        const val A_DRIFT = 0.120f

        /** Overall blob alpha, before breathing, per blob. */
        val BASE_ALPHA = floatArrayOf(0.50f, 0.42f, 0.46f, 0.38f)
        val MIN_ALPHA = 0.30f
        val MAX_ALPHA = 0.55f

        /**
         * Anchors in fractions of the frame. Not a grid: two of the four sit off
         * the edges and none sits on the centre line, which is where the cover
         * and the title are, so the field never competes with the two things the
         * player is actually about.
         */
        val ANCHOR_X = floatArrayOf(0.18f, 0.86f, 0.36f, 0.72f)
        val ANCHOR_Y = floatArrayOf(0.24f, 0.34f, 0.88f, 0.70f)
        val ANCHOR_R = floatArrayOf(0.46f, 0.40f, 0.42f, 0.32f)

        const val TWO_PI = (2.0 * PI).toFloat()
    }
}
