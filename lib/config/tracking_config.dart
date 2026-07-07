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

  /// Correction d'orientation model-viewer ("roll pitch yaw") par type.
  ///
  /// Les modèles Meshy sont exportés en pose "photo produit" : la bague a
  /// son trou face caméra (axe Z) et sa pierre vers le haut. Portée au
  /// doigt, le trou doit suivre le doigt (vertical dans le widget) et la
  /// pierre faire face à la caméra → tangage +90°. Vérifié visuellement
  /// sur les GLB actuels ; à ajuster si un nouveau modèle est exporté
  /// avec d'autres axes. Le bracelet Meshy a déjà son axe vertical.
  static const Map<String, String> _orientationByFolder = <String, String>{
    'bagues': '0deg 90deg 0deg',
  };

  static TrackingTarget targetFor(String folder) =>
      _targetByFolder[folder] ?? TrackingTarget.hand;

  static AnchorPoint anchorFor(String folder) =>
      _anchorByFolder[folder] ?? AnchorPoint.ringFinger;

  static String? orientationFor(String folder) =>
      _orientationByFolder[folder];

  /// Renvoie la direction caméra adaptée au type de bijou.
  /// Toujours caméra frontale (mode miroir selfie) pour une expérience try-on.
  static CameraLensDirection lensFor(String folder) =>
      CameraLensDirection.front;
}
