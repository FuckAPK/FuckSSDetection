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

        try {
            val activityTaskManagerServiceClazz = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                activityTaskManagerServiceClazz,
                "registerScreenCaptureObserver",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activityRecordClazz = XposedHelpers.findClass(
                            "com.android.server.wm.ActivityRecord",
                            lpparam.classLoader
                        )
                        val ar = XposedHelpers.callStaticMethod(
                            activityRecordClazz,
                            "forTokenLocked",
                            arrayOf(IBinder::class.java),
                            param.args[0]
                        )
                        val arIntent = XposedHelpers.getObjectField(ar, "intent") as Intent
                        XposedBridge.log("prevent screenshot detection register from ${arIntent.component?.flattenToString()}")
                        param.result = null
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
