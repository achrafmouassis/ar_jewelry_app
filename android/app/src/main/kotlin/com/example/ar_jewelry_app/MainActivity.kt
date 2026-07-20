package com.example.ar_jewelry_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Enregistre la vue AR native (étape B.1 : CameraX ; puis Filament + MediaPipe).
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "native-ar-view",
            NativeArViewFactory(this),
        )
    }
}
