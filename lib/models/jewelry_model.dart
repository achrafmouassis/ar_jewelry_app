import 'jewelry_type.dart';

/// Représente un bijou unique (un fichier .glb).
///
/// [assetPath] est le chemin complet déclarable dans pubspec.yaml,
/// ex: "assets/jewelry/bagues/anneau_or.glb".
class JewelryModel {
  final String name;       // nom affiché (dérivé du nom de fichier)
  final String assetPath;  // chemin complet de l'asset
  final JewelryType type;  // type parent

  const JewelryModel({
    required this.name,
    required this.assetPath,
    required this.type,
  });
}
