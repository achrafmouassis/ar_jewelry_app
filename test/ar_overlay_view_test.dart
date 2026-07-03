import 'package:ar_jewelry_app/widgets/ar_overlay_view.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AROverlayView.assetToSceneUrl', () {
    test('chemin simple -> URL relative depuis assets/web/', () {
      expect(
        AROverlayView.assetToSceneUrl('assets/jewelry/bagues/anneau_or.glb'),
        '../jewelry/bagues/anneau_or.glb',
      );
    });

    test('espaces percent-encodés (fichier déposé à la main)', () {
      expect(
        AROverlayView.assetToSceneUrl('assets/jewelry/bagues/anneau or.glb'),
        '../jewelry/bagues/anneau%20or.glb',
      );
    });

    test('caractères réservés (#, ?) encodés — sinon tronqués par le fetch',
        () {
      expect(
        AROverlayView.assetToSceneUrl('assets/jewelry/bagues/ring#1.glb'),
        '../jewelry/bagues/ring%231.glb',
      );
      expect(
        AROverlayView.assetToSceneUrl('assets/jewelry/bagues/ring?.glb'),
        '../jewelry/bagues/ring%3F.glb',
      );
    });

    test('accents encodés en UTF-8', () {
      expect(
        AROverlayView.assetToSceneUrl('assets/jewelry/colliers/émeraude.glb'),
        '../jewelry/colliers/%C3%A9meraude.glb',
      );
    });

    test('les séparateurs de dossiers ne sont pas encodés', () {
      final String url = AROverlayView.assetToSceneUrl(
          'assets/jewelry/perles/sous dossier/perle.glb');
      expect(url, '../jewelry/perles/sous%20dossier/perle.glb');
    });

    test('chemin sans préfixe assets/ conservé tel quel (encodé)', () {
      expect(
        AROverlayView.assetToSceneUrl('jewelry/bagues/x.glb'),
        '../jewelry/bagues/x.glb',
      );
    });
  });
}
