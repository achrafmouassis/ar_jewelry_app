import 'package:flutter/foundation.dart' show Factory;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

/// Écran de test de la vue AR NATIVE.
///
/// Étape B.2a : la PlatformView `native-ar-view` affiche le bijou rendu par
/// **Filament natif** (glTF + IBL). Rendue en **Hybrid Composition** (vue
/// native composée directement par le système) : indispensable pour un rendu
/// OpenGL direct comme Filament — sinon écran noir. La caméra reviendra en
/// B.2b dans la même vue, puis MediaPipe (B.3) et l'occlusion (B.4).
class NativeArTestScreen extends StatefulWidget {
  /// Chemin de l'asset Flutter du bijou (ex : `assets/jewelry/bagues/xxx.glb`).
  final String assetPath;

  /// Point d'ancrage natif : `wrist` (bracelet) ou `ringFinger` (bague).
  final String anchor;

  const NativeArTestScreen({
    super.key,
    required this.assetPath,
    this.anchor = 'ringFinger',
  });

  @override
  State<NativeArTestScreen> createState() => _NativeArTestScreenState();
}

class _NativeArTestScreenState extends State<NativeArTestScreen> {
  static const String _viewType = 'native-ar-view';
  bool? _granted;

  // Debug : rend l'occluseur (cylindre bras/doigt) visible en rouge pour
  // vérifier sa présence, sa position et sa taille. Recrée la vue native.
  bool _debugOccluder = false;

  @override
  void initState() {
    super.initState();
    // Permission demandée dès maintenant : la caméra reviendra en B.2b.
    Permission.camera.request().then((PermissionStatus s) {
      if (mounted) setState(() => _granted = s.isGranted);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('AR natif — B.4 (occlusion)'),
        backgroundColor: Colors.black87,
        foregroundColor: Colors.white,
        actions: <Widget>[
          IconButton(
            icon: Icon(
              _debugOccluder ? Icons.visibility : Icons.visibility_off,
            ),
            tooltip: 'Occluseur visible (debug)',
            onPressed: () =>
                setState(() => _debugOccluder = !_debugOccluder),
          ),
        ],
      ),
      body: switch (_granted) {
        null => const Center(child: CircularProgressIndicator()),
        false => const Center(
            child: Text('Permission caméra refusée.',
                style: TextStyle(color: Colors.white)),
          ),
        // La clé force la recréation de la vue native quand le debug change.
        true => KeyedSubtree(
            key: ValueKey<bool>(_debugOccluder),
            child: _buildHybridView(),
          ),
      },
    );
  }

  /// Hybrid Composition : la vue native (SurfaceView Filament) est insérée
  /// dans la hiérarchie et composée par le système → le rendu GL direct
  /// s'affiche réellement (un simple `AndroidView` donnerait un écran noir).
  Widget _buildHybridView() {
    final Map<String, dynamic> creationParams = <String, dynamic>{
      'assetPath': widget.assetPath,
      'anchor': widget.anchor,
      'debugOccluder': _debugOccluder,
    };
    return PlatformViewLink(
      viewType: _viewType,
      surfaceFactory: (BuildContext context, PlatformViewController controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers:
              const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        final AndroidViewController controller =
            PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        );
        controller
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
        return controller;
      },
    );
  }
}
