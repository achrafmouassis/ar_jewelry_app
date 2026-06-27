import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show Offset, Size;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:google_mlkit_face_detection/google_mlkit_face_detection.dart';
import 'package:hand_landmarker/hand_landmarker.dart';

import '../config/tracking_config.dart';

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

/// Service AR : encapsule MediaPipe Hand Landmarker (main) et ML Kit
/// Face Detection (visage), et expose un flux unifié [AnchorResult].
///
/// Points perf :
/// - MediaPipe en delegate GPU (configurable côté plugin).
/// - Hand Landmarker est asynchrone : on lui pousse les frames via
///   `processFrame`, et on consomme les résultats via `landmarkStream`.
/// - ML Kit est appelé manuellement avec un garde `_busy` (throttle) pour
///   ne pas accumuler de frames en queue.
/// - Les positions sont lissées par un filtre One-Euro pour éliminer le
///   tremblement frame-à-frame (perçu comme un overlay "qui vibre").
/// - `dispose()` obligatoire à la fermeture de l'écran.
class ARService {
  final TrackingTarget target;
  final AnchorPoint anchor;

  /// True si l'image affichée est en miroir (caméra frontale) : on retourne
  /// alors l'axe X pour que le bijou colle à ce que voit l'utilisateur.
  final bool mirror;

  HandLandmarkerPlugin? _hand;
  FaceDetector? _face;
  StreamSubscription<List<Hand>>? _handSub;

  bool _busy = false; // throttle pour le pipeline visage
  bool _disposed = false;

  // Lissage des sorties (anti-jitter).
  final _AnchorSmoother _smoother = _AnchorSmoother();

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

      // Abonnement au flux de détections : on traduit chaque détection
      // en AnchorResult et on la pousse dans notre StreamController.
      _handSub = _hand!.landmarkStream.listen(_onHands);
    } else {
      // ML Kit Face Detector — mode fast + landmarks (oreilles, nez, bouche).
      _face = FaceDetector(
        options: FaceDetectorOptions(
          enableLandmarks: true,
          enableContours: false,
          enableClassification: false,
          enableTracking: true,
          performanceMode: FaceDetectorMode.fast,
          minFaceSize: 0.15,
        ),
      );
    }
  }

  /// Traite une frame caméra.
  /// - Main : fire-and-forget vers MediaPipe ; les résultats arrivent
  ///   asynchronement par le stream.
  /// - Visage : appel ML Kit manuel avec garde anti-backlog.
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
    _controller.add(_smoother.apply(raw));
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
    //  0  = wrist
    //  9  = base du majeur (middle finger MCP) — repère d'échelle
    //  13 = base de l'annulaire (ring finger MCP)
    //  14 = phalange intermédiaire de l'annulaire (ring finger PIP)
    final double tx, ty;
    if (anchor == AnchorPoint.wrist) {
      tx = lm[0].x;
      ty = lm[0].y;
    } else {
      // milieu entre 13 et 14 → emplacement naturel d'une bague
      tx = (lm[13].x + lm[14].x) / 2;
      ty = (lm[13].y + lm[14].y) / 2;
    }

    // Échelle = distance euclidienne entre poignet (0) et MCP majeur (9).
    final double dx = lm[9].x - lm[0].x;
    final double dy = lm[9].y - lm[0].y;
    final double handSize = math.sqrt(dx * dx + dy * dy);

    final double px = mirror ? 1.0 - tx : tx;
    _emit(AnchorResult(
      position: Offset(px.clamp(0.0, 1.0), ty.clamp(0.0, 1.0)),
      scale: (handSize * 0.6).clamp(0.05, 0.5),
    ));
  }

  // --- VISAGE ------------------------------------------------------------

  Future<void> _processFace(CameraImage image, CameraDescription cam) async {
    final FaceDetector? det = _face;
    if (det == null) return;

    final InputImage? input = _toInputImage(image, cam);
    if (input == null) return;

    try {
      final List<Face> faces = await det.processImage(input);
      if (_disposed) return;

      if (faces.isEmpty) {
        _emit(null);
        return;
      }

      final Face face = faces.first;

      // ML Kit renvoie les coordonnées dans l'espace de l'image **redressée**
      // (rotation appliquée). Pour une rotation de 90/270°, les dimensions
      // utiles sont donc inversées par rapport au buffer brut.
      final bool rotated =
          cam.sensorOrientation == 90 || cam.sensorOrientation == 270;
      final double w =
          (rotated ? image.height : image.width).toDouble();
      final double h =
          (rotated ? image.width : image.height).toDouble();

      Offset? pos;
      switch (anchor) {
        case AnchorPoint.ear:
          final FaceLandmark? leftEar =
              face.landmarks[FaceLandmarkType.leftEar];
          if (leftEar != null) {
            pos = Offset(
              leftEar.position.x.toDouble() / w,
              leftEar.position.y.toDouble() / h,
            );
          }
          break;
        case AnchorPoint.neck:
          // ML Kit n'a pas de landmark "cou" : on prend la bouche et on
          // descend de ~40% de la hauteur du visage.
          final FaceLandmark? mouth =
              face.landmarks[FaceLandmarkType.bottomMouth];
          if (mouth != null) {
            pos = Offset(
              mouth.position.x.toDouble() / w,
              (mouth.position.y.toDouble() + face.boundingBox.height * 0.4) / h,
            );
          }
          break;
        case AnchorPoint.forehead:
          final FaceLandmark? nose =
              face.landmarks[FaceLandmarkType.noseBase];
          if (nose != null) {
            pos = Offset(
              nose.position.x.toDouble() / w,
              (nose.position.y.toDouble() - face.boundingBox.height * 0.45) / h,
            );
          }
          break;
        default:
          pos = Offset(
            (face.boundingBox.left + face.boundingBox.width / 2) / w,
            (face.boundingBox.top + face.boundingBox.height / 2) / h,
          );
      }

      if (pos == null) {
        _emit(null);
        return;
      }

      // Miroir horizontal pour la caméra frontale.
      final double px = mirror ? 1.0 - pos.dx : pos.dx;
      final double faceSize = face.boundingBox.width / w;
      final double roll = (face.headEulerAngleZ ?? 0) * math.pi / 180.0;

      _emit(AnchorResult(
        position: Offset(px.clamp(0.0, 1.0), pos.dy.clamp(0.0, 1.0)),
        scale: (faceSize * 0.9).clamp(0.05, 0.6),
        rotationRadians: mirror ? -roll : roll,
      ));
    } catch (e) {
      debugPrint('AR face error: $e');
    }
  }

  /// Construit l'InputImage attendu par ML Kit à partir du CameraImage.
  ///
  /// Le controller caméra est configuré en **NV21** pour la cible visage :
  /// l'image tient donc dans un seul plan exploitable directement, sans
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
    await _face?.close();
    _face = null;
  }
}

/// Lissage One-Euro appliqué aux 4 grandeurs d'un [AnchorResult]
/// (x, y, échelle, rotation). Réduit fortement le jitter tout en gardant
/// une bonne réactivité lors des mouvements rapides.
class _AnchorSmoother {
  final _OneEuroFilter _x = _OneEuroFilter(minCutoff: 1.2, beta: 0.02);
  final _OneEuroFilter _y = _OneEuroFilter(minCutoff: 1.2, beta: 0.02);
  final _OneEuroFilter _scale = _OneEuroFilter(minCutoff: 0.8, beta: 0.01);
  final _OneEuroFilter _rot = _OneEuroFilter(minCutoff: 1.0, beta: 0.01);

  AnchorResult apply(AnchorResult r) {
    final int t = DateTime.now().millisecondsSinceEpoch;
    return AnchorResult(
      position: Offset(_x.filter(r.position.dx, t), _y.filter(r.position.dy, t)),
      scale: _scale.filter(r.scale, t),
      rotationRadians: _rot.filter(r.rotationRadians, t),
    );
  }

  void reset() {
    _x.reset();
    _y.reset();
    _scale.reset();
    _rot.reset();
  }
}

/// Filtre One-Euro (Casiez et al., 2012).
/// Faible latence à faible vitesse (lisse le bruit), faible lissage à
/// haute vitesse (suit le mouvement) — idéal pour du tracking AR.
class _OneEuroFilter {
  final double minCutoff;
  final double beta;

  // Fréquence de coupure du dérivé : 1 Hz est la valeur usuelle du papier.
  static const double dCutoff = 1.0;

  double? _xPrev;
  double? _dxPrev;
  int? _tPrev; // ms

  _OneEuroFilter({
    this.minCutoff = 1.0,
    this.beta = 0.0,
  });

  double _alpha(double cutoff, double dt) {
    final double tau = 1.0 / (2 * math.pi * cutoff);
    return 1.0 / (1.0 + tau / dt);
  }

  double filter(double x, int tMs) {
    if (_tPrev == null) {
      _tPrev = tMs;
      _xPrev = x;
      _dxPrev = 0.0;
      return x;
    }

    double dt = (tMs - _tPrev!) / 1000.0;
    if (dt <= 0) dt = 1.0 / 30.0; // garde-fou
    _tPrev = tMs;

    final double dx = (x - _xPrev!) / dt;
    final double edx =
        _alpha(dCutoff, dt) * dx + (1 - _alpha(dCutoff, dt)) * _dxPrev!;
    _dxPrev = edx;

    final double cutoff = minCutoff + beta * edx.abs();
    final double a = _alpha(cutoff, dt);
    final double ex = a * x + (1 - a) * _xPrev!;
    _xPrev = ex;
    return ex;
  }

  void reset() {
    _xPrev = null;
    _dxPrev = null;
    _tPrev = null;
  }
}
