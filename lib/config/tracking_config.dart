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
