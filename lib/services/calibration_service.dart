import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

/// Calibration morphologique de l'utilisateur.
///
/// **Pourquoi.** Les world landmarks de MediaPipe sont métriques, mais ils
/// sont l'ajustement d'un modèle de main *générique* : pour une main donnée
/// ils sont systématiquement biaisés, de l'ordre de ±15 %. Sur un bracelet ça
/// passe inaperçu, sur une bague ça se voit immédiatement — c'est précisément
/// pour ça que les essayages commerciaux (Perfect Corp / Cartier) découplent
/// la MESURE du SUIVI : l'utilisateur pose son doigt sur l'écran, ajuste deux
/// règles, et cette mesure absolue en millimètres pilote ensuite la taille du
/// bijou. Le suivi temps réel ne sert plus qu'à la pose.
///
/// **Ce qu'on en fait.** On ne remplace pas la mesure du pipeline : on la
/// corrige. La calibration produit un simple facteur multiplicatif
/// [ringCorrection] / [braceletCorrection] (1.0 = morphologie de référence),
/// passé au rendu natif. Aucune calibration = facteur 1.0 = comportement
/// actuel inchangé.
@immutable
class Calibration {
  /// Largeur du doigt annulaire à la base, en millimètres.
  final double? fingerMm;

  /// Largeur du poignet (médio-latérale, la plus grande), en millimètres.
  final double? wristMm;

  const Calibration({this.fingerMm, this.wristMm});

  static const Calibration empty = Calibration();

  bool get isEmpty => fingerMm == null && wristMm == null;

  /// Facteur d'échelle de la bague : 1.0 si non calibré.
  double get ringCorrection =>
      fingerMm == null ? 1.0 : (fingerMm! / kReferenceFingerMm).clamp(0.7, 1.4);

  /// Facteur d'échelle du bracelet : 1.0 si non calibré.
  double get braceletCorrection =>
      wristMm == null ? 1.0 : (wristMm! / kReferenceWristMm).clamp(0.7, 1.4);

  /// Taille de bague (norme française = circonférence en mm) déduite de la
  /// largeur mesurée, en assimilant le doigt à un cercle. Approximation
  /// assumée : le doigt est légèrement ovale, la norme du métier tolère
  /// ±0,5 taille — c'est la précision qu'on peut honnêtement annoncer.
  int? get frenchRingSize {
    final double? w = fingerMm;
    if (w == null) return null;
    return (w * 3.14159).round();
  }

  Calibration copyWith({double? fingerMm, double? wristMm}) => Calibration(
        fingerMm: fingerMm ?? this.fingerMm,
        wristMm: wristMm ?? this.wristMm,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        if (fingerMm != null) 'fingerMm': fingerMm,
        if (wristMm != null) 'wristMm': wristMm,
      };

  static Calibration fromJson(Map<String, dynamic> j) => Calibration(
        fingerMm: (j['fingerMm'] as num?)?.toDouble(),
        wristMm: (j['wristMm'] as num?)?.toDouble(),
      );

  /// Morphologie de référence : celle pour laquelle les constantes du rendu
  /// natif (kRingPerHand, kWristWidthPerHand) ont été réglées. Un utilisateur
  /// à ces mesures obtient un facteur 1.0, donc exactement le rendu actuel —
  /// c'est ce qui rend la calibration purement additive.
  static const double kReferenceFingerMm = 17.0;
  static const double kReferenceWristMm = 58.0;
}

/// Persistance de la calibration + accès à la densité physique de l'écran.
class CalibrationService {
  static const MethodChannel _device = MethodChannel('ar_jewelry/device');
  static const String _fileName = 'calibration.json';

  static Calibration _cached = Calibration.empty;
  static bool _loaded = false;

  /// Dernière calibration connue (synchrone, pour le rendu).
  static Calibration get current => _cached;

  static Future<File> _file() async {
    final Directory dir = await getApplicationSupportDirectory();
    return File('${dir.path}/$_fileName');
  }

  static Future<Calibration> load() async {
    if (_loaded) return _cached;
    try {
      final File f = await _file();
      if (await f.exists()) {
        final Object? j = jsonDecode(await f.readAsString());
        if (j is Map<String, dynamic>) _cached = Calibration.fromJson(j);
      }
    } catch (e) {
      debugPrint('Calibration illisible: $e');
    }
    _loaded = true;
    return _cached;
  }

  static Future<void> save(Calibration c) async {
    _cached = c;
    _loaded = true;
    try {
      final File f = await _file();
      await f.writeAsString(jsonEncode(c.toJson()));
    } catch (e) {
      debugPrint('Calibration non enregistrée: $e');
    }
  }

  static Future<void> clear() => save(Calibration.empty);

  /// Millimètres par pixel **logique** Flutter, mesuré sur la dalle réelle.
  ///
  /// Renvoie `null` si la densité physique n'est pas exploitable — auquel cas
  /// il ne faut PAS proposer la calibration : une règle à l'écran fondée sur
  /// une densité fausse donne une mesure fausse avec l'air d'être précise,
  /// ce qui est pire que pas de calibration du tout.
  static Future<double?> mmPerLogicalPixel(double devicePixelRatio) async {
    try {
      final Map<Object?, Object?>? m =
          await _device.invokeMapMethod<Object?, Object?>('screenDpi');
      final double? xdpi = (m?['xdpi'] as num?)?.toDouble();
      if (xdpi == null || xdpi < 80 || xdpi > 900) return null;
      // px logique → px physique → pouces → mm.
      return devicePixelRatio / xdpi * 25.4;
    } catch (e) {
      debugPrint('Densité écran illisible: $e');
      return null;
    }
  }
}
