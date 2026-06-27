import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';
import 'package:permission_handler/permission_handler.dart';

import '../config/tracking_config.dart';
import '../main.dart' show cameras;
import '../models/jewelry_model.dart';
import '../services/ar_service.dart';
import '../widgets/fps_counter.dart';
import '../widgets/loading_indicator.dart';

/// Écran 3 : caméra temps réel + superposition du bijou 3D suivant
/// la détection MediaPipe / ML Kit.
class ARViewScreen extends StatefulWidget {
  final JewelryModel jewelry;
  const ARViewScreen({super.key, required this.jewelry});

  @override
  State<ARViewScreen> createState() => _ARViewScreenState();
}

class _ARViewScreenState extends State<ARViewScreen>
    with WidgetsBindingObserver {
  CameraController? _cam;
  ARService? _ar;
  StreamSubscription<AnchorResult?>? _sub;

  // Notifier dédié à l'overlay : seul ce widget rebuild à chaque détection,
  // pas tout l'écran (perf critique).
  final ValueNotifier<AnchorResult?> _anchor =
      ValueNotifier<AnchorResult?>(null);

  String? _error;
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
  }

  /// Pipeline complet d'initialisation : permission → caméra → AR service.
  Future<void> _bootstrap() async {
    // Évite une double init si on rentre ici alors qu'une caméra tourne déjà
    // (ex : resume appelé alors que le teardown n'a pas eu lieu).
    if (_cam != null) return;
    try {
      // 1) Permission caméra
      final PermissionStatus status = await Permission.camera.request();
      if (!status.isGranted) {
        setState(() => _error = 'Permission caméra refusée.');
        return;
      }

      // 2) Sélection de la caméra (frontale/arrière) selon le type
      final TrackingTarget target =
          TrackingConfig.targetFor(widget.jewelry.type.folder);
      final CameraLensDirection lens =
          TrackingConfig.lensFor(widget.jewelry.type.folder);
      final CameraDescription cam = cameras.firstWhere(
        (CameraDescription c) => c.lensDirection == lens,
        orElse: () => cameras.first,
      );

      // 3) Controller en 720p (équilibre perf/qualité). Le format d'image
      //    dépend de la cible :
      //    - Visage (ML Kit) → NV21 : c'est le seul format accepté par
      //      InputImage.fromBytes sur Android (1 seul plan, pas de
      //      concaténation manuelle hasardeuse).
      //    - Main (MediaPipe) → YUV420 : attendu par le plugin hand_landmarker.
      final ImageFormatGroup fmt = target == TrackingTarget.face
          ? ImageFormatGroup.nv21
          : ImageFormatGroup.yuv420;
      final CameraController controller = CameraController(
        cam,
        ResolutionPreset.medium, // ≈ 720p sur la plupart des devices
        enableAudio: false,
        imageFormatGroup: fmt,
      );
      await controller.initialize();

      // 4) Service AR (MediaPipe ou ML Kit selon la cible). On lui indique
      //    si l'image doit être miroir (caméra frontale) pour aligner
      //    l'overlay avec ce que voit l'utilisateur.
      final ARService ar = ARService(
        target: target,
        anchor: TrackingConfig.anchorFor(widget.jewelry.type.folder),
        mirror: lens == CameraLensDirection.front,
      );
      await ar.init();

      // 5) Branchement : flux caméra → AR service → ValueNotifier
      _sub = ar.results.listen((AnchorResult? r) => _anchor.value = r);
      await controller.startImageStream((CameraImage img) {
        ar.processFrame(img, cam);
      });

      if (!mounted) {
        await controller.dispose();
        await ar.dispose();
        return;
      }

      setState(() {
        _cam = controller;
        _ar = ar;
        _initialized = true;
      });
    } catch (e) {
      if (mounted) setState(() => _error = 'Erreur d\'initialisation : $e');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Libère la caméra quand l'app passe en arrière-plan, ré-init au retour.
    //
    // ⚠️ On NE filtre PAS sur `_cam == null` ici : au retour de
    // l'arrière-plan, `_teardown()` a déjà mis `_cam` à null, et un guard
    // précoce empêcherait `_bootstrap()` de relancer la caméra (écran noir).
    switch (state) {
      case AppLifecycleState.inactive:
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
        if (_cam != null) _teardown();
        break;
      case AppLifecycleState.resumed:
        if (_cam == null && _error == null) _bootstrap();
        break;
      case AppLifecycleState.detached:
        break;
    }
  }

  Future<void> _teardown() async {
    // Capture refs synchronously before any await to prevent use-after-dispose.
    final CameraController? c = _cam;
    final ARService? ar = _ar;
    final StreamSubscription<AnchorResult?>? sub = _sub;
    _cam = null;
    _ar = null;
    _sub = null;
    if (mounted) setState(() => _initialized = false);

    await sub?.cancel();
    if (c != null) {
      try {
        if (c.value.isStreamingImages) await c.stopImageStream();
      } catch (_) {}
      await c.dispose();
    }
    await ar?.dispose();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _teardown();
    _anchor.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Stack(
          fit: StackFit.expand,
          children: <Widget>[
            _buildContent(),
            // UI overlay : bouton retour + FPS
            Positioned(
              top: 12,
              left: 12,
              child: Row(
                children: <Widget>[
                  _RoundIconButton(
                    icon: Icons.arrow_back,
                    onTap: () => Navigator.of(context).maybePop(),
                  ),
                  const SizedBox(width: 10),
                  const FpsCounter(),
                ],
              ),
            ),
            // Légende discrète du bijou affiché
            Positioned(
              bottom: 16,
              left: 16,
              right: 16,
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.45),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  widget.jewelry.name,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildContent() {
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            _error!,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white),
          ),
        ),
      );
    }
    if (!_initialized || _cam == null) {
      return const CenteredLoader(label: 'Préparation de la caméra…');
    }

    // Aperçu caméra + overlay 3D positionné par ValueListenableBuilder.
    return LayoutBuilder(
      builder: (BuildContext _, BoxConstraints box) {
        return Stack(
          fit: StackFit.expand,
          children: <Widget>[
            // Centrage + ratio caméra pour éviter les déformations.
            Center(
              child: AspectRatio(
                aspectRatio: _cam!.value.aspectRatio,
                child: CameraPreview(_cam!),
              ),
            ),
            ValueListenableBuilder<AnchorResult?>(
              valueListenable: _anchor,
              builder: (BuildContext _, AnchorResult? r, __) {
                if (r == null) {
                  return const SizedBox.shrink();
                }
                // Le bijou occupe une fraction de la dimension la plus
                // petite (scale est normalisé à la frame caméra).
                final double side =
                    box.maxWidth.clamp(0, double.infinity) * r.scale;
                final double left =
                    r.position.dx * box.maxWidth - side / 2;
                final double top =
                    r.position.dy * box.maxHeight - side / 2;
                return Positioned(
                  left: left,
                  top: top,
                  width: side,
                  height: side,
                  child: IgnorePointer(
                    child: Transform.rotate(
                      angle: r.rotationRadians,
                      child: _JewelryGlbView(
                        assetPath: widget.jewelry.assetPath,
                      ),
                    ),
                  ),
                );
              },
            ),
          ],
        );
      },
    );
  }
}

/// Affichage d'un .glb via model_viewer_plus, fond transparent.
/// Mémoïsé via un widget dédié pour éviter de recréer la WebView
/// à chaque update du Positioned parent.
class _JewelryGlbView extends StatelessWidget {
  final String assetPath;
  const _JewelryGlbView({required this.assetPath});

  @override
  Widget build(BuildContext context) {
    return ModelViewer(
      key: ValueKey<String>(assetPath),
      src: assetPath, // chemin asset Flutter, model_viewer_plus l'expose en local
      alt: 'Bijou 3D',
      ar: false,
      autoRotate: false,
      disableZoom: true,
      cameraControls: false,
      backgroundColor: const Color(0x00000000),
      // 'reveal: manual' évite l'animation d'apparition à chaque rebuild.
      relatedCss: 'model-viewer { background-color: transparent; }',
    );
  }
}

class _RoundIconButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  const _RoundIconButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.45),
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Icon(icon, color: Colors.white, size: 20),
        ),
      ),
    );
  }
}
