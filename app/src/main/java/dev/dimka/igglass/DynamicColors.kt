package dev.dimka.igglass

import android.content.res.Resources

/** The dock uses the system palette without replacing Instagram's resource colors. */
object DynamicColors {
    private var appResources: Resources? = null

    fun bind(resources: Resources) { appResources = resources }

    fun accent(dark: Boolean): Int = (appResources ?: Resources.getSystem()).getColor(
        if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600,
        null,
    )
}
