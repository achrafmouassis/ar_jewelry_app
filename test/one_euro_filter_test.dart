import 'dart:math' as math;

import 'package:ar_jewelry_app/services/one_euro_filter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('OneEuroFilter', () {
    test('renvoie la première valeur telle quelle (amorçage)', () {
      final OneEuroFilter f = OneEuroFilter(minCutoff: 1.0, beta: 0.0);
      expect(f.filter(0.5, 0), 0.5);
    });

    test('converge vers un signal constant bruité', () {
      final OneEuroFilter f = OneEuroFilter(minCutoff: 1.0, beta: 0.007);
      final math.Random rng = math.Random(42);
      const double truth = 0.4;
      double out = 0;
      int t = 0;
      // 3 s à ~60 Hz de valeur constante + bruit ±0.05.
      for (int i = 0; i < 180; i++) {
        t += 16;
        final double noisy = truth + (rng.nextDouble() - 0.5) * 0.1;
        out = f.filter(noisy, t);
      }
      // La sortie doit rester très proche de la vérité terrain.
      expect((out - truth).abs(), lessThan(0.02));
    });

    test('réduit fortement le jitter (variance de sortie << variance d\'entrée)',
        () {
      final OneEuroFilter f = OneEuroFilter(minCutoff: 1.0, beta: 0.007);
      final math.Random rng = math.Random(7);
      const double truth = 0.5;
      final List<double> inputs = <double>[];
      final List<double> outputs = <double>[];
      int t = 0;
      for (int i = 0; i < 200; i++) {
        t += 16;
        final double noisy = truth + (rng.nextDouble() - 0.5) * 0.2;
        inputs.add(noisy);
        outputs.add(f.filter(noisy, t));
      }
      // Ignore la phase d'amorçage (premières frames).
      double variance(List<double> xs) {
        final List<double> s = xs.sublist(50);
        final double m = s.reduce((a, b) => a + b) / s.length;
        return s.map((x) => (x - m) * (x - m)).reduce((a, b) => a + b) /
            s.length;
      }

      final double vin = variance(inputs);
      final double vout = variance(outputs);
      expect(vout, lessThan(vin * 0.5),
          reason: 'le filtre doit au moins halver la variance');
    });

    test('suit un échelon (réactivité au mouvement rapide)', () {
      final OneEuroFilter f = OneEuroFilter(minCutoff: 1.5, beta: 0.05);
      int t = 0;
      for (int i = 0; i < 30; i++) {
        f.filter(0.0, t += 16);
      }
      double out = 0;
      for (int i = 0; i < 30; i++) {
        out = f.filter(1.0, t += 16);
      }
      // Après l'échelon, la sortie doit avoir largement rattrapé la cible.
      expect(out, greaterThan(0.8));
    });

    test('reset ré-amorce le filtre', () {
      final OneEuroFilter f = OneEuroFilter();
      f.filter(0.2, 0);
      f.filter(0.9, 16);
      f.reset();
      // Après reset, la valeur suivante est renvoyée telle quelle.
      expect(f.filter(0.33, 32), 0.33);
    });
  });

  group('AnchorSmoother', () {
    test('lisse les 4 grandeurs et renvoie la 1re passe à l\'identique', () {
      final AnchorSmoother s = AnchorSmoother();
      final res = s.apply(
        position: const Offset(0.3, 0.7),
        scale: 0.2,
        rotation: 0.1,
        tMs: 0,
      );
      expect(res.position.dx, 0.3);
      expect(res.position.dy, 0.7);
      expect(res.scale, 0.2);
      expect(res.rotation, 0.1);
    });

    test('déroule la rotation au passage ±π (pas de tour complet)', () {
      final AnchorSmoother s = AnchorSmoother();
      // Amorçage juste sous +π.
      double out = s
          .apply(position: Offset.zero, scale: 0.2, rotation: 3.1, tMs: 0)
          .rotation;
      // L'orientation physique bascule sur la branche −π (−3.1 ≡ +3.18…) :
      // la sortie lissée doit rester sur la branche continue proche de +π,
      // pas glisser vers 0 en traversant tout le cercle.
      for (int i = 1; i <= 30; i++) {
        out = s
            .apply(
                position: Offset.zero, scale: 0.2, rotation: -3.1, tMs: i * 16)
            .rotation;
      }
      expect(out, greaterThan(3.0));
      expect(out, closeTo(2 * math.pi - 3.1, 0.05));
    });

    test('reset ré-amorce aussi le déroulage d\'angle', () {
      final AnchorSmoother s = AnchorSmoother();
      s.apply(position: Offset.zero, scale: 0.2, rotation: 3.1, tMs: 0);
      s.reset();
      // Après reset, aucune référence : la rotation repart telle quelle.
      final double out = s
          .apply(position: Offset.zero, scale: 0.2, rotation: -3.1, tMs: 16)
          .rotation;
      expect(out, -3.1);
    });
  });
}
