import 'package:flutter/foundation.dart' show Factory;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/calibration_service.dart';
import 'calibration_screen.dart';

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

  /// Calibration chargée ? La vue native embarque les mesures (wristMm/…) dans
  /// sa CLÉ de recréation. Si on crée la vue AVANT que la calibration async
  /// arrive, ses valeurs changent une fraction de seconde plus tard → Flutter
  /// DÉTRUIT et recrée la vue → le dispose de la 1re instance faisait planter
  /// Filament (« l'app sort toute seule »). On attend donc la calibration avant
  /// de créer la vue : elle n'est alors construite qu'UNE fois, valeurs finales.
  bool _calibLoaded = false;

  // Debug : rend l'occluseur (cylindre bras/doigt) visible en rouge pour
  // vérifier sa présence, sa position et sa taille. Recrée la vue native.
  bool _debugOccluder = false;

  /// Affichage « lockstep » : la vue native dessine elle-même les frames
  /// ANALYSÉES, chacune accompagnée de la pose calculée pour elle, au lieu de
  /// laisser l'aperçu caméra courir en avance sur le suivi.
  ///
  /// Par défaut DÉSACTIVÉ : ce mode remplace la PreviewView par une vue
  /// dessinée à la main, or l'empilement des surfaces (caméra / SurfaceView
  /// Filament) est l'endroit le plus fragile de ce projet — plusieurs écrans
  /// noirs y ont déjà été payés. L'interrupteur permet de comparer les deux
  /// rendus sur device sans jamais perdre celui qui fonctionne.
  bool _lockstep = false;

  /// Fusion par flux optique : le déplacement réel des pixels entre deux
  /// frames sert de prédiction, la détection MediaPipe ne servant plus qu'à
  /// corriger lentement la dérive. C'est le pipeline des brevets WANNA.
  ///
  /// Désactivé par défaut, comme [_lockstep] : ce mode ajoute un calcul par
  /// frame sur le thread d'analyse, dont le coût réel ne peut se constater que
  /// sur device.
  bool _flow = false;

  /// Facteur morphologique passé au rendu natif (1.0 = non calibré).
  double _correction = 1.0;

  /// Mesures ABSOLUES de la calibration (mm), transmises telles quelles au
  /// natif : ce sont elles qui pilotent désormais la TAILLE du bijou (le
  /// facteur [_correction] ne servait qu'au repli anatomique). 0 = non mesuré.
  double _wristMm = 0.0;
  double _fingerMm = 0.0;

  bool get _isWrist => widget.anchor == 'wrist';

  /// Le collier n'a pas de calibration à l'écran : on ne peut pas poser son
  /// cou sur la dalle. Son échelle vient de la largeur d'épaules mesurée.
  bool get _isNeck => widget.anchor == 'neck';

  @override
  void initState() {
    super.initState();
    // Permission demandée dès maintenant : la caméra reviendra en B.2b.
    Permission.camera.request().then((PermissionStatus s) {
      if (mounted) setState(() => _granted = s.isGranted);
    });
    CalibrationService.load().then((Calibration c) {
      if (mounted) {
        setState(() {
          _applyCalibration(c);
          _calibLoaded = true;
        });
      }
    });
  }

  void _applyCalibration(Calibration c) {
    _correction = _correctionOf(c);
    _wristMm = c.wristMm ?? 0.0;
    _fingerMm = c.fingerMm ?? 0.0;
  }

  double _correctionOf(Calibration c) => switch (widget.anchor) {
        'neck' => 1.0,
        'wrist' => c.braceletCorrection,
        _ => c.ringCorrection,
      };

  Future<void> _openCalibration() async {
    await Navigator.of(context).push(
      MaterialPageRoute<double>(
        builder: (_) => CalibrationScreen(
          mode: _isWrist ? CalibrationMode.wrist : CalibrationMode.finger,
        ),
      ),
    );
    if (!mounted) return;
    // La vue native lit ses mesures à la création : il faut la recréer pour
    // que la nouvelle calibration prenne effet (cf. KeyedSubtree ci-dessous).
    setState(() => _applyCalibration(CalibrationService.current));
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
          if (!_isNeck)
            IconButton(
              icon: const Icon(Icons.straighten),
              tooltip: _isWrist ? 'Mesurer mon poignet' : 'Mesurer mon doigt',
              onPressed: _openCalibration,
            ),
          IconButton(
            icon: Icon(
              _debugOccluder ? Icons.visibility : Icons.visibility_off,
            ),
            tooltip: 'Occluseur visible (debug)',
            onPressed: () =>
                setState(() => _debugOccluder = !_debugOccluder),
          ),
          IconButton(
            icon: Icon(
              _lockstep ? Icons.link : Icons.link_off,
            ),
            tooltip: _lockstep
                ? 'Lockstep ACTIF — image et bijou sur la même frame'
                : 'Lockstep inactif — aperçu caméra en avance sur le suivi',
            onPressed: () => setState(() => _lockstep = !_lockstep),
          ),
          IconButton(
            icon: Icon(_flow ? Icons.waves : Icons.water),
            tooltip: _flow
                ? 'Flux optique ACTIF — mouvement mesuré sur les pixels'
                : 'Flux optique inactif — lissage temporel seul',
            onPressed: () => setState(() => _flow = !_flow),
          ),
        ],
      ),
      body: switch (_granted) {
        null => const Center(child: CircularProgressIndicator()),
        false => const Center(
            child: Text('Permission caméra refusée.',
                style: TextStyle(color: Colors.white)),
          ),
        // On attend AUSSI la calibration : créer la vue avant qu'elle arrive
        // provoquait une recréation-dispose immédiate (crash Filament).
        true when !_calibLoaded =>
          const Center(child: CircularProgressIndicator()),
        // La clé force la recréation de la vue native quand le debug ou la
        // calibration change (les deux sont lus à la création de la vue).
        true => KeyedSubtree(
            key: ValueKey<String>(
              '$_debugOccluder/$_correction/$_wristMm/$_fingerMm/'
              '$_lockstep/$_flow',
            ),
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
      'scaleCorrection': _correction,
      // Mesures absolues (mm) : pilotent la taille du bijou côté natif.
      'wristMm': _wristMm,
      'fingerMm': _fingerMm,
      'lockstep': _lockstep,
      'flow': _flow,
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
