import 'dart:math' as math;
import 'dart:ui' show Offset;

/// Fonctions géométriques pures utilisées pour transformer des landmarks
/// (main MediaPipe 21 pts / visage FaceMesh 468 pts) en ancrage écran.
///
/// Isolées ici (sans dépendance aux plugins de détection) pour être testables
/// unitairement — cf. test/anchor_math_test.dart.
class AnchorMath {
  const AnchorMath._();

  /// Distance euclidienne entre deux points.
  static double distance(Offset a, Offset b) {
    final double dx = a.dx - b.dx;
    final double dy = a.dy - b.dy;
    return math.sqrt(dx * dx + dy * dy);
  }

  /// Milieu de deux points (ex: emplacement naturel d'une bague entre deux
  /// phalanges).
  static Offset midpoint(Offset a, Offset b) =>
      Offset((a.dx + b.dx) / 2, (a.dy + b.dy) / 2);

  /// Applique l'effet miroir horizontal (caméra frontale) à une coordonnée x
  /// normalisée [0..1].
  static double mirrorX(double x, bool mirror) => mirror ? 1.0 - x : x;

  /// Contraint une valeur à l'intervalle [0..1].
  static double clamp01(double v) => v.clamp(0.0, 1.0);

  /// Contraint un point à la frame normalisée [0..1] × [0..1].
  static Offset clampToFrame(Offset p) =>
      Offset(clamp01(p.dx), clamp01(p.dy));

  /// Angle de roulis (rotation autour de l'axe de vue) déduit de deux points
  /// alignés horizontalement au repos — typiquement les coins externes des
  /// yeux. Coordonnées en pixels (axes de même échelle) pour un angle correct.
  static double rollRadians(Offset right, Offset left) =>
      math.atan2(left.dy - right.dy, left.dx - right.dx);

  /// Normalise un point pixel (dans une image de dimensions w×h) en [0..1].
  static Offset normalize(Offset pixel, double w, double h) =>
      Offset(pixel.dx / w, pixel.dy / h);
}
