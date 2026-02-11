package com.example.screen_shot

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterShellArgs
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "screen_shot/overlay"
    private val reqOverlay = 2001
    private val reqMediaProjection = 2002
    private var pendingOverlayResult: MethodChannel.Result? = null
    private var pendingProjectionResult: MethodChannel.Result? = null
    private lateinit var projectionManager: MediaProjectionManager

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestOverlayPermission" -> handleOverlayPermission(result)
                    "requestMediaProjection" -> handleMediaProjectionPermission(result)
                    "startOverlayService" -> {
                        OverlayService.start(this)
                        result.success(true)
                    }
                    "setHighPerformanceMode" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        OverlayService.setHighPerformanceMode(this, enabled)
                        result.success(true)
                    }
                    "setBallColor" -> {
                        val color = call.argument<Number>("color")?.toInt()
                        if (color == null) {
                            result.error("INVALID_ARG", "color is required", null)
                        } else {
                            OverlayService.setBallColor(this, color)
                            result.success(true)
                        }
                    }
                    "stopOverlayService" -> {
                        OverlayService.stop(this)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun getFlutterShellArgs(): FlutterShellArgs {
        return FlutterShellArgs.fromIntent(intent).apply {
            // Force-disable Impeller on devices with Vulkan compatibility issues.
            remove(FlutterShellArgs.ARG_ENABLE_IMPELLER)
            add(FlutterShellArgs.ARG_DISABLE_IMPELLER)
            if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
                add(FlutterShellArgs.ARG_ENABLE_SOFTWARE_RENDERING)
            }
        }
    }

    private fun handleOverlayPermission(result: MethodChannel.Result) {
        if (Settings.canDrawOverlays(this)) {
            result.success(true)
            return
        }
        if (pendingOverlayResult != null) {
            result.error("PENDING", "Overlay permission request already pending.", null)
            return
        }
        pendingOverlayResult = result
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, reqOverlay)
    }

    private fun handleMediaProjectionPermission(result: MethodChannel.Result) {
        if (pendingProjectionResult != null) {
            result.error("PENDING", "MediaProjection request already pending.", null)
            return
        }
        pendingProjectionResult = result
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, reqMediaProjection)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqOverlay -> {
                val granted = Settings.canDrawOverlays(this)
                pendingOverlayResult?.success(granted)
                pendingOverlayResult = null
            }
            reqMediaProjection -> {
                val ok = resultCode == RESULT_OK && data != null
                if (ok) {
                    OverlayService.updateProjection(this, resultCode, data!!)
                }
                pendingProjectionResult?.success(ok)
                pendingProjectionResult = null
            }
        }
    }
}
