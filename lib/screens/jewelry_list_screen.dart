import 'package:flutter/material.dart';

import '../models/jewelry_model.dart';
import '../models/jewelry_type.dart';
import '../services/jewelry_service.dart';
import '../widgets/jewelry_card.dart';
import '../widgets/loading_indicator.dart';
import 'ar_view_screen.dart';

/// Écran 2 : liste des .glb pour un type donné.
class JewelryListScreen extends StatefulWidget {
  final JewelryType type;
  const JewelryListScreen({super.key, required this.type});

  @override
  State<JewelryListScreen> createState() => _JewelryListScreenState();
}

class _JewelryListScreenState extends State<JewelryListScreen> {
  late final Future<List<JewelryModel>> _future;

  @override
  void initState() {
    super.initState();
    _future = JewelryService.instance.loadJewelryByType(widget.type);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.type.displayName),
      ),
      body: FutureBuilder<List<JewelryModel>>(
        future: _future,
        builder: (BuildContext ctx, AsyncSnapshot<List<JewelryModel>> snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const CenteredLoader(label: 'Chargement des bijoux…');
          }
          if (snap.hasError) {
            return EmptyState(
              icon: Icons.error_outline,
              message: 'Erreur : ${snap.error}',
            );
          }
          final List<JewelryModel> items =
              snap.data ?? const <JewelryModel>[];
          if (items.isEmpty) {
            return EmptyState(
              icon: Icons.inventory_2_outlined,
              message:
                  'Aucun bijou dans ce dossier.\nAjoute un .glb dans assets/jewelry/${widget.type.folder}/',
            );
          }
          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (BuildContext _, int i) {
              final JewelryModel j = items[i];
              return JewelryListTile(
                key: ValueKey<String>(j.assetPath),
                name: j.name,
                onTap: () => _openAR(j),
              );
            },
          );
        },
      ),
    );
  }

  void _openAR(JewelryModel jewelry) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => ARViewScreen(jewelry: jewelry),
    ));
  }
}
