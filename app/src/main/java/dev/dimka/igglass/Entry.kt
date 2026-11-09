package dev.dimka.igglass

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.IdentityHashMap

/** Only verified Instagram builds' main processes are modified. */
class Entry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var modulePath: String

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE || lpparam.processName != PACKAGE) return
        runCatching {
            XposedBridge.hookMethod(
                Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java),
                object : XC_MethodHook() {
                    private var initialized = false

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (initialized) return
                        initialized = true
                        runCatching { initialize(param.args[0] as Application) }
                            .onFailure { log("initialization skipped: ${it.stackTraceToString()}") }
                    }
                },
            )
        }.onFailure { log("application hook unavailable: $it") }
    }

    private fun initialize(app: Application) {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(PACKAGE, 0)
        if (info.longVersionCode !in VERSION_CODES || info.versionName != VERSION_NAME) {
            log("unsupported Instagram ${info.versionName} (${info.longVersionCode}); expected $VERSION_NAME ${VERSION_CODES.joinToString()}, leaving UI unchanged")
            return
        }
        hookModuleResources()
        DynamicColors.bind(app.resources)
        val sessions = IdentityHashMap<Activity, InstagramHooks>()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun attach(activity: Activity) {
                if (activity.javaClass.name != MAIN_ACTIVITY || sessions.containsKey(activity)) return
                runCatching { InstagramHooks(activity).also { sessions[activity] = it; it.start() } }
                    .onFailure { log("activity hook skipped: ${it.stackTraceToString()}") }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = attach(activity)
            override fun onActivityResumed(activity: Activity) = attach(activity)
            override fun onActivityDestroyed(activity: Activity) {
                runCatching { sessions.remove(activity)?.close() }
                    .onFailure { log("activity cleanup: $it") }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        log("ready for Instagram ${info.versionName} (${info.longVersionCode})")
    }

    /** Compose accessibility strings must resolve from our reserved resource package. */
    private fun hookModuleResources() {
        fun load(): Resources {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, modulePath)
            val system = Resources.getSystem()
            @Suppress("DEPRECATION")
            return Resources(assets, system.displayMetrics, system.configuration)
        }
        var moduleRes: Resources? = null
        XposedBridge.hookMethod(
            Resources::class.java.getMethod("getText", Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val id = param.args[0] as Int
                    if (id ushr 24 != 0x6e || param.thisObject === moduleRes) return
                    param.result = runCatching { (moduleRes ?: load().also { moduleRes = it }).getText(id) }
                        .recoverCatching { load().also { moduleRes = it }.getText(id) }
                        .getOrElse { log("module string 0x${id.toString(16)} unavailable: $it"); "" }
                }
            },
        )
    }

    companion object {
        const val PACKAGE = "com.instagram.android"
        const val VERSION_NAME = "454.0.0.54.77"
        val VERSION_CODES = setOf(386014705L, 386014758L)
        const val MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity"
        fun log(message: String) {
            Log.i("IgGlass", message)
            XposedBridge.log("[IgGlass] $message")
        }
    }
}
