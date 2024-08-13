package org.lyaaz.fuckssdetection

import android.content.Intent
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "android") {
            return
        }

        val activityTaskManagerServiceClazz = runCatching {
            XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader
            )
        }.onFailure {
            XposedBridge.log(it)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                activityTaskManagerServiceClazz,
                "registerScreenCaptureObserver",
                IBinder::class.java,
                "android.app.IScreenCaptureObserver",
                RegisterScreenCaptureObserverHook
            )
        }.onFailure {
            XposedBridge.log(it)
        }
    }

    object RegisterScreenCaptureObserverHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            runCatching {
                val activityRecordClazz = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord",
                    param.thisObject.javaClass.classLoader
                )
                val ar = XposedHelpers.callStaticMethod(
                    activityRecordClazz,
                    "forTokenLocked",
                    arrayOf(IBinder::class.java),
                    param.args[0]
                )
                val arIntent = XposedHelpers.getObjectField(ar, "intent") as Intent
                XposedBridge.log("Prevent screenshot detection register from ${arIntent.component?.flattenToString()}")
            }.onFailure {
                XposedBridge.log("Prevent screenshot detection register but failed to retrieve component info.")
                XposedBridge.log(it)
            }
        }
    }
}
