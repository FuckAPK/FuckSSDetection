package org.lyaaz.fuckssdetection

import android.app.AndroidAppHelper
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "FuckSSDetection"
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        hookAndroidSystem(param.classLoader)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        hookAppProcesses(param)
    }

    private fun hookAndroidSystem(classLoader: ClassLoader) {
        runCatching {
            val atmsClass = Class.forName(
                "com.android.server.wm.ActivityTaskManagerService",
                false, classLoader
            )
            val observerClass = Class.forName(
                "android.app.IScreenCaptureObserver",
                false, classLoader
            )
            val method = atmsClass.getDeclaredMethod(
                "registerScreenCaptureObserver",
                IBinder::class.java,
                observerClass
            )
            hook(method).intercept { chain ->
                runCatching {
                    val activityRecordClazz = Class.forName(
                        "com.android.server.wm.ActivityRecord",
                        false,
                        chain.thisObject!!.javaClass.classLoader!!
                    )
                    val forTokenLockedMethod = activityRecordClazz
                        .getDeclaredMethod("forTokenLocked", IBinder::class.java)
                        .apply { isAccessible = true }
                    val ar = forTokenLockedMethod.invoke(null, chain.args[0])
                    val intentField = activityRecordClazz
                        .getDeclaredField("intent")
                        .apply { isAccessible = true }
                    val arIntent = intentField.get(ar) as Intent
                    log(Log.INFO, TAG, "Prevent screenshot detection register from ${arIntent.component?.flattenToString()}")
                }.onFailure {
                    log(Log.INFO, TAG, "Prevent screenshot detection register but failed to retrieve component info.")
                    log(Log.ERROR, TAG, "error", it)
                }
                null
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to hook registerScreenCaptureObserver", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                val srccClass = Class.forName(
                    "com.android.server.wm.ScreenRecordingCallbackController",
                    false, classLoader
                )
                val callbackClass = Class.forName(
                    "android.window.IScreenRecordingCallback",
                    false, classLoader
                )
                val method = srccClass.getDeclaredMethod("register", callbackClass)
                hook(method).intercept { chain ->
                    log(Log.INFO, TAG, "Prevent screen recording detection from uid: ${Binder.getCallingUid()}")
                    false
                }
            }.onFailure {
                log(Log.ERROR, TAG, "Failed to hook ScreenRecordingCallbackController.register", it)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hookAppProcesses(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        // Hook FileObserver
        runCatching {
            val fileObserverClass = Class.forName("android.os.FileObserver", false, classLoader)
            for (constructor in fileObserverClass.declaredConstructors) {
                hook(constructor).intercept { chain ->
                    if (chain.args.isNotEmpty()) {
                        val arg = chain.args[0]
                        val newArgs = chain.args.toTypedArray()
                        when {
                            arg is String && isScreenshotPath(arg) -> {
                                newArgs[0] = "/dev/null"
                                chain.proceed(newArgs)
                                return@intercept null
                            }
                            arg is File && isScreenshotPath(arg.absolutePath) -> {
                                newArgs[0] = File("/dev/null")
                                chain.proceed(newArgs)
                                return@intercept null
                            }
                            arg is List<*> -> {
                                val newPaths = arg.map { item ->
                                    if (item is File && isScreenshotPath(item.absolutePath)) {
                                        File("/dev/null")
                                    } else {
                                        item
                                    }
                                }
                                newArgs[0] = newPaths
                                chain.proceed(newArgs)
                                return@intercept null
                            }
                        }
                    }
                    chain.proceed()
                    null
                }
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to hook FileObserver in ${param.packageName}", it)
        }

        // Hook ContentObserver
        runCatching {
            val contentObserverClass =
                Class.forName("android.database.ContentObserver", false, classLoader)
            for (method in contentObserverClass.declaredMethods.filter { it.name == "dispatchChange" }) {
                hook(method).intercept { chain ->
                    var isMedia = false
                    var targetUri: android.net.Uri? = null

                    for (arg in chain.args) {
                        if (arg is android.net.Uri) {
                            if (arg.toString().lowercase().contains("media")) {
                                isMedia = true
                                targetUri = arg
                                break
                            }
                        } else if (arg is Collection<*>) {
                            for (item in arg) {
                                if (item is android.net.Uri && item.toString().lowercase()
                                        .contains("media")
                                ) {
                                    isMedia = true
                                    targetUri = item
                                    break
                                }
                            }
                        }
                        if (isMedia) break
                    }

                    if (!isMedia || targetUri == null) {
                        chain.proceed()
                        return@intercept null
                    }

                    val context = AndroidAppHelper.currentApplication()
                    if (context == null) {
                        chain.proceed()
                        return@intercept null
                    }

                    var isScreenshot = false
                    try {
                        context.contentResolver.query(
                            targetUri, arrayOf("_data"), null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val path = cursor.getString(0)
                                if (path != null) {
                                    val lowerPath = path.lowercase()
                                    if (lowerPath.contains("screenshot") ||
                                        lowerPath.contains("screenrecord") ||
                                        lowerPath.contains("screen_record")
                                    ) {
                                        isScreenshot = true
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore query exceptions
                    }

                    if (!isScreenshot) {
                        chain.proceed()
                    }
                    null
                }
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to hook ContentObserver in ${param.packageName}", it)
        }
    }

    private fun isScreenshotPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        return lowerPath.contains("screenshot") ||
                lowerPath.contains("screenrecord") ||
                lowerPath.contains("screen_record")
    }
}
