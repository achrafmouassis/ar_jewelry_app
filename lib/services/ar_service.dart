import 'dart:async';
import 'dart:ui' show Offset, Size;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:google_mlkit_face_mesh_detection/google_mlkit_face_mesh_detection.dart';
import 'package:hand_landmarker/hand_landmarker.dart';

import '../config/tracking_config.dart';
import 'anchor_math.dart';
import 'one_euro_filter.dart';

/// Résultat d'une détection : position du point d'ancrage en coordonnées
/// normalisées [0..1] de la frame caméra **redressée** (origine en haut-gauche,
/// déjà miroir si caméra frontale), plus un facteur d'échelle suggéré pour le
/// bijou (proportionnel à la "taille" du visage / de la main détectée).
@immutable
class AnchorResult {
  final Offset position; // 0..1, 0..1
  final double scale;    // 0..1 (proportion de la dimension caméra)
  final double rotationRadians;

  const AnchorResult({
    required this.position,
    required this.scale,
    this.rotationRadians = 0.0,
  });
}

/// Service AR : encapsule MediaPipe Hand Landmarker (main, 21 pts) et
/// **MediaPipe FaceMesh via ML Kit** (visage, 468 pts), et expose un flux
/// unifié [AnchorResult].
///
/// Points perf :
/// - MediaPipe main en delegate GPU, asynchrone : on pousse les frames via
///   `processFrame`, les résultats arrivent par `landmarkStream`.
/// - FaceMesh est appelé manuellement avec un garde `_busy` (throttle) pour
///   ne pas accumuler de frames en queue.
/// - Les positions sont lissées par un filtre One-Euro ([AnchorSmoother]) pour
///   éliminer le tremblement frame-à-frame.
/// - `dispose()` obligatoire à la fermeture de l'écran.
class ARService {
  final TrackingTarget target;
  final AnchorPoint anchor;

  /// True si l'image affichée est en miroir (caméra frontale) : on retourne
  /// alors l'axe X pour que le bijou colle à ce que voit l'utilisateur.
  final bool mirror;

  HandLandmarkerPlugin? _hand;
  FaceMeshDetector? _faceMesh;
  StreamSubscription<List<Hand>>? _handSub;

  bool _busy = false; // throttle pour le pipeline visage
  bool _disposed = false;

  // Lissage des sorties (anti-jitter).
  final AnchorSmoother _smoother = AnchorSmoother();

  final StreamController<AnchorResult?> _controller =
      StreamController<AnchorResult?>.broadcast();

  /// Flux unifié consommé par la vue AR.
  Stream<AnchorResult?> get results => _controller.stream;

  ARService({
    required this.target,
    required this.anchor,
    this.mirror = false,
  });

  /// Initialise le détecteur adapté à la cible. À appeler une fois.
  Future<void> init() async {
    if (target == TrackingTarget.hand) {
      // MediaPipe Hand Landmarker — delegate GPU pour la perf.
      _hand = HandLandmarkerPlugin.create(
        numHands: 1,
        minHandDetectionConfidence: 0.5,
        delegate: HandLandmarkerDelegate.gpu,
      );
      _handSub = _hand!.landmarkStream.listen(_onHands);
    } else {
      // ML Kit FaceMesh — 468 landmarks (topologie canonique MediaPipe).
      _faceMesh = FaceMeshDetector(option: FaceMeshDetectorOptions.faceMesh);
    }
  }

  /// Traite une frame caméra.
  /// - Main : fire-and-forget vers MediaPipe ; résultats via le stream.
  /// - Visage : appel FaceMesh manuel avec garde anti-backlog.
  void processFrame(CameraImage image, CameraDescription camera) {
    if (_disposed) return;
    if (target == TrackingTarget.hand) {
      _hand?.processFrame(image, camera.sensorOrientation);
    } else {
      if (_busy) return;
      _busy = true;
      _processFace(image, camera).whenComplete(() => _busy = false);
    }
  }

  /// Publie un résultat lissé (ou propage la perte de détection).
  void _emit(AnchorResult? raw) {
    if (_disposed) return;
    if (raw == null) {
      _smoother.reset(); // ré-acquisition nette plutôt qu'un glissement
      _controller.add(null);
      return;
    }
    final int t = DateTime.now().millisecondsSinceEpoch;
    final ({Offset position, double scale, double rotation}) s = _smoother.apply(
      position: raw.position,
      scale: raw.scale,
      rotation: raw.rotationRadians,
      tMs: t,
    );
    _controller.add(AnchorResult(
      position: s.position,
      scale: s.scale,
      rotationRadians: s.rotation,
    ));
  }

  // --- MAIN --------------------------------------------------------------

  void _onHands(List<Hand> hands) {
    if (_disposed) return;
    if (hands.isEmpty) {
      _emit(null);
      return;
    }
    final Hand hand = hands.first;
    final List<Landmark> lm = hand.landmarks;
    if (lm.length < 21) {
      _emit(null);
      return;
    }

    // Indices standards MediaPipe Hand :
    //  0  = wrist ; 9 = base du majeur (échelle) ; 13/14 = annulaire.
    final Offset wrist = Offset(lm[0].x, lm[0].y);
    final Offset middleMcp = Offset(lm[9].x, lm[9].y);

    final Offset target = anchor == AnchorPoint.wrist
        ? wrist
        : AnchorMath.midpoint(
            Offset(lm[13].x, lm[13].y), Offset(lm[14].x, lm[14].y));

    final double handSize = AnchorMath.distance(middleMcp, wrist);
    final double px = AnchorMath.mirrorX(target.dx, mirror);

    _emit(AnchorResult(
      position: AnchorMath.clampToFrame(Offset(px, target.dy)),
      scale: (handSize * 0.6).clamp(0.05, 0.5),
    ));
  }

  // --- VISAGE (FaceMesh 468) --------------------------------------------

  Future<void> _processFace(CameraImage image, CameraDescription cam) async {
    final FaceMeshDetector? det = _faceMesh;
    if (det == null) return;

    final InputImage? input = _toInputImage(image, cam);
    if (input == null) return;

    try {
      final List<FaceMesh> meshes = await det.processImage(input);
      if (_disposed) return;

      if (meshes.isEmpty) {
        _emit(null);
        return;
      }

      final FaceMesh mesh = meshes.first;
      final List<FaceMeshPoint> pts = mesh.points;
      if (pts.length < 468) {
        _emit(null);
        return;
      }

      // FaceMesh renvoie les coordonnées dans l'espace de l'image **redressée**.
      // Pour 90/270°, les dimensions utiles sont inversées vs le buffer brut.
      final bool rotated =
          cam.sensorOrientation == 90 || cam.sensorOrientation == 270;
      final double w = (rotated ? image.height : image.width).toDouble();
      final double h = (rotated ? image.width : image.height).toDouble();

      Offset pix(int i) => Offset(pts[i].x.toDouble(), pts[i].y.toDouble());

      // Point d'ancrage de base selon le type de bijou.
      Offset anchorPix = pix(FaceMeshIndices.baseFor(anchor));

      // Cou : pas de landmark dédié → on descend depuis le menton d'une
      // fraction de la hauteur du visage pour viser la base du cou.
      if (anchor == AnchorPoint.neck) {
        anchorPix =
            Offset(anchorPix.dx, anchorPix.dy + mesh.boundingBox.height * 0.5);
      }

      final Offset pos = AnchorMath.normalize(anchorPix, w, h);

      // Échelle : largeur du visage (oreille à oreille), normalisée.
      final double faceW = AnchorMath.distance(
            pix(FaceMeshIndices.rightEar),
            pix(FaceMeshIndices.leftEar),
          ) /
          w;

      // Roulis : angle entre les coins externes des yeux (espace pixel).
      final double roll = AnchorMath.rollRadians(
        pix(FaceMeshIndices.rightEyeOuter),
        pix(FaceMeshIndices.leftEyeOuter),
      );

      final double px = AnchorMath.mirrorX(pos.dx, mirror);
      _emit(AnchorResult(
        position: AnchorMath.clampToFrame(Offset(px, pos.dy)),
        scale: (faceW * 0.9).clamp(0.05, 0.6),
        rotationRadians: mirror ? -roll : roll,
      ));
    } catch (e) {
      debugPrint('AR face mesh error: $e');
    }
  }

  /// Construit l'InputImage attendu par ML Kit à partir du CameraImage.
  ///
  /// Le controller caméra est configuré en **NV21** pour la cible visage :
  /// l'image tient dans un seul plan exploitable directement, sans
  /// concaténation manuelle (qui produirait un buffer invalide pour ML Kit).
  InputImage? _toInputImage(CameraImage img, CameraDescription cam) {
    final Plane plane = img.planes.first;

    final Size size = Size(img.width.toDouble(), img.height.toDouble());
    final InputImageRotation rotation =
        InputImageRotationValue.fromRawValue(cam.sensorOrientation) ??
            InputImageRotation.rotation0deg;
    final InputImageFormat format =
        InputImageFormatValue.fromRawValue(img.format.raw) ??
            InputImageFormat.nv21;

    return InputImage.fromBytes(
      bytes: plane.bytes,
      metadata: InputImageMetadata(
        size: size,
        rotation: rotation,
        format: format,
        bytesPerRow: plane.bytesPerRow,
      ),
    );
  }

  Future<void> dispose() async {
    _disposed = true;
    await _handSub?.cancel();
    _handSub = null;
    await _controller.close();
    _hand?.dispose();
    _hand = null;
    await _faceMesh?.close();
    _faceMesh = null;
  }
}
