package com.example.ar_jewelry_app

import android.util.DisplayMetrics
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Enregistre la vue AR native (étape B.1 : CameraX ; puis Filament + MediaPipe).
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "native-ar-view",
            NativeArViewFactory(this),
        )

        // Densité PHYSIQUE réelle de la dalle, pour la calibration à l'écran
        // (mesure du doigt posé sur le téléphone). Flutter n'expose que le
        // devicePixelRatio, dérivé de la densité *bucketée* d'Android
        // (ldpi/mdpi/hdpi/…) : elle peut s'écarter de plus de 10 % de la
        // réalité, ce qui suffirait à fausser d'une taille de bague entière.
        // xdpi/ydpi viennent, elles, des caractéristiques de la dalle.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "screenDpi" -> {
                        val m: DisplayMetrics = resources.displayMetrics
                        result.success(
                            mapOf(
                                "xdpi" to m.xdpi,
                                "ydpi" to m.ydpi,
                                "density" to m.density,
                            ),
                        )
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private companion object {
        const val DEVICE_CHANNEL = "ar_jewelry/device"
    }
}
