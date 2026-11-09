package dev.dimka.igglass

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Discovers asynchronously created/replaced bars without depending on X.* symbol names. */
internal class InstagramHooks(private val activity: Activity) : AutoCloseable {
    private val decor = activity.window.decorView
    private val tabBarId = activity.resources.getIdentifier("tab_bar", "id", Entry.PACKAGE)
    private var observer: ViewTreeObserver? = null
    private var installation: GlassInstaller? = null
    private var disabled = false
    private var reportedUnsupportedLayout = false
    private var lastStatus: String? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (!disabled) runCatching { update() }.onFailure { disable(it) }
        true
    }

    fun start() {
        if (tabBarId == 0) {
            Entry.log("tab_bar resource missing; leaving stock navigation")
            return
        }
        observer = decor.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        status("watching ${activity.javaClass.name}; tab_bar=0x${tabBarId.toString(16)}")
    }

    private fun update() {
        val nav = decor.findViewById<View>(tabBarId) as? ViewGroup
        val current = installation
        if (current != null && current.nav !== nav) {
            current.close()
            installation = null
        }
        if (nav == null) { status("waiting for tab_bar"); return }
        if (!nav.isAttachedToWindow) { status("waiting for tab_bar attachment"); return }
        if (installation == null) {
            // Large-screen rail and unknown experiments keep their native layout.
            if (nav !is LinearLayout || nav.orientation != LinearLayout.HORIZONTAL || nav.parent !is FrameLayout) {
                if (!reportedUnsupportedLayout) Entry.log("unsupported tab_bar layout ${nav.javaClass.name}; leaving stock navigation")
                reportedUnsupportedLayout = true
                return
            }
            if (!nav.isHardwareAccelerated) { status("tab_bar has no hardware acceleration"); return }
            if (nav.childCount !in 3..6) { status("waiting for tab_bar buttons: count=${nav.childCount}"); return }
            if (nav.width <= nav.height * 2) { status("waiting for horizontal tab_bar bounds: ${nav.width}x${nav.height}"); return }
            if ((0 until nav.childCount).any { nav.getChildAt(it).visibility == View.GONE }) {
                status("tab_bar contains hidden slots: count=${nav.childCount}")
                return
            }
            val candidate = GlassInstaller(nav, nav.parent as FrameLayout, ::disable)
            installation = candidate
            candidate.install()
            status("glass attached")
        }
        installation?.sync()
    }

    private fun status(message: String) {
        if (lastStatus == message) return
        lastStatus = message
        Entry.log(message)
    }

    private fun disable(error: Throwable) {
        if (disabled) return
        disabled = true
        Entry.log("glass disabled for this activity: ${error.stackTraceToString()}")
        runCatching { installation?.close() }.onFailure { Entry.log("restore stock navigation: $it") }
        installation = null
    }

    override fun close() {
        disabled = true
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        installation?.close()
        installation = null
    }
}
