package com.example.musicplayerapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.R

/**
 * The Mini Player's connecting face: three dots, as the frozen file draws them.
 *
 * ## What is frozen and what is not
 *
 * `Component / Dark / Mini-player / buffering` 2436:771 draws three 5x5 ellipses
 * at x = 13, 21, 29 and y = 21 inside the trailing 48x48 slot, filled #5FD9B4.
 * So the **geometry and the colour are frozen**: 5dp across, 8dp between
 * centres, 21dp of dots centred in the slot.
 *
 * The **timing is not**. A Figma frame is one still, and the file has no
 * prototype or smart-animate data anywhere in it - so the pulse below is chosen,
 * not derived, and is deliberately restrained: each dot fades between 35% and
 * 100% over 600ms, staggered 150ms apart, which is the slowest cadence that
 * still reads as "working" rather than "stuck".
 *
 * ## Why not a ProgressBar
 *
 * The slot used to hold an indeterminate `ProgressBar` - a spinning arc, which is
 * the platform's idiom and not this design's. The frozen file specifies no
 * spinner anywhere, in either the mini player or the PLAYER's own control; where
 * it shows loading it shows either these dots or the skeleton rows of
 * `history-loading` 2517:2420.
 *
 * ## Colour, and why it is not `primary`
 *
 * The dots are #5FD9B4 in the file, which is `primary` on the Dark page. They
 * cannot be bound to `primary`, because the pill under them is #1C4771 in *both*
 * themes and light's `primary` is that same #1C4771 - the dots would be navy on
 * navy. They get their own token for the same reason `brand_mini_player_icon`
 * has one: the pill does not change with the theme, so neither does what is
 * drawn on it.
 */
class BufferingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val a = context.obtainStyledAttributes(attrs, R.styleable.BufferingDotsView)
        color = a.getColor(
            R.styleable.BufferingDotsView_dotColor,
            ContextCompat.getColor(context, R.color.brand_mini_player_buffering_dot),
        )
        a.recycle()
    }

    private val diameter = resources.getDimension(R.dimen.buffering_dot_size)
    private val pitch = resources.getDimension(R.dimen.buffering_dot_pitch)

    /**
     * 0..1, advanced by the animator; each dot reads it at its own offset.
     *
     * Rests at 0, where the three dots stand at 35%, 67% and 100% - an ascending
     * ramp left to right. That is the face kept when animations are switched off
     * ("Remove animations" sets `animator_duration_scale` to 0), and it was chosen
     * over the earlier resting quarter because of what that one looked like held
     * still: dim - bright - dim is a symmetric ellipsis, a "more" glyph, and the
     * owner read the static PLAYER control as exactly that. A ramp has a direction
     * and reads as progress even when nothing moves.
     *
     * It is also the animator's own first frame, so with animations on the pulse
     * starts from the same picture it rests on. A test or a layout preview -
     * neither of which attaches the view to a window, so neither starts the
     * animator - rasterises this face too.
     */
    private var phase = REST_PHASE
    private var animator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        val r = diameter / 2f
        val span = diameter + 2 * pitch
        var cx = (width - span) / 2f + r
        val cy = height / 2f
        for (i in 0 until DOTS) {
            paint.alpha = (alphaFor(i) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, paint)
            cx += pitch
        }
    }

    /**
     * A triangle wave per dot, offset by a quarter of the cycle each - 150ms at
     * the 600ms [CYCLE_MS]. Each dot is a quarter-cycle further along than the
     * one to its left, so it peaks 150ms *before* it: the brightest point travels
     * right to left. Measured off the emulator's own display recorder at
     * `animator_duration_scale` 1: 579ms mean peak-to-peak over 18 cycles and
     * 134ms between neighbours, against the 600 / 150 set here.
     */
    private fun alphaFor(index: Int): Float {
        val t = (phase + index * STAGGER) % 1f
        val up = if (t < 0.5f) t * 2f else (1f - t) * 2f
        return MIN_ALPHA + (1f - MIN_ALPHA) * up
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun start() {
        if (animator != null) return
        // Honour the system's animation scale: at 0 - "Remove animations" in
        // accessibility settings, and what the instrumentation harness sets - the
        // dots stay lit rather than running a loop nothing will see.
        if (animatorScale() == 0f) {
            phase = REST_PHASE
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun animatorScale(): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private companion object {
        const val DOTS = 3

        /** The phase the dots rest at: an ascending 35 / 67 / 100% ramp - see [phase]. */
        const val REST_PHASE = 0f
        const val CYCLE_MS = 600L
        const val STAGGER = 0.25f
        const val MIN_ALPHA = 0.35f
    }
}
