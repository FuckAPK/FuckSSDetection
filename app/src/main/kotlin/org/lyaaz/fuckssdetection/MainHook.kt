package org.lyaaz.fuckssdetection

import android.app.AndroidAppHelper
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import kotlin.concurrent.thread

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android") {
            hookAndroidSystem(lpparam)
        }
        
        if (lpparam.packageName != "android") {
            hookAppProcesses(lpparam)
        }
    }

    private fun hookAndroidSystem(lpparam: LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader,
                "registerScreenCaptureObserver",
                IBinder::class.java,
                "android.app.IScreenCaptureObserver",
                RegisterScreenCaptureObserverHook
            )
        }.onFailure {
            XposedBridge.log(it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.ScreenRecordingCallbackController",
                    lpparam.classLoader,
                    "register",
                    "android.window.IScreenRecordingCallback",
                    RegisterScreenRecordingHook
                )
            }.onFailure {
                XposedBridge.log(it)
            }
        }
    }

    private fun hookAppProcesses(lpparam: LoadPackageParam) {
        // Hook FileObserver
        runCatching {
            val fileObserverClass = XposedHelpers.findClass("android.os.FileObserver", lpparam.classLoader)
            XposedBridge.hookAllConstructors(fileObserverClass, FileObserverConstructorHook)
        }.onFailure {
            XposedBridge.log("FuckSSDetection: Failed to hook FileObserver in ${lpparam.packageName}")
            XposedBridge.log(it)
        }

        // Hook ContentObserver
        runCatching {
            val contentObserverClass = XposedHelpers.findClass("android.database.ContentObserver", lpparam.classLoader)
            XposedBridge.hookAllMethods(contentObserverClass, "dispatchChange", ContentObserverDispatchChangeHook)
        }.onFailure {
            XposedBridge.log("FuckSSDetection: Failed to hook ContentObserver in ${lpparam.packageName}")
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

    object RegisterScreenRecordingHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            XposedBridge.log("Prevent screen recording detection from uid: ${Binder.getCallingUid()}")
            param.result = false
        }
    }

    object FileObserverConstructorHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.isEmpty()) return
            
            val arg = param.args[0]
            if (arg is String) {
                if (isScreenshotPath(arg)) {
                    param.args[0] = "/dev/null"
                }
            } else if (arg is File) {
                if (isScreenshotPath(arg.absolutePath)) {
                    param.args[0] = File("/dev/null")
                }
            } else if (arg is List<*>) {
                val newPaths = mutableListOf<File>()
                for (item in arg) {
                    if (item is File) {
                        if (isScreenshotPath(item.absolutePath)) {
                            newPaths.add(File("/dev/null"))
                        } else {
                            newPaths.add(item)
                        }
                    }
                }
                param.args[0] = newPaths
            }
        }

        private fun isScreenshotPath(path: String): Boolean {
            val lowerPath = path.lowercase()
            return lowerPath.contains("screenshot") || lowerPath.contains("screenrecord") || lowerPath.contains("screen_record")
        }
    }

    object ContentObserverDispatchChangeHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            var isMedia = false
            var targetUri: android.net.Uri? = null
            
            for (arg in param.args) {
                if (arg is android.net.Uri) {
                    if (arg.toString().lowercase().contains("media")) {
                        isMedia = true
                        targetUri = arg
                        break
                    }
                } else if (arg is Collection<*>) {
                    for (item in arg) {
                        if (item is android.net.Uri && item.toString().lowercase().contains("media")) {
                            isMedia = true
                            targetUri = item
                            break
                        }
                    }
                }
                if (isMedia) break
            }

            if (!isMedia || targetUri == null) return

            val context = AndroidAppHelper.currentApplication() ?: return
            
            var isScreenshot = false
            try {
                context.contentResolver.query(targetUri, arrayOf("_data"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (path != null) {
                            val lowerPath = path.lowercase()
                            if (lowerPath.contains("screenshot") || lowerPath.contains("screenrecord") || lowerPath.contains("screen_record")) {
                                isScreenshot = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore query exceptions
            }

            if (isScreenshot) {
                // Cancel execution entirely to evade detection
                param.result = null
            }
        }
    }
}
