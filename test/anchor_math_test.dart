import 'dart:math' as math;

import 'package:ar_jewelry_app/services/anchor_math.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AnchorMath.distance', () {
    test('triangle 3-4-5', () {
      expect(
        AnchorMath.distance(const Offset(0, 0), const Offset(3, 4)),
        closeTo(5.0, 1e-9),
      );
    });
    test('distance nulle', () {
      expect(
        AnchorMath.distance(const Offset(1, 1), const Offset(1, 1)),
        0.0,
      );
    });
  });

  group('AnchorMath.midpoint', () {
    test('milieu de deux points', () {
      final Offset m =
          AnchorMath.midpoint(const Offset(0, 0), const Offset(2, 4));
      expect(m.dx, 1.0);
      expect(m.dy, 2.0);
    });
  });

  group('AnchorMath.mirrorX', () {
    test('miroir actif inverse x', () {
      expect(AnchorMath.mirrorX(0.3, true), closeTo(0.7, 1e-9));
    });
    test('miroir inactif conserve x', () {
      expect(AnchorMath.mirrorX(0.3, false), 0.3);
    });
    test('point central invariant sous miroir', () {
      expect(AnchorMath.mirrorX(0.5, true), closeTo(0.5, 1e-9));
    });
  });

  group('AnchorMath.clamp01 / clampToFrame', () {
    test('borne les valeurs hors [0,1]', () {
      expect(AnchorMath.clamp01(-0.2), 0.0);
      expect(AnchorMath.clamp01(1.5), 1.0);
      expect(AnchorMath.clamp01(0.4), 0.4);
    });
    test('clampToFrame borne les deux axes', () {
      final Offset p = AnchorMath.clampToFrame(const Offset(-0.1, 1.3));
      expect(p.dx, 0.0);
      expect(p.dy, 1.0);
    });
  });

  group('AnchorMath.normalize', () {
    test('pixel -> [0,1] selon dimensions', () {
      final Offset n =
          AnchorMath.normalize(const Offset(320, 240), 640, 480);
      expect(n.dx, closeTo(0.5, 1e-9));
      expect(n.dy, closeTo(0.5, 1e-9));
    });
  });

  group('AnchorMath.handRollRadians', () {
    test('main pointant vers le haut -> roulis nul', () {
      // Poignet en bas, base du majeur au-dessus (y vers le bas).
      final double roll = AnchorMath.handRollRadians(
          const Offset(100, 400), const Offset(100, 200));
      expect(roll, closeTo(0.0, 1e-9));
    });
    test('main inclinée à droite -> roulis positif (sens horaire écran)', () {
      final double roll = AnchorMath.handRollRadians(
          const Offset(100, 400), const Offset(300, 200));
      expect(roll, closeTo(math.pi / 4, 1e-9)); // 45°
    });
    test('main pointant à droite -> quart de tour horaire', () {
      final double roll = AnchorMath.handRollRadians(
          const Offset(100, 200), const Offset(300, 200));
      expect(roll, closeTo(math.pi / 2, 1e-9));
    });
  });

  group('AnchorMath.rollRadians', () {
    test('yeux horizontaux -> roulis nul', () {
      final double roll =
          AnchorMath.rollRadians(const Offset(100, 200), const Offset(300, 200));
      expect(roll, closeTo(0.0, 1e-9));
    });
    test('tête inclinée -> roulis positif', () {
      // œil gauche plus bas que l'œil droit (y vers le bas).
      final double roll =
          AnchorMath.rollRadians(const Offset(100, 200), const Offset(300, 400));
      expect(roll, closeTo(math.atan2(200, 200), 1e-9)); // 45°
      expect(roll, greaterThan(0));
    });
  });
}
