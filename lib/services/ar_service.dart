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

  /// Inclinaison hors-plan de l'axe anatomique (doigt / avant-bras) :
  /// positif = pointe vers la caméra, négatif = s'en éloigne. Sert à
  /// orienter la caméra 3D du rendu pour que le bijou se tourne avec la
  /// main au lieu de rester plaqué face à l'écran.
  final double tiltRadians;

  const AnchorResult({
    required this.position,
    required this.scale,
    this.rotationRadians = 0.0,
    this.tiltRadians = 0.0,
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

  /// Orientation du capteur (90/270 sur la quasi-totalité des téléphones).
  /// Nécessaire pour re-projeter les landmarks MediaPipe — qui sont renvoyés
  /// dans l'espace du buffer capteur BRUT (paysage, ni tourné ni miroir) —
  /// vers l'espace de l'aperçu affiché (portrait redressé + miroir selfie).
  int _sensorOrientation = 90;

  /// Throttle du pipeline main : la conversion YUV→RGB du plugin se fait
  /// pixel par pixel sur le thread appelant. À 30 fps en 720p ça sature le
  /// thread UI et crée un backlog → l'overlay traîne derrière la main.
  /// ~15 Hz de détection suffisent, le lissage One-Euro fait le reste.
  static const int _minHandIntervalMs = 66;
  int _lastHandFrameMs = 0;

  // Luminosité ambiante moyenne (plan Y, 0..1), échantillonnée ~2×/s.
  // Sert à caler l'exposition du rendu 3D sur l'éclairage réel de la scène
  // pour que le bijou ne paraisse pas "éclairé studio" dans une pièce sombre.
  static const int _lumaIntervalMs = 500;
  int _lastLumaMs = 0;
  double _ambientLuma = 0.5;

  /// Dernière luminosité ambiante mesurée (0 = noir, 1 = blanc).
  double get ambientLuma => _ambientLuma;

  /// Ratio hauteur/largeur de la frame **redressée** (portrait). Les landmarks
  /// sont normalisés indépendamment sur chaque axe : sans ce ratio,
  /// distances et angles calculés en mélangeant dx et dy sont faux
  /// (l'axe Y "pèse" ~1,5× plus que l'axe X en portrait).
  double _frameAspect = 4 / 3;

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
    _sampleAmbientLuma(image);
    if (target == TrackingTarget.hand) {
      final int now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastHandFrameMs < _minHandIntervalMs) return;
      _lastHandFrameMs = now;

      _sensorOrientation = camera.sensorOrientation;
      // Buffer capteur en paysage (width > height) ; une fois redressée en
      // portrait (rotation 90/270), la hauteur de la frame = image.width.
      final bool rotated =
          _sensorOrientation == 90 || _sensorOrientation == 270;
      _frameAspect = rotated
          ? image.width / image.height
          : image.height / image.width;
      _hand?.processFrame(image, camera.sensorOrientation);
    } else {
      if (_busy) return;
      _busy = true;
      _processFace(image, camera).whenComplete(() => _busy = false);
    }
  }

  /// Moyenne du plan Y (luma) sous-échantillonnée 1/64, en respectant le
  /// bytesPerRow (le padding de fin de ligne fausserait la moyenne).
  void _sampleAmbientLuma(CameraImage image) {
    final int now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastLumaMs < _lumaIntervalMs) return;
    _lastLumaMs = now;

    final Plane y = image.planes.first;
    final int rowStride = y.bytesPerRow;
    int sum = 0, n = 0;
    for (int r = 0; r < image.height; r += 8) {
      final int rowStart = r * rowStride;
      for (int c = 0; c < image.width; c += 8) {
        sum += y.bytes[rowStart + c];
        n++;
      }
    }
    if (n > 0) {
      // EMA légère pour éviter les sautes d'exposition (auto-expo caméra).
      _ambientLuma = _ambientLuma * 0.6 + (sum / n / 255.0) * 0.4;
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

  /// Re-projette un landmark MediaPipe (normalisé dans le buffer capteur
  /// BRUT, paysage) vers l'espace de l'aperçu affiché : rotation
  /// [_sensorOrientation] horaire pour redresser, puis miroir horizontal
  /// si caméra frontale — exactement les transformations que le plugin
  /// caméra applique au flux vidéo pour l'afficher.
  Offset _toDisplaySpace(Landmark l) {
    double x, y;
    switch (_sensorOrientation) {
      case 90:
        x = 1.0 - l.y;
        y = l.x;
        break;
      case 180:
        x = 1.0 - l.x;
        y = 1.0 - l.y;
        break;
      case 270:
        x = l.y;
        y = 1.0 - l.x;
        break;
      default: // 0
        x = l.x;
        y = l.y;
    }
    if (mirror) x = 1.0 - x;
    return Offset(x, y);
  }

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
    //
    // Tous les points sont d'abord re-projetés dans l'espace d'affichage :
    // le reste du calcul (ancre, direction, échelle, rotation) se fait dans
    // un seul espace cohérent, sans cas particulier miroir.
    final Offset wrist = _toDisplaySpace(lm[0]);
    final Offset indexMcp = _toDisplaySpace(lm[5]);
    final Offset middleMcp = _toDisplaySpace(lm[9]);
    final Offset ringMcp = _toDisplaySpace(lm[13]);
    final Offset ringPip = _toDisplaySpace(lm[14]);
    final Offset pinkyMcp = _toDisplaySpace(lm[17]);

    // Distances/angles en "unités de largeur" : dy est multiplié par
    // _frameAspect pour compenser la normalisation indépendante des axes.
    // dz (profondeur MediaPipe) est déjà à l'échelle de la largeur ; il est
    // insensible à la rotation écran et au miroir (axe caméra).
    double tx, ty;
    final double dirX, dirY; // direction anatomique (vers le bout des doigts)
    final double dirZ;       // <0 = pointe vers la caméra
    if (anchor == AnchorPoint.wrist) {
      // Le landmark 0 est au pli du poignet (base de la paume) ; un bracelet
      // se porte 3-4 cm plus bas sur l'avant-bras → on décale l'ancre de
      // 40 % de la longueur de la main à l'opposé des doigts. (L'offset
      // suit l'axe projeté du bras : il raccourcit naturellement avec la
      // perspective quand le bras s'incline.)
      tx = wrist.dx - (middleMcp.dx - wrist.dx) * 0.40;
      ty = wrist.dy - (middleMcp.dy - wrist.dy) * 0.40;
      dirX = middleMcp.dx - wrist.dx;
      dirY = (middleMcp.dy - wrist.dy) * _frameAspect;
      dirZ = lm[9].z - lm[0].z;
    } else {
      // Base de l'annulaire, à 55 % entre MCP (13) et PIP (14) : le
      // landmark MCP est dans la chair de la paume, pas à la surface du
      // doigt — l'ancre remonte donc légèrement au-delà du milieu pour
      // que l'anneau tombe visuellement sur la phalange.
      tx = ringMcp.dx + (ringPip.dx - ringMcp.dx) * 0.55;
      ty = ringMcp.dy + (ringPip.dy - ringMcp.dy) * 0.55;
      dirX = ringPip.dx - ringMcp.dx;
      dirY = (ringPip.dy - ringMcp.dy) * _frameAspect;
      dirZ = lm[14].z - lm[13].z;
    }

    // Largeur du doigt ≈ écart entre les MCP du majeur (9) et de
    // l'annulaire (13) : bien plus fiable que la taille de main pour
    // dimensionner une bague, quelle que soit la pose (poing, main ouverte).
    //
    // Les mesures incluent la composante z MediaPipe (déjà à l'échelle de
    // la largeur) : une distance purement 2D rétrécirait dès que la main
    // s'incline (raccourci de projection) et le bijou avec elle.
    final double fwx = ringMcp.dx - middleMcp.dx;
    final double fwy = (ringMcp.dy - middleMcp.dy) * _frameAspect;
    final double fwz = lm[13].z - lm[9].z;
    final double fingerW =
        math.sqrt(fwx * fwx + fwy * fwy + fwz * fwz);

    // Largeur de paume = écart MCP index (5) → MCP auriculaire (17).
    // Le poignet fait ~2/3 de la paume et un jonc l'englobe avec du jeu :
    // c'est la référence la plus stable pour la taille du bracelet.
    final double pwx = pinkyMcp.dx - indexMcp.dx;
    final double pwy = (pinkyMcp.dy - indexMcp.dy) * _frameAspect;
    final double pwz = lm[17].z - lm[5].z;
    final double palmW =
        math.sqrt(pwx * pwx + pwy * pwy + pwz * pwz);

    // model-viewer cadre le modèle sur sa sphère englobante : la fraction
    // du widget réellement occupée par le diamètre du bijou a été calculée
    // depuis les GLB (tool/glb_cut_back.dart + sphère three.js) :
    //   bague ≈ 0,80 · bracelet ≈ 0,70.
    // Anatomie visée :
    //   bague : diamètre ≈ 1,15× l'écart MCP majeur-annulaire
    //           → widget = 1,15 / 0,80 ≈ 1,5 × fingerW ;
    //   bracelet : poignet visible ≈ 0,9× la paume, jonc = 1,3× le poignet
    //           (il déborde nettement, c'est ce qui "englobe")
    //           → widget = 1,17 / 0,70 ≈ 1,65 × palmW.
    final double scale = anchor == AnchorPoint.wrist
        ? (palmW * 1.65).clamp(0.15, 0.85)
        : (fingerW * 1.5).clamp(0.08, 0.40);

    // Rotation : aligne le "haut" du modèle avec la direction du doigt
    // (bague) ou de l'avant-bras → main (bracelet). Les points étant déjà
    // dans l'espace d'affichage, aucun ajustement miroir n'est nécessaire.
    final double rotation = math.atan2(dirX, -dirY);

    // Inclinaison hors-plan : angle entre l'axe anatomique et le plan écran.
    // dz < 0 quand la pointe se rapproche de la caméra → tilt positif.
    final double planar = math.sqrt(dirX * dirX + dirY * dirY);
    final double tilt = math.atan2(-dirZ, planar);

    _emit(AnchorResult(
      position: Offset(tx.clamp(0.0, 1.0), ty.clamp(0.0, 1.0)),
      scale: scale,
      rotationRadians: rotation,
      tiltRadians: tilt,
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
  // La profondeur MediaPipe (z) est plus bruitée que x/y → cutoff plus bas,
  // mais assez réactif pour que le bijou pivote avec la main sans traîner.
  final _OneEuroFilter _tilt = _OneEuroFilter(minCutoff: 0.8, beta: 0.01);

  /// Avance temporelle (s) pour la compensation de latence : on projette la
  /// position selon la vitesse lissée pour que le bijou reste "collé" à la
  /// main en mouvement au lieu de traîner derrière. Calé sur la latence
  /// mesurée du pipeline (~throttle 66 ms + détection + rendu).
  static const double _leadSeconds = 0.09;

  /// Déplacement prédictif max (unités normalisées) : borne l'extrapolation
  /// pour éviter tout dépassement lors des changements brusques de direction.
  static const double _maxLead = 0.06;

  AnchorResult apply(AnchorResult r) {
    final int t = DateTime.now().millisecondsSinceEpoch;
    final double fx = _x.filter(r.position.dx, t);
    final double fy = _y.filter(r.position.dy, t);
    // Extrapolation : position filtrée + vitesse lissée × avance, bornée.
    final double px =
        (fx + (_x.velocity * _leadSeconds).clamp(-_maxLead, _maxLead))
            .clamp(0.0, 1.0);
    final double py =
        (fy + (_y.velocity * _leadSeconds).clamp(-_maxLead, _maxLead))
            .clamp(0.0, 1.0);
    return AnchorResult(
      position: Offset(px, py),
      scale: _scale.filter(r.scale, t),
      rotationRadians: _rot.filter(r.rotationRadians, t),
      tiltRadians: _tilt.filter(r.tiltRadians, t),
    );
  }

  void reset() {
    _x.reset();
    _y.reset();
    _scale.reset();
    _rot.reset();
    _tilt.reset();
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

  /// Vitesse lissée courante (unités/seconde), utilisée pour extrapoler la
  /// position et masquer la latence du pipeline (throttle + MediaPipe async
  /// + compositing WebView). 0 tant qu'aucune donnée n'est arrivée.
  double get velocity => _dxPrev ?? 0.0;

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
