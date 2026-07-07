import 'dart:async';
import 'dart:math' as math;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:webview_flutter/webview_flutter.dart' show WebViewController;

import '../config/tracking_config.dart';
import '../main.dart' show cameras;
import '../models/jewelry_model.dart';
import '../services/ar_service.dart';
import '../services/jewelry_service.dart';
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

  // WebView du ModelViewer : permet de piloter l'orbite caméra 3D en direct
  // pour incliner le bijou avec la main (rendu "vrai 3D" au lieu d'une
  // vignette plate). Rempli via onWebViewCreated.
  WebViewController? _mvController;
  double _lastTiltDeg = 0;
  double _lastExposure = 1.2;

  // Chemin du GLB affiché en AR : variante "moitié avant" si disponible
  // (la moitié arrière serait cachée par le doigt/poignet dans la réalité).
  late String _arAssetPath = widget.jewelry.assetPath;

  String? _error;
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _anchor.addListener(_pushTiltToModelViewer);
    _bootstrap();
  }

  /// Synchronise le rendu 3D avec la scène réelle :
  /// - inclinaison : remonte/abaisse la caméra 3D (angle polaire) selon le
  ///   tilt hors-plan détecté de la main ;
  /// - exposition : calée sur la luminosité ambiante mesurée sur le flux
  ///   caméra, pour que le bijou ne brille pas "studio" dans le noir.
  /// Throttlé (2° / 0,08 d'exposition) pour ne pas spammer la WebView ;
  /// interpolation-decay côté model-viewer lisse les transitions d'orbite.
  void _pushTiltToModelViewer() {
    final AnchorResult? r = _anchor.value;
    final WebViewController? mv = _mvController;
    if (r == null || mv == null) return;
    // Clamp serré : au-delà de ~30° on verrait "à travers" l'anneau, et
    // l'absence d'occlusion du doigt casserait l'illusion.
    final double deg =
        (r.tiltRadians * 180 / math.pi).clamp(-30.0, 30.0);
    // Luma 0,5 (scène moyenne) → exposition 1,2 (valeur de base) ;
    // pièce sombre → ~0,7, plein soleil → ~1,7.
    final double exposure =
        (0.5 + (_ar?.ambientLuma ?? 0.5) * 1.4).clamp(0.6, 1.7);
    final bool tiltChanged = (deg - _lastTiltDeg).abs() >= 2.0;
    final bool expoChanged = (exposure - _lastExposure).abs() >= 0.08;
    if (!tiltChanged && !expoChanged) return;
    _lastTiltDeg = deg;
    _lastExposure = exposure;
    final double phi = 90.0 - deg;
    mv.runJavaScript(
      "var mv=document.getElementById('jewelry-viewer');"
      "if(mv){mv.setAttribute('camera-orbit','0deg ${phi.toStringAsFixed(1)}deg 105%');"
      "mv.setAttribute('exposure','${exposure.toStringAsFixed(2)}');}",
    );
  }

  /// Pipeline complet d'initialisation : permission → caméra → AR service.
  Future<void> _bootstrap() async {
    // Évite une double init si on rentre ici alors qu'une caméra tourne déjà
    // (ex : resume appelé alors que le teardown n'a pas eu lieu).
    if (_cam != null) return;
    try {
      // 0) Variante AR du modèle (moitié avant seule) si elle est bundlée.
      _arAssetPath =
          await JewelryService.instance.arAssetFor(widget.jewelry.assetPath);

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
    _anchor.removeListener(_pushTiltToModelViewer);
    _anchor.dispose();
    _mvController = null;
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
                child: _JewelryGlbView(
                  assetPath: _arAssetPath,
                  orientation: TrackingConfig.orientationFor(
                      widget.jewelry.type.folder),
                  onWebViewCreated: (WebViewController c) =>
                      _mvController = c,
                ),
              ),
              builder: (BuildContext _, AnchorResult? r, Widget? child) {
                final bool visible = r != null;
                // La WebView garde une taille FIXE (retailler une platform
                // view à chaque frame déclenche un relayout natif →enfer de
                // perfs). La taille apparente du bijou est appliquée par
                // Transform.scale, une simple transformation GPU de texture.
                final double baseSide = displayW * 0.6;
                // Le bijou occupe une fraction de la largeur d'affichage
                // (scale normalisé à la frame caméra).
                final double side = displayW * (r?.scale ?? 0.2);
                final double cx =
                    offsetX + (r?.position.dx ?? 0.5) * displayW;
                final double cy =
                    offsetY + (r?.position.dy ?? 0.5) * displayH;
                // Détection perdue : on garde la WebView vivante mais hors
                // écran plutôt que de la retirer de l'arbre. Pas d'Opacity
                // ici : composer une vue native (WebView) sous une couche
                // d'opacité produit des artefacts de rendu sur Android.
                return Positioned(
                  left: visible ? cx - baseSide / 2 : -9999,
                  top: visible ? cy - baseSide / 2 : -9999,
                  width: baseSide,
                  height: baseSide,
                  child: Transform.rotate(
                    angle: r?.rotationRadians ?? 0.0,
                    child: Transform.scale(
                      scale: side / baseSide,
                      child: child,
                    ),
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
  final String? orientation;
  final ValueChanged<WebViewController>? onWebViewCreated;
  const _JewelryGlbView({
    required this.assetPath,
    this.orientation,
    this.onWebViewCreated,
  });

  @override
  Widget build(BuildContext context) {
    return ModelViewer(
      key: ValueKey<String>(assetPath),
      id: 'jewelry-viewer',
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
      // Cible fixe à l'origine du modèle : le camera-target auto vise le
      // centre de la bbox, qui est décalé sur les variantes AR coupées
      // (moitié arrière supprimée) → le bijou glisserait hors de l'ancre.
      cameraTarget: '0m 0m 0m',
      // Ré-oriente le modèle en pose "porté" (voir TrackingConfig).
      orientation: orientation,
      // FOV serré (télé) : à cette taille apparente, un bijou réel est vu
      // à distance de bras — un grand-angle le déformerait (effet macro).
      fieldOfView: '22deg',
      // Lisse les changements d'orbite pilotés par le tilt de la main.
      interpolationDecay: 90,
      // Éclairage studio uniforme : indispensable pour que les matériaux
      // PBR métalliques aient des reflets au lieu d'un rendu gris plat.
      environmentImage: 'neutral',
      exposure: 1.2,
      // `tone-mapping` n'est pas exposé par l'API Dart du package : on
      // l'injecte en JS. "neutral" = Khronos PBR Neutral, conçu pour
      // l'e-commerce — il préserve la teinte réelle de l'or/argent là où
      // le tone mapping par défaut (ACES) désature et assombrit les métaux.
      relatedJs: "var mv=document.getElementById('jewelry-viewer');"
          "if(mv){mv.setAttribute('tone-mapping','neutral');}",
      backgroundColor: const Color(0x00000000),
      relatedCss: 'model-viewer { background-color: transparent; }',
      onWebViewCreated: onWebViewCreated,
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
