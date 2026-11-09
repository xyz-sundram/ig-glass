package dev.dimka.igglass

import android.graphics.RenderNode
import android.view.View
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * [Backdrop] samples the Instagram content recorded by [BackdropRecorder].
 */
internal class ViewBackdrop(
    private val target: View,
    private val node: RenderNode,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    // The render thread follows this node's updated display list without a bitmap copy.
    private val targetLocation = IntArray(2)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val canvas = drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || !node.hasDisplayList()) return

        // The node uses its parent's coordinates; Compose uses screen coordinates.
        target.getLocationOnScreen(targetLocation)
        val position = coordinates.positionOnScreen()
        translate(targetLocation[0] - position.x, targetLocation[1] - position.y) {
            drawContext.canvas.nativeCanvas.drawRenderNode(node)
        }
    }
}
