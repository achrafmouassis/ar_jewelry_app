import 'package:ar_jewelry_app/services/jewelry_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('JewelryService.groupAssetPaths', () {
    test('groupe les .glb par sous-dossier et ignore le reste', () {
      final Map<String, List<String>> g = JewelryService.groupAssetPaths(<String>[
        'assets/jewelry/bagues/anneau.glb',
        'assets/jewelry/bagues/diamant.glb',
        'assets/jewelry/colliers/perle.glb',
        'assets/web/ar_scene.html', // ignoré (pas jewelry)
        'assets/jewelry/bagues/notice.txt', // ignoré (pas .glb)
        'assets/jewelry/boucles/', // ignoré (pas un fichier .glb)
      ]);

      expect(g.keys.toSet(), <String>{'bagues', 'colliers'});
      expect(g['bagues'], hasLength(2));
      expect(g['colliers'], <String>['assets/jewelry/colliers/perle.glb']);
    });

    test('tri stable des fichiers dans chaque dossier', () {
      final Map<String, List<String>> g = JewelryService.groupAssetPaths(<String>[
        'assets/jewelry/bagues/z.glb',
        'assets/jewelry/bagues/a.glb',
        'assets/jewelry/bagues/m.glb',
      ]);
      expect(g['bagues'], <String>[
        'assets/jewelry/bagues/a.glb',
        'assets/jewelry/bagues/m.glb',
        'assets/jewelry/bagues/z.glb',
      ]);
    });

    test('extension insensible à la casse', () {
      final Map<String, List<String>> g = JewelryService.groupAssetPaths(<String>[
        'assets/jewelry/bagues/UPPER.GLB',
      ]);
      expect(g['bagues'], hasLength(1));
    });

    test('dossier vide -> absent du résultat', () {
      final Map<String, List<String>> g = JewelryService.groupAssetPaths(<String>[
        'assets/jewelry/bagues/x.glb',
      ]);
      expect(g.containsKey('colliers'), isFalse);
    });

    test('entrée vide -> map vide', () {
      expect(JewelryService.groupAssetPaths(const <String>[]), isEmpty);
    });
  });

  group('JewelryService.fileNameToTitle', () {
    test('remplace underscores/tirets et capitalise', () {
      expect(
        JewelryService.fileNameToTitle('assets/jewelry/bagues/anneau_or.glb'),
        'Anneau or',
      );
      expect(
        JewelryService.fileNameToTitle('assets/jewelry/colliers/collier-perle.glb'),
        'Collier perle',
      );
    });

    test('retire l\'extension .glb insensible à la casse', () {
      expect(
        JewelryService.fileNameToTitle('assets/jewelry/bagues/Diamant.GLB'),
        'Diamant',
      );
    });
  });
}
