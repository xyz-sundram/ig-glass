package dev.dimka.igglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Reserves space for native controls while leaving the video behind the floating dock. */
internal class ReelControls(private val root: ViewGroup, private val nav: View) : AutoCloseable {
    private val infoId = id("clips_viewer_media_info_container")
    private val mediaId = id("clips_media_component")
    private val lithoInfoId = id("clips_media_info_component")
    private val lithoActionsId = id("clips_ufi_component")
    private data class Offset(var baseline: Float, var shift: Float = 0f)
    private val offsets = IdentityHashMap<View, Offset>()
    private val scrubberContainerId = id("clips_attached_scrubber_container")
    private val scrubberId = id("scrubber")
    private val hairlineId = id("scrubber_hairline")
    private var lastTimeline: View? = root
    private val margins = IdentityHashMap<View, Int>()
    private val alphas = IdentityHashMap<View, Float>()
    private val sliders = IdentityHashMap<SeekBar, SliderStyle>()
    private val location = IntArray(2)
    private val navLocation = IntArray(2)
    fun sync() {
        if (!nav.isShown || nav.alpha <= 0f) { restore(); return }
        nav.getLocationInWindow(navLocation)
        val found = mutableSetOf<View>()
        listOf(lithoInfoId, lithoActionsId).forEach { targetId ->
            root.findViewById<View>(targetId)?.let { view ->
                found += view
                lift(view, dp(44))
            }
        }
        root.findViewById<View>(infoId)?.let { view ->
            found += view
            reserveMargin(view, dp(44))
        }
        val attached = root.findViewById<View>(scrubberContainerId)
        attached?.let { view ->
            found += view
            reserveMargin(view, dp(8))
            view.findViewById<View>(hairlineId)?.let { hairline ->
                found += hairline
                alphas.getOrPut(hairline) { hairline.alpha }
                hairline.alpha = 0f
            }
        }
        // Resolve the real control directly; Litho owns the surrounding mounted hosts.
        val nativeBar = root.findViewById<View>(scrubberId)
        if (nativeBar !== lastTimeline) {
            lastTimeline = nativeBar
            Entry.log("Reels timeline id=$scrubberId view=$nativeBar seek=${nativeBar is SeekBar}")
        }
        val bar = nativeBar as? SeekBar
        if (bar != null) {
            found += bar
            sliders.getOrPut(bar) { SliderStyle(bar) }.apply()
            if (attached == null) {
                var host: View = bar
                while (host.parent is View && (host.parent as View).id != mediaId) {
                    host = host.parent as View
                }
                if ((host.parent as? View)?.id == mediaId) {
                    found += host
                    lift(host, dp(8))
                }
            }
        }
        offsets.keys.toList().filter { it !in found }.forEach(::restoreOffset)
        margins.keys.toList().filter { it !in found }.forEach(::restoreMargin)
        alphas.keys.toList().filter { it !in found }.forEach { view ->
            alphas.remove(view)?.let { view.alpha = it }
        }
        sliders.keys.toList().filter { it !in found }.forEach { sliders.remove(it)?.close() }
    }

    private fun reserveMargin(view: View, clearance: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (view.height <= 0) return
        val original = margins.getOrPut(view) { params.bottomMargin }
        view.getLocationInWindow(location)
        val stockBottom = location[1] + view.height + params.bottomMargin - original
        val desired = original + max(0, stockBottom - navLocation[1] + clearance)
        if (params.bottomMargin != desired) {
            params.bottomMargin = desired
            view.layoutParams = params
        }
    }

    private fun lift(view: View, clearance: Int) {
        if (view.height <= 0) return
        val offset = offsets.getOrPut(view) { Offset(view.translationY) }
        if (kotlin.math.abs(view.translationY - offset.baseline - offset.shift) > 0.5f) {
            offset.baseline = view.translationY
            offset.shift = 0f
        }
        view.getLocationInWindow(location)
        val stockBottom = location[1] + view.height - offset.shift
        offset.shift = kotlin.math.min(0f, navLocation[1] - clearance - stockBottom)
        view.translationY = offset.baseline + offset.shift
    }

    private fun restoreOffset(view: View) {
        val offset = offsets.remove(view) ?: return
        if (kotlin.math.abs(view.translationY - offset.baseline - offset.shift) < 0.5f) {
            view.translationY = offset.baseline
        }
    }

    private fun restoreMargin(view: View) {
        val original = margins.remove(view) ?: return
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = original
            view.layoutParams = it
        }
    }

    private fun restore() {
        offsets.keys.toList().forEach(::restoreOffset)
        margins.keys.toList().forEach(::restoreMargin)
        alphas.forEach { (view, alpha) -> view.alpha = alpha }; alphas.clear()
        sliders.values.forEach { it.close() }; sliders.clear()
    }

    override fun close() = restore()
    private fun id(name: String) = root.resources.getIdentifier(name, "id", Entry.PACKAGE)
    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
}

/** Restyles the real SeekBar: progress, seeking, previews, and accessibility remain native. */
private class SliderStyle(private val bar: SeekBar) : AutoCloseable {
    private val density = bar.resources.displayMetrics.density
    private val originalProgress = bar.progressDrawable
    private val originalThumb = bar.thumb
    private val originalOffset = bar.thumbOffset
    private val originalPadding = intArrayOf(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom)
    private val originalMinHeight = bar.minimumHeight
    private val track = LayerDrawable(arrayOf(
        GlassTrack(density, 0, 0x55ffffff),
        GlassTrack(density, 1, 0x88ffffff.toInt()),
        GlassTrack(density, 1, Color.WHITE),
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.secondaryProgress)
        setId(2, android.R.id.progress)
    }
    private val thumb = GlassTrack(density, 2, Color.WHITE)

    fun apply() {
        if (bar.progressDrawable !== track) bar.progressDrawable = track
        if (bar.thumb !== thumb) bar.thumb = thumb
        bar.thumbOffset = (10 * density).roundToInt()
        val inset = (16 * density).roundToInt()
        if (bar.paddingLeft != inset || bar.paddingRight != inset) {
            bar.setPadding(inset, originalPadding[1], inset, originalPadding[3])
        }
        bar.minimumHeight = max(originalMinHeight, (28 * density).roundToInt())
    }

    override fun close() {
        bar.progressDrawable = originalProgress
        bar.thumb = originalThumb
        bar.thumbOffset = originalOffset
        bar.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
        bar.minimumHeight = originalMinHeight
    }
}

/** Quiet rounded playback track; a translucent, highlighted lens appears while scrubbing. */
private class GlassTrack(private val density: Float, private val kind: Int, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var pressed = false
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = android.R.attr.state_pressed in state || android.R.attr.state_focused in state
        if (pressed == next) return false
        pressed = next; invalidateSelf(); return true
    }
    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun getIntrinsicWidth() = if (kind == 2) (20 * density).roundToInt() else -1
    override fun getIntrinsicHeight() = ((if (kind == 2) 20 else 6) * density).roundToInt()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val y = b.exactCenterY()
        if (kind == 2) {
            if (!pressed) return
            paint.color = 0xccffffff.toInt()
            canvas.drawOval(b.left.toFloat(), y - 7 * density, b.right.toFloat(), y + 7 * density, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.WHITE
            canvas.drawOval(b.left + density, y - 6 * density, b.right - density, y + 6 * density, paint)
            paint.style = Paint.Style.FILL
        } else {
            val half = (if (pressed) 3f else 1.5f) * density
            val fraction = if (kind == 0) 1f else level / 10000f
            if (fraction <= 0f) return
            val width = b.width() * fraction
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val left = if (rtl) b.right - width else b.left.toFloat()
            canvas.drawRoundRect(left, y - half, left + width, y + half, half, half, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
