package dev.dimka.igglass

import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import java.util.IdentityHashMap

/** Preserve the native drawable and its color accessors without hooking hot draw methods. */
internal class BackgroundHider : AutoCloseable {
    private val views = IdentityHashMap<View, List<Drawable>>()

    fun sync(targets: Collection<View>) {
        views.keys.toList().filter { it !in targets }.forEach(::restore)
        targets.forEach { view ->
            val background = view.background
            if (views[view]?.firstOrNull() !== background) {
                restore(view)
                if (background != null) {
                    val drawables = mutableListOf<Drawable>()
                    fun collect(drawable: Drawable) {
                        drawables += drawable
                        when (drawable) {
                            is InsetDrawable -> drawable.drawable?.let(::collect)
                            is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) collect(drawable.getDrawable(i))
                        }
                    }
                    collect(background)
                    views[view] = drawables
                    drawables.forEach { saved.putIfAbsent(it, it.alpha) }
                }
            }
            views[view]?.forEach { if (it.alpha != 0) it.alpha = 0 }
        }
    }

    private fun restore(view: View) {
        val drawables = views.remove(view) ?: return
        drawables.forEach { drawable ->
            if (views.values.none { drawable in it }) saved.remove(drawable)?.let { drawable.alpha = it }
        }
        view.invalidate()
    }

    override fun close() = views.keys.toList().forEach(::restore)
    private val saved = IdentityHashMap<Drawable, Int>()
}
