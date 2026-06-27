import 'package:flutter/material.dart';

/// Représente un type/catégorie de bijou (bagues, colliers, etc.).
///
/// Le [folder] correspond exactement au nom du sous-dossier dans
/// assets/jewelry/ (ex: "bagues"). Le [displayName] est la version
/// présentable à l'utilisateur (ex: "Bagues").
class JewelryType {
  final String folder;
  final String displayName;
  final IconData icon;

  const JewelryType({
    required this.folder,
    required this.displayName,
    required this.icon,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is JewelryType && other.folder == folder);

  @override
  int get hashCode => folder.hashCode;
}
