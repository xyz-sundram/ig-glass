package dev.dimka.igglass

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object HostTheme {
    private var attemptedColorReader = false
    private var barColorReader: Method? = null

    fun background(context: Context): Int {
        val attr = context.resources.getIdentifier("igds_color_primary_background", "attr", Entry.PACKAGE)
        val value = TypedValue()
        for (id in intArrayOf(attr, android.R.attr.colorBackground)) {
            if (id == 0 || !context.theme.resolveAttribute(id, value, true)) continue
            if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
            if (value.resourceId != 0) {
                runCatching { context.getColor(value.resourceId) }.getOrNull()?.let { return it }
            }
        }
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
    }

    fun isLight(context: Context): Boolean = Color.luminance(background(context)) > 0.5f

    /** Optional accessor verified in the guarded build; Reels can override the bar's theme. */
    fun isLight(view: View): Boolean {
        if (!attemptedColorReader) {
            attemptedColorReader = true
            barColorReader = runCatching {
                view.context.classLoader.loadClass("X.02bT").declaredMethods.single {
                    Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaObjectType &&
                        it.parameterTypes.contentEquals(arrayOf(View::class.java))
                }.apply { isAccessible = true }
            }.onFailure { Entry.log("native bar color reader unavailable; using activity theme: $it") }.getOrNull()
        }
        val color = runCatching { barColorReader?.invoke(null, view) as? Int }.getOrNull()
            ?: background(view.context)
        return Color.luminance(color) > 0.5f
    }
}
