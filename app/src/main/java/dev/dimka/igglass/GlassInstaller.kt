package dev.dimka.igglass

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.dimka.igglass.catalog.LiquidBottomTab
import dev.dimka.igglass.catalog.LiquidBottomTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns every mutation so a failed installation or a replaced bar can restore the host. */
internal class GlassInstaller(
    val nav: LinearLayout,
    private val parent: FrameLayout,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val originalLayout = FrameLayout.LayoutParams(nav.layoutParams as FrameLayout.LayoutParams)
    private val originalPadding = intArrayOf(nav.paddingLeft, nav.paddingTop, nav.paddingRight, nav.paddingBottom)
    private val originalGravity = nav.gravity
    private val originalClipChildren = parent.clipChildren
    private val originalClipPadding = parent.clipToPadding
    private val backgrounds = BackgroundHider()
    private val hidden = IdentityHashMap<View, Float>()
    private val contentMargins = IdentityHashMap<View, Int>()
    private val excluded = mutableSetOf<View>()
    private val owner = GlassOwner()
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) nav.post { if (!closed) onFailure(error) }
    }
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + exceptionHandler)
    private val recomposer = Recomposer(AndroidUiDispatcher.Main + exceptionHandler)
    private val recorder = BackdropRecorder(parent)
    private val reels = ReelControls(parent, nav)
    private val glass = ComposeView(nav.context)
    private val tabs = NativeTabs(nav, glass, onFailure)
    private val contentIds = listOf("layout_container_main", "swipeable_tab_view_pager").map(::id).toSet()
    private val hiddenIds = listOf("tab_bar_shadow", "floating_tab_bar_nav_bar_gradient").map(::id).toSet()
    private var closed = false

    fun install() {
        glass.apply {
            tag = "ig-liquid-glass"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setParentCompositionContext(recomposer)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        scope.launch {
            try { recomposer.runRecomposeAndApplyChanges() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Throwable) { if (!closed) onFailure(error) }
        }
        // Insert immediately behind the native bar; the original children keep all their state.
        parent.addView(glass, parent.indexOfChild(nav), FrameLayout.LayoutParams(originalLayout))
        parent.clipChildren = false
        parent.clipToPadding = false
        excluded += nav
        excluded += glass
        tabs.attach()
        val backdrop = ViewBackdrop(parent, recorder.node)
        glass.setContent { InstagramDock(backdrop, tabs) }
        Entry.log("installed glass under ${nav.childCount} native tabs (${nav.javaClass.name})")
    }

    fun sync() {
        if (closed) return
        check(nav.parent === parent && nav.childCount in 3..6) { "tab_bar hierarchy changed" }
        check((0 until nav.childCount).none { nav.getChildAt(it).visibility == View.GONE }) { "tab_bar contains hidden slots" }
        tabs.sync()
        backgrounds.sync(listOf(nav) + tabs.items)

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.id != View.NO_ID && child.id in hiddenIds) {
                if (child !in hidden) hidden[child] = child.alpha
                child.alpha = 0f
                excluded += child
            }
            if (child.id != View.NO_ID && child.id in contentIds) {
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (child !in contentMargins) contentMargins[child] = params.bottomMargin
                    if (params.bottomMargin != 0) {
                        params.bottomMargin = 0
                        child.layoutParams = params
                    }
                }
            }
        }

        val layout = nav.layoutParams as FrameLayout.LayoutParams
        val bottom = max(originalLayout.bottomMargin, bottomInset()) + dp(8)
        val left = max(originalLayout.leftMargin, dp(16))
        val right = max(originalLayout.rightMargin, dp(16))
        if (layout.height != dp(64) || layout.leftMargin != left || layout.rightMargin != right ||
            layout.bottomMargin != bottom || layout.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            layout.height = dp(64)
            layout.width = ViewGroup.LayoutParams.MATCH_PARENT
            layout.leftMargin = left
            layout.rightMargin = right
            layout.marginStart = if (nav.layoutDirection == View.LAYOUT_DIRECTION_RTL) right else left
            layout.marginEnd = if (nav.layoutDirection == View.LAYOUT_DIRECTION_RTL) left else right
            layout.bottomMargin = bottom
            nav.layoutParams = layout
        }
        nav.gravity = Gravity.CENTER_VERTICAL
        if (nav.paddingLeft != dp(4) || nav.paddingRight != dp(4) || nav.paddingTop != 0 || nav.paddingBottom != 0) {
            nav.setPadding(dp(4), 0, dp(4), 0)
        }
        syncGlassLayout(layout)
        reels.sync()
        (glass.getChildAt(0) as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        glass.visibility = nav.visibility
        glass.alpha = nav.alpha
        glass.translationX = nav.translationX
        glass.translationY = nav.translationY
        glass.scaleX = nav.scaleX
        glass.scaleY = nav.scaleY
        glass.pivotX = nav.pivotX
        glass.pivotY = nav.pivotY
        glass.layoutDirection = nav.layoutDirection
        glass.elevation = nav.elevation
        glass.translationZ = nav.translationZ
        if (nav.isShown && nav.alpha > 0f) recorder.record(excluded)
    }

    private fun syncGlassLayout(layout: FrameLayout.LayoutParams) {
        val current = glass.layoutParams as FrameLayout.LayoutParams
        if (current.width != layout.width || current.height != layout.height || current.gravity != layout.gravity ||
            current.leftMargin != layout.leftMargin || current.rightMargin != layout.rightMargin ||
            current.topMargin != layout.topMargin || current.bottomMargin != layout.bottomMargin ||
            current.marginStart != layout.marginStart || current.marginEnd != layout.marginEnd) {
            glass.layoutParams = FrameLayout.LayoutParams(layout)
        }
    }

    private fun bottomInset(): Int {
        val inset = parent.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        parent.getLocationInWindow(location)
        parent.rootView.getLocationInWindow(rootLocation)
        val spaceBelow = rootLocation[1] + parent.rootView.height - location[1] - parent.height
        return (inset - spaceBelow).coerceAtLeast(0)
    }

    private fun id(name: String): Int = nav.resources.getIdentifier(name, "id", Entry.PACKAGE)
    private fun dp(value: Int): Int = (value * nav.resources.displayMetrics.density).roundToInt()

    override fun close() {
        if (closed) return
        closed = true
        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { Entry.log("glass cleanup: $it") }
        }
        cleanup { reels.close() }
        cleanup { tabs.close() }
        cleanup { glass.disposeComposition() }
        cleanup { parent.removeView(glass) }
        cleanup { recomposer.cancel() }
        cleanup { scope.cancel() }
        cleanup { owner.destroy() }
        cleanup { backgrounds.close() }
        hidden.forEach { (view, alpha) -> cleanup { view.alpha = alpha } }
        contentMargins.forEach { (view, margin) ->
            cleanup {
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.bottomMargin = margin; view.layoutParams = it }
            }
        }
        cleanup { if (nav.parent === parent) nav.layoutParams = FrameLayout.LayoutParams(originalLayout) }
        cleanup { nav.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3]) }
        cleanup { nav.gravity = originalGravity }
        cleanup { parent.clipChildren = originalClipChildren }
        cleanup { parent.clipToPadding = originalClipPadding }
        cleanup { recorder.close() }
    }
}

@Composable
private fun InstagramDock(backdrop: ViewBackdrop, tabs: NativeTabs) {
    val items = tabs.items
    if (items.isEmpty()) return
    val glass = remember(tabs.isLight) { AdaptiveGlass(tabs.isLight) }
    // Recreate the drag range when Instagram changes the tab set or its order.
    key(items, tabs.gestureRevision) {
        val selected = remember(tabs) { { tabs.selectedIndex } }
        LiquidBottomTabs(
            selectedTabIndex = selected,
            onTabSelected = {},
            onTabReselected = {},
            backdrop = backdrop,
            tabsCount = items.size,
            glass = glass,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Native icons, avatars and badges draw above these transparent gesture slots.
            items.forEach { LiquidBottomTab(onClick = {}) {} }
        }
    }
}

private class GlassOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
