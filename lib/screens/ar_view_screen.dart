import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show kDebugMode;
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

  /// [notify] : ne PAS repasser par setState quand le teardown est déclenché
  /// depuis `dispose()`. À ce moment l'élément est déjà `defunct` (bien que
  /// `mounted` renvoie encore true), et un setState y lève l'assertion
  /// `_lifecycleState != defunct` → crash. Le lifecycle background garde
  /// notify=true pour ré-afficher le loader.
  Future<void> _teardown({bool notify = true}) async {
    // Capture refs synchronously before any await to prevent use-after-dispose.
    final CameraController? c = _cam;
    final ARService? ar = _ar;
    final StreamSubscription<AnchorResult?>? sub = _sub;
    _cam = null;
    _ar = null;
    _sub = null;
    if (notify && mounted) setState(() => _initialized = false);

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
    _teardown(notify: false); // pas de setState pendant l'unmount (defunct)
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
        // Cover plein écran : on agrandit l'aperçu pour remplir tout l'écran
        // (comme un appareil photo natif) sans déformer l'image, quitte à
        // rogner ce qui déborde. `_cam.value.aspectRatio` est le ratio
        // paysage du capteur ; à l'endroit (portrait) la largeur/hauteur
        // sont inversées → arCam = 1 / aspectRatio.
        final double arCam = 1 / _cam!.value.aspectRatio;
        final double arScreen = box.maxWidth / box.maxHeight;
        final double displayW, displayH;
        if (arCam < arScreen) {
          // Caméra plus étroite que l'écran → on cale sur la largeur.
          displayW = box.maxWidth;
          displayH = box.maxWidth / arCam;
        } else {
          // Caméra plus large → on cale sur la hauteur.
          displayH = box.maxHeight;
          displayW = box.maxHeight * arCam;
        }
        // Décalage du contenu caméra rogné vs. l'écran, pour aligner l'overlay.
        final double offsetX = (box.maxWidth - displayW) / 2;
        final double offsetY = (box.maxHeight - displayH) / 2;

        return Stack(
          fit: StackFit.expand,
          children: <Widget>[
            // Aperçu caméra plein écran (BoxFit.cover manuel via OverflowBox).
            ClipRect(
              child: OverflowBox(
                minWidth: displayW,
                maxWidth: displayW,
                minHeight: displayH,
                maxHeight: displayH,
                child: CameraPreview(_cam!),
              ),
            ),
            // Overlay bijou : le ModelViewer est monté UNE SEULE FOIS (passé
            // via `child`, jamais reconstruit par le builder) puis simplement
            // repositionné / masqué. Sans ça, chaque perte de détection le
            // détruisait et relançait une WebView + serveur local → le modèle
            // n'avait jamais le temps de se charger (et setState-après-dispose).
            ValueListenableBuilder<AnchorResult?>(
              valueListenable: _anchor,
              child: IgnorePointer(
                child: _JewelryGlbView(assetPath: widget.jewelry.assetPath),
              ),
              builder: (BuildContext _, AnchorResult? r, Widget? child) {
                final bool visible = r != null;
                // Le bijou occupe une fraction de la largeur d'affichage
                // (scale normalisé à la frame caméra).
                final double scale = r?.scale ?? 0.2;
                final double side = displayW * scale;
                final double cx =
                    offsetX + (r?.position.dx ?? 0.5) * displayW;
                final double cy =
                    offsetY + (r?.position.dy ?? 0.5) * displayH;
                // Détection perdue : on garde la WebView vivante mais hors
                // écran plutôt que de la retirer de l'arbre. Pas d'Opacity
                // ici : composer une vue native (WebView) sous une couche
                // d'opacité produit des artefacts de rendu sur Android.
                return Positioned(
                  left: visible ? cx - side / 2 : -9999,
                  top: visible ? cy - side / 2 : -9999,
                  width: side,
                  height: side,
                  child: Transform.rotate(
                    angle: r?.rotationRadians ?? 0.0,
                    child: child,
                  ),
                );
              },
            ),
            // Repère de calibration (builds debug uniquement) : point vert
            // sur le point d'ancrage détecté. Permet de vérifier le tracking
            // indépendamment du rendu 3D.
            if (kDebugMode)
              ValueListenableBuilder<AnchorResult?>(
                valueListenable: _anchor,
                builder: (BuildContext _, AnchorResult? r, Widget? __) {
                  if (r == null) return const SizedBox.shrink();
                  return Positioned(
                    left: offsetX + r.position.dx * displayW - 5,
                    top: offsetY + r.position.dy * displayH - 5,
                    child: IgnorePointer(
                      child: Container(
                        width: 10,
                        height: 10,
                        decoration: BoxDecoration(
                          color: Colors.greenAccent,
                          shape: BoxShape.circle,
                          border: Border.all(color: Colors.black, width: 1),
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
      disableTap: true,
      cameraControls: false,
      // Charge le modèle immédiatement, même monté hors écran (le widget
      // vit à -9999 tant qu'aucune détection n'est arrivée).
      loading: Loading.eager,
      reveal: Reveal.auto,
      interactionPrompt: InteractionPrompt.none,
      // Vue de face (par défaut model-viewer incline la caméra à 75°,
      // ce qui donne un bijou vu "de dessus" une fois superposé).
      cameraOrbit: '0deg 90deg 105%',
      backgroundColor: const Color(0x00000000),
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
