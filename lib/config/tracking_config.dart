import 'package:camera/camera.dart';

/// Cible de tracking : main (caméra arrière) ou visage (caméra frontale).
enum TrackingTarget { hand, face }

/// Sous-cible précise : où placer le bijou sur le repère détecté.
/// Permet à la vue AR de savoir quel landmark utiliser.
enum AnchorPoint {
  ringFinger,   // bague — base de l'annulaire
  wrist,        // bracelet — poignet
  neck,         // collier — sous le menton
  ear,          // boucle d'oreille — lobe d'oreille
  forehead,     // perles/diadème — front
}

/// Configuration du tracking : chaque type de bijou est mappé à
/// sa cible (main/visage) et à son point d'ancrage précis.
///
/// Si on ajoute un nouveau type de bijou, il suffit d'ajouter une
/// entrée ici. Si le type n'est pas listé, le défaut est : main.
class TrackingConfig {
  static const Map<String, TrackingTarget> _targetByFolder =
      <String, TrackingTarget>{
    'bagues': TrackingTarget.hand,
    'bracelets': TrackingTarget.hand,
    'colliers': TrackingTarget.face,
    'boucles': TrackingTarget.face,
    'perles': TrackingTarget.face,
  };

  static const Map<String, AnchorPoint> _anchorByFolder =
      <String, AnchorPoint>{
    'bagues': AnchorPoint.ringFinger,
    'bracelets': AnchorPoint.wrist,
    'colliers': AnchorPoint.neck,
    'boucles': AnchorPoint.ear,
    'perles': AnchorPoint.forehead,
  };

  static TrackingTarget targetFor(String folder) =>
      _targetByFolder[folder] ?? TrackingTarget.hand;

  static AnchorPoint anchorFor(String folder) =>
      _anchorByFolder[folder] ?? AnchorPoint.ringFinger;

  /// Renvoie la direction caméra adaptée au type de bijou.
  /// Toujours caméra frontale (mode miroir selfie) pour une expérience try-on.
  static CameraLensDirection lensFor(String folder) =>
      CameraLensDirection.front;
}

/// Indices de landmarks du **FaceMesh MediaPipe (468 points)**, tels que
/// renvoyés par `google_mlkit_face_mesh_detection`.
///
/// Références de la topologie canonique FaceMesh :
///  - 10  : haut du front (ligne de cheveux, centre) → diadèmes/perles
///  - 152 : pointe du menton → extrapolation base du cou (colliers)
///  - 234 : oreille droite (côté image), 454 : oreille gauche → boucles
///  - 33  : coin externe œil droit, 263 : coin externe œil gauche → roulis
///  - 1   : pointe du nez → repli centre visage
class FaceMeshIndices {
  const FaceMeshIndices._();

  static const int foreheadTop = 10;
  static const int chin = 152;
  static const int rightEar = 234;
  static const int leftEar = 454;
  static const int rightEyeOuter = 33;
  static const int leftEyeOuter = 263;
  static const int noseTip = 1;

  /// Landmark de base pour un point d'ancrage visage donné. Certaines cibles
  /// (cou) partent de ce point puis extrapolent (cf. ARService).
  static int baseFor(AnchorPoint anchor) {
    switch (anchor) {
      case AnchorPoint.ear:
        return leftEar; // boucle sur l'oreille gauche (côté écran en miroir)
      case AnchorPoint.neck:
        return chin; // point de départ, on descend ensuite vers le cou
      case AnchorPoint.forehead:
        return foreheadTop;
      case AnchorPoint.ringFinger:
      case AnchorPoint.wrist:
        return noseTip; // cibles main : non utilisé côté visage
    }
  }
}
