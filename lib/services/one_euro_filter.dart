import 'dart:math' as math;
import 'dart:ui' show Offset;

/// Filtre One-Euro (Casiez et al., 2012).
///
/// Faible latence à faible vitesse (lisse le bruit), faible lissage à haute
/// vitesse (suit le mouvement) — idéal pour du tracking AR. Extrait dans son
/// propre module pour être testable indépendamment du pipeline caméra.
class OneEuroFilter {
  final double minCutoff;
  final double beta;

  /// Fréquence de coupure du dérivé : 1 Hz est la valeur usuelle du papier.
  static const double dCutoff = 1.0;

  double? _xPrev;
  double? _dxPrev;
  int? _tPrev; // ms

  OneEuroFilter({
    this.minCutoff = 1.0,
    this.beta = 0.0,
  });

  double _alpha(double cutoff, double dt) {
    final double tau = 1.0 / (2 * math.pi * cutoff);
    return 1.0 / (1.0 + tau / dt);
  }

  /// Filtre la valeur [x] échantillonnée à l'instant [tMs] (millisecondes).
  double filter(double x, int tMs) {
    if (_tPrev == null) {
      _tPrev = tMs;
      _xPrev = x;
      _dxPrev = 0.0;
      return x;
    }

    double dt = (tMs - _tPrev!) / 1000.0;
    if (dt <= 0) dt = 1.0 / 30.0; // garde-fou (horodatage non monotone)
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

/// Applique un filtre One-Euro indépendant à chacune des 4 grandeurs d'un
/// ancrage (x, y, échelle, rotation), pour supprimer le tremblement
/// frame-à-frame perçu comme un overlay « qui vibre ».
class AnchorSmoother {
  final OneEuroFilter _x = OneEuroFilter(minCutoff: 1.2, beta: 0.02);
  final OneEuroFilter _y = OneEuroFilter(minCutoff: 1.2, beta: 0.02);
  final OneEuroFilter _scale = OneEuroFilter(minCutoff: 0.8, beta: 0.01);
  final OneEuroFilter _rot = OneEuroFilter(minCutoff: 1.0, beta: 0.01);

  /// Lisse (x, y, scale, rot) à l'instant [tMs]. Renvoie les valeurs filtrées.
  ({Offset position, double scale, double rotation}) apply({
    required Offset position,
    required double scale,
    required double rotation,
    required int tMs,
  }) {
    return (
      position: Offset(_x.filter(position.dx, tMs), _y.filter(position.dy, tMs)),
      scale: _scale.filter(scale, tMs),
      rotation: _rot.filter(rotation, tMs),
    );
  }

  void reset() {
    _x.reset();
    _y.reset();
    _scale.reset();
    _rot.reset();
  }
}
