package com.tony3974.bingeguard.enforce

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tony3974.bingeguard.ui.theme.BingeGuardTheme

/**
 * The block screen: a full-screen Compose view in a TYPE_APPLICATION_OVERLAY window, drawn by
 * the monitor service over everything. The window takes focus so the override phrase can be typed.
 */
class BlockOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val ui = mutableStateOf<BlockUi?>(null)
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var backCallback: OnBackInvokedCallback? = null

    /** The package whose block is on screen, or null. */
    val shownFor: String? get() = if (view != null) ui.value?.packageName else null

    @MainThread
    fun show(state: BlockUi) {
        ui.value = state
        if (view != null) return

        val lifecycleOwner = OverlayOwner().also { it.start() }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                BingeGuardTheme {
                    ui.value?.let { BlockScreen(it) }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // adjustResize alone no longer shrinks windows of apps targeting 35+ (edge-to-edge),
            // so ask to be kept clear of the keyboard too. BlockScreen also pads for it, in case
            // the window still overlaps the keyboard.
            fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
            title = "Binge Guard block screen"
        }
        windowManager.addView(compose, params)
        view = compose
        owner = lifecycleOwner

        // Back closes the block screen (you're already on the home screen behind it). With
        // predictive back, KEYCODE_BACK no longer reaches views, so register a callback.
        compose.post {
            val callback = OnBackInvokedCallback { ui.value?.onClose?.invoke() }
            compose.findOnBackInvokedDispatcher()
                ?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    @MainThread
    fun hide() {
        val v = view ?: return
        backCallback?.let { v.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        windowManager.removeView(v)
        owner?.stop()
        view = null
        owner = null
        ui.value = null
    }

    /** Gives the ComposeView the lifecycle and saved-state owners it would get from an activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun start() {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
