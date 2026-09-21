package com.example.musicplayerapp.ui.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withTranslation
import com.example.musicplayerapp.service.PlaybackAudioLevel
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The TV player's ambient background: a dark base under a luminous field built
 * from the current cover, moving with the music.
 *
 * ## What the field is
 *
 * Four layers, in this order: three large light masses spread across the frame and
 * one much brighter core offset from the centre. Each is a radial gradient whose
 * ramp gives it a luminous centre before it fades, so a layer reads as light with
 * a heart rather than as a soft dot - and where they overlap, each one's tail
 * lights the next.
 *
 * Four is not a taste, it is the budget this runs inside. The six-layer version -
 * three masses, a core and two separate highlight glows - rendered the same
 * picture but did enough per-frame work to take the TV emulator's *software* GL
 * stack down with an access violation within tens of seconds of live playback,
 * which the four-layer field below never does (see the crash-isolation notes in
 * the slice's history). The two highlights are therefore gone as draw calls and
 * the glow they carried is built into the core's own ramp: a lifted inner stop
 * *is* the highlight, it just does not cost a layer to draw.
 *
 * That order matters: the core is drawn last so it reads as the source of the light
 * rather than as more coloured fog, and it sits up and to the left of centre, which
 * is both where the artwork is not and where the eye expects a light source to be.
 *
 * This is not an enlarged, blurred cover and does not want to be one: nothing here
 * reads the bitmap. It is handed a palette by [TvAmbientPolicy] once per cover and
 * draws it, which is what keeps palette extraction off the frame path.
 *
 * ## Why it stays cheap on API 24
 *
 * No `RenderEffect`, no `RenderScript`, no blur, no bitmap, no shader rebuilt per
 * frame. Gradients are built once per palette change and a frame only moves and
 * fades them:
 *
 *   - position, radius and stretch come from `sin` of one looping phase; the shape
 *     is applied as a canvas transform, so the gradient object never changes. The
 *     shapes are axis-aligned on purpose: a rotated gradient is a different - and
 *     much more expensive - raster path on a software-rendered canvas, and the
 *     drift reads the same without it;
 *   - brightness comes from `Paint.alpha`, which multiplies the shader;
 *   - `onDraw` allocates nothing - the layer table and the phase offsets are in
 *     the companion, and both passes of a crossfade share one [Paint].
 *
 * ## Why it moves with the music
 *
 * The energy term is a real loudness reading taken from the audio pipeline itself
 * ([PlaybackAudioLevel]), smoothed here rather than at the source: a fast attack
 * and a slow release, so a beat is a swell rather than a flash, and a quiet
 * passage actually settles. All it does is expand the field and light it up - the
 * drift carries on underneath, so the frame keeps living when the music stops
 * instead of freezing on a still image.
 *
 * ## Lifecycle
 *
 * The drift runs only while the view is actually on screen: every way a view can
 * stop being shown - hidden, an ancestor hidden, the window hidden, the fragment
 * replaced, the view detached - is answered by the same `isShown` check in
 * [onVisibilityAggregated] and its three siblings, so there is no ordering between
 * them to get wrong. [stopAmbient] is the explicit half of that contract, called
 * from the player's `onDestroyView`.
 *
 * When animations are switched off system-wide (`Settings.Global.ANIMATOR_DURATION_SCALE`
 * of 0) no animator is started at all: the field is drawn once at its first frame,
 * and a palette change is applied instead of crossfaded. A value animator with an
 * infinite repeat and a zero duration scale is not a slower animation, it is a
 * loop that never advances.
 */
class TvAmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint().apply { style = Paint.Style.FILL }

    /**
     * One paint for every layer, in both passes of a crossfade. Its shader is
     * swapped per layer and its alpha per pass; neither allocates.
     */
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The palette this view has been asked for. Compared by value, never by identity. */
    private var target: TvAmbientPalette = TvAmbientPolicy.FALLBACK

    /**
     * The colours on screen now. The brand field is what the first frame shows, so
     * the player never opens on black, and the cover's own colours crossfade in
     * over it when they arrive.
     */
    private var currentColors: TvAmbientPalette = TvAmbientPolicy.FALLBACK
    private var currentField: Array<RadialGradient> = fieldOf(currentColors)

    /** Non-null only while a crossfade is in flight, with [fade] its progress. */
    private var incomingColors: TvAmbientPalette? = null
    private var incomingField: Array<RadialGradient>? = null
    private var fade = 1f

    /** Where the drift is, in loops. Kept across a stop so resuming does not jump. */
    private var phase = 0f

    /** The base, rebuilt only when the frame changes size. */
    private var baseShader: Shader? = null

    private var drift: ValueAnimator? = null
    private var crossfade: ValueAnimator? = null

    /** Smoothed loudness, and when it was last advanced. */
    private var energy = 0f
    private var energyAtMs = 0L

    /**
     * Shows the field for [palette], crossfading if there is something to
     * crossfade from.
     *
     * Calling this with the palette already showing does nothing at all: a repeated
     * metadata tick, or a new cover that reduces to the same colours, must not
     * restart the drift or replay the fade.
     */
    fun setAmbientPalette(palette: TvAmbientPalette) {
        if (palette == target) return
        target = palette

        val from = visibleFrame()
        if (from == null || !animationsEnabled()) {
            takeOver(palette)
            return
        }

        // A palette that arrives mid-crossfade starts from the colours actually on
        // screen, not from the ones that were fading out. On a station that changes
        // track quickly, restarting a fade from the old palette is a visible step
        // backwards.
        currentColors = from
        currentField = fieldOf(from)
        incomingColors = palette
        incomingField = fieldOf(palette)
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
     * Finishing rather than rewinding is the point: the next frame the viewer sees
     * must be the palette the current cover asked for, never a half-blended one
     * left over from a fade that was interrupted when the screen went away.
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
     * Every one of these is the same question, asked at a different moment. A view
     * can stop being shown because it was hidden, because an ancestor was, because
     * the window was, or because it was detached - and which callback fires first
     * is not something worth depending on. `isShown` is the single answer, so each
     * of them hands off to one check instead of each of them owning a case.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncDrift()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncDrift()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncDrift()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncDrift()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAmbient()
    }

    private fun syncDrift() {
        if (isShown) startDrift() else stopAmbient()
    }

    private fun startDrift() {
        if (drift != null) return
        if (!animationsEnabled()) {
            // Animations off: one good static frame, and no loop that never advances.
            invalidate()
            return
        }

        // Continue from where the last run stopped rather than from zero, so coming
        // back to the player does not snap the field to its first frame.
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

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        baseShader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            BASE_TOP_ARGB, BASE_BOTTOM_ARGB,
            Shader.TileMode.CLAMP,
        )
    }

    // ==================== palette plumbing ====================

    /** Drops any fade in progress and shows [palette] outright. */
    private fun takeOver(palette: TvAmbientPalette) {
        endCrossfade()
        currentColors = palette
        currentField = fieldOf(palette)
        incomingColors = null
        incomingField = null
        fade = 1f
        invalidate()
    }

    private fun endCrossfade() {
        if (crossfade?.isRunning == true) crossfade?.cancel()
        crossfade = null
    }

    /**
     * The palette on screen at this instant - a blend of both while a crossfade is
     * running - or null when nothing has been drawn yet.
     */
    private fun visibleFrame(): TvAmbientPalette? {
        val incoming = incomingColors ?: return currentColors
        val progress = fade
        return TvAmbientPalette(
            colors = List(TvAmbientPolicy.BLOB_COUNT) {
                blend(currentColors.colors[it], incoming.colors[it], progress)
            },
            core = blend(currentColors.core, incoming.core, progress),
            isFallback = if (progress < 0.5f) currentColors.isFallback else incoming.isFallback,
        )
    }

    private fun fieldOf(palette: TvAmbientPalette): Array<RadialGradient> =
        Array(LAYER_COUNT) { gradientOf(it, palette) }

    /**
     * One layer's gradient, in its own space: a unit circle at the origin, solid
     * at the centre and gone by the rim. The view scales and turns it into place,
     * so the object outlives every frame that draws it.
     *
     * The ramp is what makes a layer luminous instead of flat, and every ramp here
     * is three stops: a lifted centre, the colour it belongs to, and nothing at the
     * rim. The lift is what carries the highlight the six-layer version used to
     * draw as its own layer - a light with no bright centre is a smudge, and a
     * bright centre is a stop, not a draw call.
     */
    private fun gradientOf(index: Int, palette: TvAmbientPalette): RadialGradient {
        val core = palette.core
        return when (index) {
            in 0 until TvAmbientPolicy.BLOB_COUNT -> {
                val mass = palette.colors[index]
                RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(blend(mass, core, MASS_CORE_MIX), mass, fadeTo(mass, 0f)),
                    floatArrayOf(0f, MASS_BODY_STOP, 1f),
                    Shader.TileMode.CLAMP,
                )
            }

            CORE_INDEX -> RadialGradient(
                0f, 0f, 1f,
                intArrayOf(lift(core, CORE_LIFT), core, fadeTo(core, 0f)),
                floatArrayOf(0f, CORE_BODY_STOP, 1f),
                Shader.TileMode.CLAMP,
            )

            // Unreachable while LAYER_COUNT is BLOB_COUNT + 1, and kept as a mass so
            // that a future layer cannot silently draw an invented colour.
            else -> gradientOf(0, palette)
        }
    }

    // ==================== energy ====================

    /**
     * Advances the smoothed loudness by one frame.
     *
     * The attack is short and the release is long - about a tenth of a second up
     * and two thirds of a second down - which is the difference between a field
     * that swells with the track and one that strobes with it. The frame time is
     * clamped at both ends so a stalled frame cannot jump the field, and so a test
     * that draws frames back to back still advances.
     */
    private fun advanceEnergy(now: Long) {
        val elapsed = if (energyAtMs == 0L) {
            MIN_FRAME_MS
        } else {
            (now - energyAtMs).toFloat().coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
        }
        energyAtMs = now

        val loudness = PlaybackAudioLevel.read(now)
        val rate = if (loudness > energy) ATTACK_MS else RELEASE_MS
        val followed = (1.0 - exp(-(elapsed / rate).toDouble())).toFloat()
        energy += (loudness - energy) * followed
    }

    // ==================== drawing ====================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        advanceEnergy(SystemClock.uptimeMillis())

        basePaint.shader = baseShader
        if (baseShader != null) {
            canvas.drawRect(0f, 0f, w, h, basePaint)
        } else {
            canvas.drawColor(TvAmbientPolicy.BASE_ARGB)
        }

        val incoming = incomingField
        val progress = fade
        val now = phase
        val loudness = energy

        for (index in 0 until LAYER_COUNT) {
            val layer = LAYERS[index]

            val cx = w * (layer.x + layer.driftX * wobble(now, layer.phase))
            val cy = h * (layer.y + layer.driftY * wobble(now, layer.phase + 0.13f))
            val radius = w * layer.radius *
                (1f + layer.driftRadius * wobble(now, layer.phase + 0.71f)) *
                (1f + layer.energyRadius * loudness)
            val stretch = 1f + layer.stretch * wobble(now, layer.phase + 0.31f)
            val alpha = (layer.alpha * (1f + layer.energyAlpha * loudness))
                .coerceIn(layer.minAlpha, layer.maxAlpha)

            if (incoming == null) {
                drawLayer(canvas, currentField[index], cx, cy, radius, stretch, alpha)
            } else {
                // Out with the old as in with the new, so the middle of a fade is
                // two half-strength fields rather than a bright double exposure.
                drawLayer(canvas, currentField[index], cx, cy, radius, stretch, alpha * (1f - progress))
                drawLayer(canvas, incoming[index], cx, cy, radius, stretch, alpha * progress)
            }
        }
    }

    private fun drawLayer(
        canvas: Canvas,
        shader: RadialGradient,
        cx: Float,
        cy: Float,
        radius: Float,
        stretch: Float,
        alpha: Float,
    ) {
        if (alpha <= 0.004f || radius <= 0f) return

        blobPaint.shader = shader
        blobPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        // The local space is where the gradient was built: a unit circle at the
        // origin, stretched and scaled into place. Saving and restoring the canvas
        // is what the translation extension does, and it is inlined - no lambda
        // object per layer per frame.
        canvas.withTranslation(cx, cy) {
            scale(radius * stretch, radius / stretch)
            drawCircle(0f, 0f, 1f, blobPaint)
        }
    }

    /**
     * A slow, bounded wander in [-1, 1].
     *
     * The second term is a harmonic at exactly twice the rate, which is what stops
     * the layers reading as pendulums swinging together. Both terms are periodic in
     * [phase] over the whole loop, so the seam where the animator restarts is not
     * visible - a non-integer multiple here would show up as a tick once per period.
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

    private class Layer(
        /** Anchor, in fractions of the frame. */
        val x: Float,
        val y: Float,
        /** Radius at rest, in fractions of the width. */
        val radius: Float,
        /** Alpha at rest, and the range breathing is allowed to move it within. */
        val alpha: Float,
        val minAlpha: Float,
        val maxAlpha: Float,
        /** How much loudness adds to alpha, and to radius. */
        val energyAlpha: Float,
        val energyRadius: Float,
        /** How far the anchor wanders, in fractions of the frame. */
        val driftX: Float,
        val driftY: Float,
        val driftRadius: Float,
        /** How far the shape stretches, in one loop. */
        val stretch: Float,
        /** Where this layer sits in the loop, so they do not move as one. */
        val phase: Float,
    )

    private companion object {
        /**
         * How long one loop of the drift takes. Inside the 24-40s the design asks
         * for: long enough that the movement is ambient rather than a screensaver.
         */
        const val DRIFT_PERIOD_MS = 32_000L

        /** Palette crossfade, at the slow end of the 1.2-1.8s the design asks for. */
        const val PALETTE_FADE_MS = 1_400L

        /** The dark base, top to bottom. Slightly lifted at the top, never black. */
        const val BASE_TOP_ARGB = 0xFF0C0C16.toInt()
        const val BASE_BOTTOM_ARGB = 0xFF04040A.toInt()

        /**
         * How much of the core's tone each mass carries at its centre, and how far
         * the core's own centre is lifted above the palette's glow tone.
         *
         * The core's lift is what the deleted highlight layers used to contribute,
         * so it is stronger than the masses' - the centre of the field has to read
         * as the brightest thing on screen without a second gradient to say so.
         */
        const val MASS_CORE_MIX = 0.60f
        const val CORE_LIFT = 0.38f

        /**
         * Where each ramp stops holding its colour and starts fading: how far the
         * solid body of a mass and of the core reaches before the transparent rim.
         */
        const val MASS_BODY_STOP = 0.34f
        const val CORE_BODY_STOP = 0.40f

        /**
         * How fast loudness is followed: up in about a tenth of a second, down in
         * about two thirds of a second. Fast enough to feel the music, slow enough
         * that a beat is a swell and not a flash - and the clamps keep a stalled
         * frame from jumping the field.
         */
        const val ATTACK_MS = 90f
        const val RELEASE_MS = 700f
        const val MIN_FRAME_MS = 8f
        const val MAX_FRAME_MS = 100f

        /**
         * The layers, in draw order: three masses, then the core.
         *
         * The masses sit off the centre line - the artwork and the title are what
         * the player is about, and the field is what they sit on - and the core is
         * up and to the left of it, offset rather than behind the cover, which is
         * what makes the frame read as lit from one side.
         *
         * The core is where the music lands: it expands and brightens more than
         * anything else. The primary mass answers a little, the other two barely -
         * enough that a loud passage opens the field up, not enough to look like a
         * visualiser.
         */
        val LAYERS = arrayOf(
            // Masses. Sized and placed so the frame keeps its dark corners: a mass
            // that reaches the edge of the screen is not a light, it is a colour.
            Layer(0.20f, 0.30f, 0.34f, 0.72f, 0.42f, 0.98f, 0.14f, 0.08f, 0.045f, 0.038f, 0.090f, 0.26f, 0.00f),
            Layer(0.82f, 0.34f, 0.29f, 0.60f, 0.34f, 0.88f, 0.08f, 0.05f, 0.040f, 0.034f, 0.085f, 0.22f, 0.27f),
            Layer(0.34f, 0.80f, 0.31f, 0.52f, 0.28f, 0.80f, 0.08f, 0.05f, 0.050f, 0.040f, 0.095f, 0.28f, 0.51f),
            // The core: inside the main mass, brighter than anything else, and the
            // layer that moves most with the music.
            Layer(0.26f, 0.26f, 0.28f, 0.66f, 0.38f, 0.92f, 0.55f, 0.34f, 0.026f, 0.024f, 0.100f, 0.18f, 0.63f),
        )

        val LAYER_COUNT = LAYERS.size
        /** The core is the layer after the masses. */
        const val CORE_INDEX = TvAmbientPolicy.BLOB_COUNT

        const val TWO_PI = (2.0 * PI).toFloat()

        /** [color] mixed [amount] of the way towards [other], alpha dropped. */
        fun blend(color: Int, other: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = (color shr shift) and 0xFF
                val b = (other shr shift) and 0xFF
                return (a + (b - a) * amount).roundToInt().coerceIn(0, 255)
            }
            return 0xFF000000.toInt() or
                (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        /** [color] towards white, keeping its hue and its alpha. */
        fun lift(color: Int, amount: Float): Int = blend(color, 0xFFFFFFFF.toInt(), amount)

        /** [color] at a new alpha - used to write the transparent end of a ramp. */
        fun fadeTo(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24)
    }
}
