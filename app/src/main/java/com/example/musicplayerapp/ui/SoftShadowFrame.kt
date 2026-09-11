package com.example.musicplayerapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.R
import kotlin.math.ceil

/**
 * Draws a frozen Figma drop shadow behind its child.
 *
 * ## Why not elevation
 *
 * Android elevation paints one shadow, symmetric about the view, in a colour and
 * softness the platform picks from a single dp number. Two of the shadows in the
 * frozen file are not that shape:
 *
 *  - the HOME stream banner's is offset **3 to the right** as well as 11 down,
 *    and is 25% black - `Mask group` under `плашка потока MYATA` / `... GOLD`,
 *    identical on both pages;
 *  - the PLAYER artwork's is **two** layers with *negative* spread, so each one
 *    is smaller than the artwork and reads as a tight contact shadow under a
 *    wider soft one.
 *
 * G1-G3 approximated both with `cardElevation`, and the style comment on
 * `HomeStreamCard` said so in as many words: 8dp was "the closest single value to
 * the dominant one". This draws the actual parameters instead.
 *
 * ## How it draws outside itself
 *
 * A View's own `onDraw` is not clipped to its bounds - the clip comes from the
 * *parent's* `clipChildren`. So this frame is exactly the size of the card it
 * wraps, changes no layout geometry, and paints the overhang beyond its edges.
 * Every ancestor up to the one with room must therefore have `clipChildren` and
 * `clipToPadding` off, which is what the layouts using this set. If a shadow ever
 * looks cropped on one side, that is the ancestor to check, not the numbers here.
 *
 * ## Why the mask is cached
 *
 * `BlurMaskFilter` is not supported by the hardware canvas on every API this app
 * ships to - on API 24 it is silently ignored, which would paint a hard-edged
 * rectangle rather than a shadow. Rendering it through a software layer instead
 * would drag the child card into software rendering with it.
 *
 * So the blur is rasterised once per size into an **ALPHA_8** bitmap using a
 * software canvas, where the filter always works, and `onDraw` just blits that
 * mask through a coloured paint. The child stays hardware-accelerated, the result
 * is identical on 24 and 36, and an alpha-only bitmap costs a quarter of what an
 * ARGB one would - about 0.6MB for a 316x198 banner at 3x rather than 2.5MB.
 *
 * Each layer's opacity is baked into the mask, so the paint carries only the
 * colour and the two layers keep their different alphas.
 */
class SoftShadowFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * One Figma `DROP_SHADOW`, in dp and in the file's own terms.
     *
     * [blur] is Figma's blur value, not a radius: Figma's blur B is a Gaussian
     * whose standard deviation is B/2, and `BlurMaskFilter` takes that sigma - so
     * the halving happens once, here, rather than at every call site.
     */
    private data class Layer(
        val dx: Float,
        val dy: Float,
        val blur: Float,
        val spread: Float,
        val alpha: Float,
    )

    private var layers: List<Layer> = emptyList()
    private var radiusDp = 0f
    private var shadowColor = Color.BLACK

    private var mask: Bitmap? = null
    private var maskLeft = 0
    private var maskTop = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        val a = context.obtainStyledAttributes(attrs, R.styleable.SoftShadowFrame)
        val spec = a.getInt(R.styleable.SoftShadowFrame_softShadowSpec, SPEC_NONE)
        a.recycle()
        applySpec(spec)
    }

    private fun applySpec(spec: Int) {
        shadowColor = ContextCompat.getColor(context, R.color.figma_shadow)
        when (spec) {
            SPEC_HOME_STREAM_CARD -> {
                // `Live Streams Section > Container` carries (0,2) blur 6 at 10%
                // over the whole row, and each card's `Mask group` carries
                // (3,11) blur 10 at 25%. Both are drawn here, per card: the row
                // shadow is the same shape as the cards it sits under, and one
                // shadow under the row would show between them.
                radiusDp = 14f
                layers = listOf(
                    Layer(dx = 0f, dy = 2f, blur = 6f, spread = 0f, alpha = 0.10f),
                    Layer(dx = 3f, dy = 11f, blur = 10f, spread = 0f, alpha = 0.25f),
                )
            }
            SPEC_PLAYER_ARTWORK -> {
                // `Album Art (Organic Shape) > Current Track Artwork`, identical
                // on both pages. Negative spread on both layers: each shadow is
                // drawn smaller than the artwork, which is what keeps it from
                // haloing out sideways.
                radiusDp = 20f
                layers = listOf(
                    Layer(dx = 0f, dy = 5.975f, blur = 7.46875f, spread = -4.48125f, alpha = 0.10f),
                    Layer(dx = 0f, dy = 14.9375f, blur = 18.6719f, spread = -3.73438f, alpha = 0.10f),
                )
            }
            else -> layers = emptyList()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMask(w, h)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun rebuildMask(w: Int, h: Int) {
        mask?.recycle()
        mask = null
        if (layers.isEmpty() || w <= 0 || h <= 0) return

        // How far past each edge the softest layer reaches. Sigma is blur/2 and a
        // Gaussian is visually done by about 3 sigma; 1.5 * blur is that, and it
        // is what the mask has to be padded by so nothing is cut off inside its
        // own bitmap.
        var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
        for (l in layers) {
            val reach = 1.5f * l.blur + l.spread
            left = maxOf(left, reach - l.dx)
            right = maxOf(right, reach + l.dx)
            top = maxOf(top, reach - l.dy)
            bottom = maxOf(bottom, reach + l.dy)
        }
        maskLeft = ceil(dp(maxOf(left, 0f))).toInt()
        maskTop = ceil(dp(maxOf(top, 0f))).toInt()
        val padRight = ceil(dp(maxOf(right, 0f))).toInt()
        val padBottom = ceil(dp(maxOf(bottom, 0f))).toInt()

        val bmp = Bitmap.createBitmap(
            w + maskLeft + padRight,
            h + maskTop + padBottom,
            Bitmap.Config.ALPHA_8,
        )
        val c = Canvas(bmp)
        val r = dp(radiusDp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (l in layers) {
            val s = dp(l.spread)
            val rect = RectF(
                maskLeft + dp(l.dx) - s,
                maskTop + dp(l.dy) - s,
                maskLeft + w + dp(l.dx) + s,
                maskTop + h + dp(l.dy) + s,
            )
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            // Alpha only - the colour is applied when the mask is blitted, so a
            // layer's own opacity has to live in the mask itself.
            p.color = Color.argb((l.alpha * 255).toInt(), 0, 0, 0)
            p.maskFilter = if (l.blur > 0f) BlurMaskFilter(dp(l.blur / 2f), BlurMaskFilter.Blur.NORMAL) else null
            c.drawRoundRect(rect, maxOf(r + s, 0f), maxOf(r + s, 0f), p)
        }
        mask = bmp
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = mask
        if (bmp != null && !bmp.isRecycled) {
            paint.color = shadowColor
            canvas.drawBitmap(bmp, -maskLeft.toFloat(), -maskTop.toFloat(), paint)
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mask?.recycle()
        mask = null
    }

    private companion object {
        const val SPEC_NONE = -1
        const val SPEC_HOME_STREAM_CARD = 0
        const val SPEC_PLAYER_ARTWORK = 1
    }
}
