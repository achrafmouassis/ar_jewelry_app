package com.example.ar_jewelry_app

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Fabrique de la [NativeArView], enregistrée sous le type "native-ar-view"
 * dans MainActivity. L'activity est nécessaire comme LifecycleOwner pour
 * lier CameraX (et plus tard le Choreographer de Filament).
 */
class NativeArViewFactory(
    private val activity: Activity,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return NativeArView(context, activity, viewId, args)
    }
}
