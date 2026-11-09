package dev.dimka.igglass

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Instagram keeps drawing and handling its real buttons. Touches are also mirrored to the
 * glass for the press animation; only a horizontal drag starting on the selection is consumed.
 */
internal class NativeTabs(
    private val nav: LinearLayout,
    private val glass: View,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    var items by mutableStateOf(emptyList<View>()); private set
    var selectedIndex by mutableIntStateOf(0); private set
    var isLight by mutableStateOf(HostTheme.isLight(nav.context)); private set
    var gestureRevision by mutableIntStateOf(0); private set

    private val slop = ViewConfiguration.get(nav.context).scaledTouchSlop
    private val navLocation = IntArray(2)
    private val glassLocation = IntArray(2)
    private var startX = 0f
    private var startY = 0f
    private var canDrag = false
    private var dragging = false
    private var cancelledStream = false
    private var mirrorEnabled = true
    private var closed = false
    private val dispatchMethod = generateSequence<Class<*>>(nav.javaClass) { it.superclass }
        .mapNotNull { type ->
            runCatching { type.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java) }.getOrNull()
        }.first()

    fun attach() {
        registry[nav] = this
        if (dispatchMethod !in hooks) {
            hooks[dispatchMethod] = XposedBridge.hookMethod(dispatchMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tabs = registry[param.thisObject] ?: return
                    runCatching {
                        val event = param.args[0] as MotionEvent
                        val consumed = tabs.touch(event) {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(cancel)) }
                            finally { cancel.recycle() }
                        }
                        if (consumed) param.result = true
                    }.onFailure { tabs.onFailure(it) }
                }
            })
        }
        sync()
    }

    fun sync() {
        val children = (0 until nav.childCount).map(nav::getChildAt)
        if (children != items) items = children
        children.indexOfFirst { it.isSelected || it.isActivated }.takeIf { it >= 0 }?.let { selectedIndex = it }
        isLight = HostTheme.isLight(nav)
    }

    private fun hit(x: Float): Int = items.indices.minByOrNull { index ->
        val child = items[index]
        abs(x - (child.left + child.right) / 2f)
    } ?: 0

    private fun touch(event: MotionEvent, cancelNative: () -> Unit): Boolean {
        if (closed || items.isEmpty()) return false
        if (cancelledStream && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                cancelledStream = false
                nav.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
                cancelledStream = false
                mirrorEnabled = true
                canDrag = hit(startX) == selectedIndex
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Leave multi-touch to Instagram; cancel any glass press.
                val consumed = dragging
                canDrag = false
                dragging = false
                mirror(event, MotionEvent.ACTION_CANCEL)
                mirrorEnabled = false
                cancelledStream = consumed
                if (consumed) nav.parent?.requestDisallowInterceptTouchEvent(false)
                gestureRevision++
                return consumed
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && canDrag) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dy > slop || event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
                        canDrag = false
                        mirror(event, MotionEvent.ACTION_CANCEL)
                        mirrorEnabled = false
                        gestureRevision++
                    }
                    if (canDrag && dx > slop && dx > dy) {
                        dragging = true
                        cancelNative()
                        nav.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
        }
        if (mirrorEnabled) mirror(event)
        val consumed = dragging
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val selected = if (dragging && event.actionMasked == MotionEvent.ACTION_UP) items.getOrNull(hit(event.x)) else null
            dragging = false
            canDrag = false
            if (consumed) nav.parent?.requestDisallowInterceptTouchEvent(false)
            if (selected?.isEnabled == true) {
                selected.performClick()
                sync()
                // Create/camera can open without selecting a tab. Reconcile after Compose's
                // release handler; a cancelled drag must also return to the native selection.
                if (items.getOrNull(selectedIndex) !== selected) resetGlassGesture()
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) resetGlassGesture()
        }
        return consumed
    }

    private fun resetGlassGesture() { nav.post { if (!closed) gestureRevision++ } }

    private fun mirror(event: MotionEvent, action: Int? = null) {
        nav.getLocationInWindow(navLocation)
        glass.getLocationInWindow(glassLocation)
        val copy = MotionEvent.obtain(event)
        try {
            if (action != null) copy.action = action
            copy.offsetLocation((navLocation[0] - glassLocation[0]).toFloat(), (navLocation[1] - glassLocation[1]).toFloat())
            glass.dispatchTouchEvent(copy)
        } finally { copy.recycle() }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (dragging) nav.parent?.requestDisallowInterceptTouchEvent(false)
        registry.remove(nav)
        // Retain the bridge while ART may be compiling it; an absent registry entry is inert.
    }

    companion object {
        private val registry = IdentityHashMap<Any, NativeTabs>()
        private val hooks = mutableMapOf<Method, XC_MethodHook.Unhook>()
    }
}
