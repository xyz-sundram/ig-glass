package dev.dimka.igglass

import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Records sibling content display lists before each actual window draw. The dock and native
 * tabs are excluded, so the backdrop can never contain a reference to its own RenderNode.
 * Instagram's hierarchy stays in place; drawChild preserves child transforms and alpha.
 */
internal class BackdropRecorder(private val parent: FrameLayout) : AutoCloseable {
    val node = RenderNode("IgGlassBackdrop")

    fun record(excluded: Set<View>) {
        if (parent.width <= 0 || parent.height <= 0) return
        node.setPosition(0, 0, parent.width, parent.height)
        val canvas = node.beginRecording(parent.width, parent.height)
        try {
            canvas.drawColor(HostTheme.background(parent.context))
            parent.background?.draw(canvas)
            // FrameLayout draws elevated children after its ordinary children.
            val children = (0 until parent.childCount).map(parent::getChildAt).sortedBy { it.z }
            for (child in children) {
                if (child.visibility == View.VISIBLE && child !in excluded) {
                    drawChild.invoke(parent, canvas, child, parent.drawingTime)
                }
            }
        } finally {
            node.endRecording()
        }
    }

    override fun close() = node.discardDisplayList()

    companion object {
        private val drawChild by lazy {
            ViewGroup::class.java.getDeclaredMethod(
                "drawChild", android.graphics.Canvas::class.java, View::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }
    }
}
