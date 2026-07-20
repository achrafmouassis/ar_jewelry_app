import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';

import '../models/jewelry_model.dart';
import '../models/jewelry_type.dart';
import '../services/jewelry_service.dart';
import '../widgets/jewelry_card.dart';
import '../widgets/loading_indicator.dart';
import 'jewelry_list_screen.dart';
import 'native_ar_test_screen.dart';

/// Écran 1 : grille des types de bijoux découverts dans assets/jewelry/.
class JewelryTypesScreen extends StatefulWidget {
  const JewelryTypesScreen({super.key});

  @override
  State<JewelryTypesScreen> createState() => _JewelryTypesScreenState();
}

class _JewelryTypesScreenState extends State<JewelryTypesScreen> {
  // Future stocké en state pour éviter de relancer le chargement à chaque rebuild.
  late final Future<List<JewelryType>> _future;

  @override
  void initState() {
    super.initState();
    _future = JewelryService.instance.loadTypes();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Essayage de bijoux',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      // Bouton de test de la vue AR native (debug uniquement, retiré en release).
      floatingActionButton: kDebugMode
          ? FloatingActionButton.extended(
              heroTag: 'fab-native-ar',
              onPressed: _openNativeArTest,
              icon: const Icon(Icons.view_in_ar),
              label: const Text('AR natif'),
            )
          : null,
      body: FutureBuilder<List<JewelryType>>(
        future: _future,
        builder: (BuildContext ctx, AsyncSnapshot<List<JewelryType>> snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const CenteredLoader(label: 'Chargement des types…');
          }
          if (snap.hasError) {
            return EmptyState(
              icon: Icons.error_outline,
              message: 'Erreur de chargement : ${snap.error}',
            );
          }
          final List<JewelryType> types = snap.data ?? const <JewelryType>[];
          if (types.isEmpty) {
            return const EmptyState(
              icon: Icons.folder_open,
              message:
                  'Aucun type trouvé.\nAjoute des fichiers .glb dans assets/jewelry/<type>/',
            );
          }

          return Padding(
            padding: const EdgeInsets.all(16),
            child: GridView.builder(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                crossAxisSpacing: 14,
                mainAxisSpacing: 14,
                childAspectRatio: 0.95,
              ),
              itemCount: types.length,
              itemBuilder: (BuildContext _, int i) {
                final JewelryType t = types[i];
                return JewelryTypeCard(
                  // key explicite : préserve l'état/animation lors de changements de liste.
                  key: ValueKey<String>(t.folder),
                  label: t.displayName,
                  icon: t.icon,
                  onTap: () => _openList(t),
                );
              },
            ),
          );
        },
      ),
    );
  }

  void _openList(JewelryType type) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => JewelryListScreen(type: type),
    ));
  }

  /// Ouvre l'écran de test de la vue AR native (étape B) après avoir laissé
  /// l'utilisateur choisir le type de bijou (bracelet → ancre poignet,
  /// bague → ancre annulaire).
  Future<void> _openNativeArTest() async {
    final String? folder = await showModalBottomSheet<String>(
      context: context,
      builder: (BuildContext ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'Essayage AR natif',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.watch),
              title: const Text('Bracelet'),
              subtitle: const Text('Suivi du poignet'),
              onTap: () => Navigator.of(ctx).pop('bracelets'),
            ),
            ListTile(
              leading: const Icon(Icons.circle_outlined),
              title: const Text('Bague'),
              subtitle: const Text('Suivi de l\'annulaire'),
              onTap: () => Navigator.of(ctx).pop('bagues'),
            ),
            ListTile(
              leading: const Icon(Icons.diamond_outlined),
              title: const Text('Collier'),
              subtitle: const Text('Suivi du cou (épaules + tête)'),
              onTap: () => Navigator.of(ctx).pop('colliers'),
            ),
          ],
        ),
      ),
    );
    if (folder == null || !mounted) return;

    final List<JewelryType> types = await JewelryService.instance.loadTypes();
    if (types.isEmpty || !mounted) return;
    final JewelryType type = types.firstWhere(
      (JewelryType t) => t.folder == folder,
      orElse: () => types.first,
    );
    final List<JewelryModel> items =
        await JewelryService.instance.loadJewelryByType(type);
    if (items.isEmpty || !mounted) return;
    // Occlusion réelle (B.4) : la vue native rend le modèle COMPLET et masque
    // sa moitié arrière avec un cylindre occluseur de profondeur qui suit le
    // bras/doigt — les variantes _arhalf ne servent plus ici.
    final String assetPath = items.first.assetPath;
    final String anchor = switch (folder) {
      'bracelets' => 'wrist',
      'colliers' => 'neck',
      _ => 'ringFinger',
    };
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => NativeArTestScreen(
        assetPath: assetPath,
        anchor: anchor,
      ),
    ));
  }
}
