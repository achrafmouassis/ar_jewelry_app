import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show AssetManifest, rootBundle;

import '../models/jewelry_model.dart';
import '../models/jewelry_type.dart';

/// Service de découverte des types et bijoux depuis les assets.
///
/// Flutter ne permet pas de lister un dossier d'assets à l'exécution :
/// on utilise donc le manifeste généré par le build pour énumérer tous
/// les fichiers déclarés sous assets/jewelry/.
///
/// NB : depuis Flutter 3.16+, `AssetManifest.json` n'est plus généré
/// (seul `AssetManifest.bin` l'est). On passe donc par l'API typée
/// `AssetManifest.loadFromAssetBundle`, qui décode le binaire pour nous.
class JewelryService {
  JewelryService._();
  static final JewelryService instance = JewelryService._();

  // Cache : on ne lit le manifeste qu'une seule fois pour éviter les
  // lectures répétées coûteuses.
  Map<String, List<String>>? _filesByFolder;

  // Tous les .glb du manifeste (variantes AR incluses) pour les lookups.
  Set<String>? _allGlbPaths;

  /// Suffixe des variantes AR : même modèle mais moitié arrière supprimée
  /// (celle cachée par le doigt/poignet). À travers les ajours on voit alors
  /// la peau de l'utilisateur → le bijou semble vraiment enroulé autour de
  /// la main au lieu d'être plaqué dessus. Générées par le script
  /// tool/glb_cut_back.dart ; exclues de la liste des bijoux.
  static const String arHalfSuffix = '_arhalf.glb';

  /// Métadonnées d'affichage par dossier connu.
  /// Si un nouveau dossier apparaît dans assets/, on l'affiche avec
  /// des valeurs par défaut.
  static const Map<String, ({String label, IconData icon})> _meta =
      <String, ({String label, IconData icon})>{
    'bagues':    (label: 'Bagues',     icon: Icons.circle_outlined),
    'colliers':  (label: 'Colliers',   icon: Icons.favorite_border),
    'boucles':   (label: 'Boucles',    icon: Icons.brightness_2_outlined),
    'bracelets': (label: 'Bracelets',  icon: Icons.watch_outlined),
    'perles':    (label: 'Perles',     icon: Icons.blur_on),
  };

  /// Charge le manifeste si nécessaire et regroupe les fichiers .glb
  /// par sous-dossier de assets/jewelry/.
  Future<void> _ensureLoaded() async {
    if (_filesByFolder != null) return;

    final AssetManifest manifest =
        await AssetManifest.loadFromAssetBundle(rootBundle);

    final Map<String, List<String>> grouped = <String, List<String>>{};
    final Set<String> all = <String>{};
    for (final String path in manifest.listAssets()) {
      if (!path.startsWith('assets/jewelry/')) continue;
      if (!path.toLowerCase().endsWith('.glb')) continue;
      all.add(path);

      // Les variantes AR ne sont pas des bijoux à lister.
      if (path.toLowerCase().endsWith(arHalfSuffix)) continue;

      // assets/jewelry/<folder>/<file>.glb
      final List<String> parts = path.split('/');
      if (parts.length < 4) continue;
      final String folder = parts[2];
      grouped.putIfAbsent(folder, () => <String>[]).add(path);
    }
    _allGlbPaths = all;

    // Tri stable pour un affichage déterministe.
    for (final List<String> list in grouped.values) {
      list.sort();
    }
    _filesByFolder = grouped;
  }

  /// Retourne la liste des types disponibles (un par sous-dossier non vide).
  Future<List<JewelryType>> loadTypes() async {
    await _ensureLoaded();
    final List<JewelryType> result = <JewelryType>[];
    final List<String> folders = _filesByFolder!.keys.toList()..sort();
    for (final String folder in folders) {
      if ((_filesByFolder![folder] ?? const <String>[]).isEmpty) continue;
      final m = _meta[folder];
      result.add(JewelryType(
        folder: folder,
        displayName: m?.label ?? _prettify(folder),
        icon: m?.icon ?? Icons.diamond_outlined,
      ));
    }
    return result;
  }

  /// Retourne tous les bijoux d'un type donné.
  Future<List<JewelryModel>> loadJewelryByType(JewelryType type) async {
    await _ensureLoaded();
    final List<String> paths =
        _filesByFolder![type.folder] ?? const <String>[];
    return paths
        .map((String p) => JewelryModel(
              name: _fileNameToTitle(p),
              assetPath: p,
              type: type,
            ))
        .toList(growable: false);
  }

  /// Retourne le chemin du modèle à utiliser dans la vue AR : la variante
  /// "moitié avant" si elle existe, sinon le modèle complet.
  Future<String> arAssetFor(String assetPath) async {
    await _ensureLoaded();
    final String half =
        assetPath.replaceFirst(RegExp(r'\.glb$', caseSensitive: false),
            arHalfSuffix);
    return (_allGlbPaths?.contains(half) ?? false) ? half : assetPath;
  }

  // --- helpers privés -----------------------------------------------------

  String _prettify(String folder) =>
      folder.isEmpty ? folder : folder[0].toUpperCase() + folder.substring(1);

  String _fileNameToTitle(String assetPath) {
    final String file = assetPath.split('/').last;
    final String noExt =
        file.toLowerCase().endsWith('.glb') ? file.substring(0, file.length - 4) : file;
    final String spaced = noExt.replaceAll('_', ' ').replaceAll('-', ' ');
    return spaced.isEmpty ? noExt : spaced[0].toUpperCase() + spaced.substring(1);
  }
}
