import 'package:flutter/material.dart';

import '../models/jewelry_type.dart';
import '../services/jewelry_service.dart';
import '../widgets/jewelry_card.dart';
import '../widgets/loading_indicator.dart';
import 'jewelry_list_screen.dart';

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
}
