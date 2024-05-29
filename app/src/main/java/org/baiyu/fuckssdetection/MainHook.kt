package org.baiyu.fuckssdetection

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
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
                XC_MethodReplacement.returnConstant(null)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
