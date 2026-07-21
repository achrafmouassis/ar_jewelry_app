import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

/// Analyse géométrique d'un bijou GLB → `<nom>.fit.json` posé à côté de lui.
///
/// POURQUOI CET OUTIL. Toutes les grandeurs qui relient un bijou à un membre
/// (axe du trou, rayon intérieur, centre du trou, hauteur de bande, position
/// de l'ouverture) étaient des CONSTANTES écrites à la main dans
/// NativeArView.kt, mesurées une fois par un script jetable. Trois défauts :
///  - ajouter un bijou obligeait à refaire les mesures à la main ;
///  - une erreur passait inaperçue (kModelInnerRadius a vécu plusieurs
///    commits à 0.652 alors que la bonne valeur est 0.89) ;
///  - le commentaire et la valeur pouvaient diverger sans que rien ne le dise
///    (l'occluseur de bague annonçait un trou de 0.242 pour un trou réel de
///    0.556, donc un cylindre de profondeur qui mangeait la bague).
/// Ici la mesure est reproductible, versionnée à côté de l'asset, et le
/// runtime la lit au lieu de la deviner.
///
/// MÉTHODE. Le trou d'un bijou n'est pas « le point le plus proche de
/// l'origine » — c'est le plus grand CYLINDRE VIDE qui traverse la pièce de
/// part en part. On le cherche sur chacun des trois axes du modèle, en
/// optimisant la position du cylindre dans le plan perpendiculaire, et en
/// exigeant qu'il soit ENCLOS par la matière (sinon on mesurerait un vide de
/// coin, pas un trou). L'axe retenu est celui qui donne le plus gros cylindre.
///
/// Usage :
///   dart run tool/fit_analyzer.dart <fichier.glb | dossier> [...]
///   dart run tool/fit_analyzer.dart assets/jewelry

// ---------------------------------------------------------------------------
// Lecture GLB
// ---------------------------------------------------------------------------

const int _kGlbMagic = 0x46546C67; // 'glTF'
const int _kChunkJson = 0x4E4F534A;
const int _kChunkBin = 0x004E4942;

const Map<int, int> _kCompSize = <int, int>{
  5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4,
};
// Diviseur de dé-quantification des accesseurs `normalized` (spec glTF).
const Map<int, double> _kCompMax = <int, double>{
  5120: 127, 5121: 255, 5122: 32767, 5123: 65535,
};
const Map<String, int> _kNumComp = <String, int>{
  'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4,
};

class _Glb {
  _Glb(this.json, this.bin);
  final Map<String, dynamic> json;
  final ByteData bin;
}

_Glb _readGlb(String path) {
  final Uint8List bytes = File(path).readAsBytesSync();
  final ByteData d = ByteData.sublistView(bytes);
  if (d.getUint32(0, Endian.little) != _kGlbMagic) {
    throw FormatException('$path : ce n\'est pas un GLB');
  }
  int off = 12;
  Map<String, dynamic>? js;
  ByteData? bin;
  while (off + 8 <= bytes.length) {
    final int len = d.getUint32(off, Endian.little);
    final int type = d.getUint32(off + 4, Endian.little);
    off += 8;
    if (type == _kChunkJson) {
      js = jsonDecode(utf8.decode(bytes.sublist(off, off + len)))
          as Map<String, dynamic>;
    } else if (type == _kChunkBin) {
      bin = ByteData.sublistView(bytes, off, off + len);
    }
    off += len;
  }
  if (js == null || bin == null) {
    throw FormatException('$path : chunk JSON ou BIN manquant');
  }
  return _Glb(js, bin);
}

/// Lit un accesseur en doubles, en appliquant la dé-quantification des
/// accesseurs `normalized` (KHR_mesh_quantization : les modèles Meshy stockent
/// les positions en SHORT normalisés, pas en float).
Float64List _readAccessor(_Glb g, int index) {
  final Map<String, dynamic> acc =
      (g.json['accessors'] as List)[index] as Map<String, dynamic>;
  final int compType = acc['componentType'] as int;
  final int n = _kNumComp[acc['type'] as String]!;
  final int count = acc['count'] as int;
  final int sz = _kCompSize[compType]!;
  final Map<String, dynamic> bv =
      (g.json['bufferViews'] as List)[acc['bufferView'] as int]
          as Map<String, dynamic>;
  final int base = ((bv['byteOffset'] ?? 0) as int) +
      ((acc['byteOffset'] ?? 0) as int);
  final int stride = (bv['byteStride'] ?? sz * n) as int;
  final bool norm = acc['normalized'] == true;
  final Float64List out = Float64List(count * n);
  for (int i = 0; i < count; i++) {
    final int o = base + i * stride;
    for (int k = 0; k < n; k++) {
      double v;
      switch (compType) {
        case 5126:
          v = g.bin.getFloat32(o + k * 4, Endian.little);
        case 5125:
          v = g.bin.getUint32(o + k * 4, Endian.little).toDouble();
        case 5123:
          v = g.bin.getUint16(o + k * 2, Endian.little).toDouble();
        case 5122:
          v = g.bin.getInt16(o + k * 2, Endian.little).toDouble();
        case 5121:
          v = g.bin.getUint8(o + k).toDouble();
        default:
          v = g.bin.getInt8(o + k).toDouble();
      }
      if (norm && compType != 5126) {
        final double m = _kCompMax[compType]!;
        // Les types signés se dé-quantifient avec un plancher à -1.
        v = (compType == 5120 || compType == 5122)
            ? math.max(v / m, -1.0)
            : v / m;
      }
      out[i * n + k] = v;
    }
  }
  return out;
}

// --- matrices 4x4 colonne-major ---------------------------------------------

Float64List _identity() => Float64List.fromList(<double>[
      1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
    ]);

Float64List _mul(Float64List a, Float64List b) {
  final Float64List o = Float64List(16);
  for (int c = 0; c < 4; c++) {
    for (int r = 0; r < 4; r++) {
      double s = 0;
      for (int k = 0; k < 4; k++) {
        s += a[k * 4 + r] * b[c * 4 + k];
      }
      o[c * 4 + r] = s;
    }
  }
  return o;
}

Float64List _nodeMatrix(Map<String, dynamic> node) {
  if (node['matrix'] != null) {
    return Float64List.fromList(
        (node['matrix'] as List).map((dynamic e) => (e as num).toDouble())
            .toList());
  }
  final List<double> t = ((node['translation'] ?? <num>[0, 0, 0]) as List)
      .map((dynamic e) => (e as num).toDouble()).toList();
  final List<double> q = ((node['rotation'] ?? <num>[0, 0, 0, 1]) as List)
      .map((dynamic e) => (e as num).toDouble()).toList();
  final List<double> s = ((node['scale'] ?? <num>[1, 1, 1]) as List)
      .map((dynamic e) => (e as num).toDouble()).toList();
  final double x = q[0], y = q[1], z = q[2], w = q[3];
  final Float64List m = Float64List.fromList(<double>[
    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
    0, 0, 0, 1,
  ]);
  for (int c = 0; c < 3; c++) {
    for (int r = 0; r < 3; r++) {
      m[c * 4 + r] *= s[c];
    }
  }
  m[12] = t[0];
  m[13] = t[1];
  m[14] = t[2];
  return m;
}

/// Positions de tous les sommets, transforms de nœuds appliquées (un GLB
/// quantifié porte l'échelle inverse sur le nœud : l'ignorer fausse tout).
Float64List _collectPositions(_Glb g) {
  final List<double> acc = <double>[];
  final List<dynamic> nodes = g.json['nodes'] as List;
  final int sceneIndex = (g.json['scene'] ?? 0) as int;
  final List<dynamic> roots =
      ((g.json['scenes'] as List)[sceneIndex] as Map<String, dynamic>)['nodes']
          as List;

  void walk(int ni, Float64List parent) {
    final Map<String, dynamic> node = nodes[ni] as Map<String, dynamic>;
    final Float64List m = _mul(parent, _nodeMatrix(node));
    if (node['mesh'] != null) {
      final Map<String, dynamic> mesh =
          (g.json['meshes'] as List)[node['mesh'] as int]
              as Map<String, dynamic>;
      for (final dynamic p in mesh['primitives'] as List) {
        final Map<String, dynamic> prim = p as Map<String, dynamic>;
        final int? pos =
            (prim['attributes'] as Map<String, dynamic>)['POSITION'] as int?;
        if (pos == null) continue;
        final Float64List v = _readAccessor(g, pos);
        for (int i = 0; i + 2 < v.length; i += 3) {
          final double x = v[i], y = v[i + 1], z = v[i + 2];
          acc.add(m[0] * x + m[4] * y + m[8] * z + m[12]);
          acc.add(m[1] * x + m[5] * y + m[9] * z + m[13]);
          acc.add(m[2] * x + m[6] * y + m[10] * z + m[14]);
        }
      }
    }
    for (final dynamic c in (node['children'] ?? <dynamic>[]) as List) {
      walk(c as int, m);
    }
  }

  for (final dynamic r in roots) {
    walk(r as int, _identity());
  }
  return Float64List.fromList(acc);
}

// ---------------------------------------------------------------------------
// Recherche du trou
// ---------------------------------------------------------------------------

class _Hole {
  _Hole(this.radius, this.cb, this.cc);
  final double radius; // rayon du cylindre vide traversant
  final double cb; // centre, 1re coordonnée perpendiculaire
  final double cc; // centre, 2e coordonnée perpendiculaire
}

const int _kEnclosureSectors = 16;

/// Plus grand cylindre vide traversant, d'axe [a], centre optimisé dans le
/// plan ([b], [c]). Le centre doit être ENCLOS : les 16 secteurs angulaires
/// autour de lui doivent tous contenir de la matière, faute de quoi on
/// mesurerait un vide extérieur à la pièce au lieu d'un trou.
_Hole _findHole(Float64List pts, int a, int b, int c, List<double> lo,
    List<double> hi) {
  // Sous-échantillonnage : la densité des maillages Meshy est très supérieure
  // à ce qu'exige la recherche du centre. La mesure finale repassera sur la
  // totalité des sommets.
  final int n = pts.length ~/ 3;
  final int step = math.max(1, n ~/ 30000);
  final List<double> pb = <double>[];
  final List<double> pc = <double>[];
  for (int i = 0; i < n; i += step) {
    pb.add(pts[i * 3 + b]);
    pc.add(pts[i * 3 + c]);
  }
  final int m = pb.length;

  // Distance au point le plus proche ET test d'enclosure, en une seule passe.
  _Hole? evaluate(double cb, double cc) {
    double best = double.infinity;
    final List<bool> seen = List<bool>.filled(_kEnclosureSectors, false);
    int seenCount = 0;
    for (int i = 0; i < m; i++) {
      final double dx = pb[i] - cb, dy = pc[i] - cc;
      final double d2 = dx * dx + dy * dy;
      if (d2 < best) best = d2;
      final double ang = math.atan2(dy, dx);
      int s = ((ang + math.pi) / (2 * math.pi) * _kEnclosureSectors).floor();
      if (s >= _kEnclosureSectors) s = _kEnclosureSectors - 1;
      if (s < 0) s = 0;
      if (!seen[s]) {
        seen[s] = true;
        seenCount++;
      }
    }
    if (seenCount < _kEnclosureSectors) return null; // vide de coin, pas un trou
    return _Hole(math.sqrt(best), cb, cc);
  }

  _Hole best = _Hole(-1, 0, 0);
  const int grid = 40;
  for (int i = 0; i <= grid; i++) {
    for (int k = 0; k <= grid; k++) {
      final double cb = lo[0] + (hi[0] - lo[0]) * i / grid;
      final double cc = lo[1] + (hi[1] - lo[1]) * k / grid;
      final _Hole? h = evaluate(cb, cc);
      if (h != null && h.radius > best.radius) best = h;
    }
  }
  if (best.radius < 0) return best;
  // Raffinement local, fenêtre divisée par 4 à chaque passe.
  double win = math.max(hi[0] - lo[0], hi[1] - lo[1]) / grid;
  for (int pass = 0; pass < 4; pass++) {
    final _Hole from = best;
    for (int i = -5; i <= 5; i++) {
      for (int k = -5; k <= 5; k++) {
        final _Hole? h =
            evaluate(from.cb + win * i / 5, from.cc + win * k / 5);
        if (h != null && h.radius > best.radius) best = h;
      }
    }
    win /= 4;
  }
  // Mesure exacte sur TOUS les sommets, au centre retenu.
  double exact = double.infinity;
  for (int i = 0; i < n; i++) {
    final double dx = pts[i * 3 + b] - best.cb;
    final double dy = pts[i * 3 + c] - best.cc;
    final double d2 = dx * dx + dy * dy;
    if (d2 < exact) exact = d2;
  }
  return _Hole(math.sqrt(exact), best.cb, best.cc);
}

/// Rayon libre dans la direction cardinale ±[dir] du plan perpendiculaire :
/// c'est cette grandeur-là, et non le rayon minimal global, qu'il faut
/// confronter à une largeur de membre mesurée dans la MÊME direction.
double _radiusToward(Float64List pts, int b, int c, double cb, double cc,
    int dir, double tolDeg) {
  final int n = pts.length ~/ 3;
  final double target = dir * math.pi / 2; // ±90° = axe c ; 0/180° = axe b
  double best = double.infinity;
  for (int i = 0; i < n; i++) {
    final double dx = pts[i * 3 + b] - cb, dy = pts[i * 3 + c] - cc;
    double ang = math.atan2(dy, dx) - target;
    while (ang > math.pi) {
      ang -= 2 * math.pi;
    }
    while (ang < -math.pi) {
      ang += 2 * math.pi;
    }
    if (ang.abs() > tolDeg * math.pi / 180) continue;
    final double r = math.sqrt(dx * dx + dy * dy);
    if (r < best) best = r;
  }
  return best.isFinite ? best : -1;
}

const List<String> _kAxisName = <String>['X', 'Y', 'Z'];

// ---------------------------------------------------------------------------

Map<String, dynamic> analyze(String path) {
  final _Glb g = _readGlb(path);
  final Float64List pts = _collectPositions(g);
  final int n = pts.length ~/ 3;
  if (n == 0) throw FormatException('$path : aucun sommet');

  final List<double> mn = <double>[1e30, 1e30, 1e30];
  final List<double> mx = <double>[-1e30, -1e30, -1e30];
  for (int i = 0; i < n; i++) {
    for (int k = 0; k < 3; k++) {
      final double v = pts[i * 3 + k];
      if (v < mn[k]) mn[k] = v;
      if (v > mx[k]) mx[k] = v;
    }
  }

  // Le trou est cherché sur les trois axes ; on garde le plus large.
  int bestAxis = -1;
  _Hole bestHole = _Hole(-1, 0, 0);
  for (int a = 0; a < 3; a++) {
    final int b = (a + 1) % 3, c = (a + 2) % 3;
    final _Hole h = _findHole(pts, a, b, c, <double>[mn[b], mn[c]],
        <double>[mx[b], mx[c]]);
    if (h.radius > bestHole.radius) {
      bestHole = h;
      bestAxis = a;
    }
  }
  if (bestAxis < 0) {
    throw StateError('$path : aucun trou traversant enclos détecté — la pièce '
        'est pleine, aucun membre ne peut y passer');
  }
  final int a = bestAxis, b = (a + 1) % 3, c = (a + 2) % 3;

  // Profil angulaire : ovalité (un poignet est ovale, pas rond) et rayons
  // cardinaux, qui sont ceux que le runtime confronte aux mesures de membre.
  const int nb = 24;
  final List<double> sector = List<double>.filled(nb, double.infinity);
  for (int i = 0; i < n; i++) {
    final double dx = pts[i * 3 + b] - bestHole.cb;
    final double dy = pts[i * 3 + c] - bestHole.cc;
    final double ang = math.atan2(dy, dx);
    int s = ((ang + math.pi) / (2 * math.pi) * nb).floor();
    if (s >= nb) s = nb - 1;
    if (s < 0) s = 0;
    final double r = math.sqrt(dx * dx + dy * dy);
    if (r < sector[s]) sector[s] = r;
  }
  final List<double> finite = sector.where((double v) => v.isFinite).toList();
  finite.sort();
  final double rMin = finite.first, rMax = finite.last;

  // Rayons cardinaux : le long de l'axe b et le long de l'axe c.
  final double rTowardB = math.min(
      _radiusToward(pts, b, c, bestHole.cb, bestHole.cc, 0, 20),
      _radiusToward(pts, b, c, bestHole.cb, bestHole.cc, 2, 20));
  final double rTowardC = math.min(
      _radiusToward(pts, b, c, bestHole.cb, bestHole.cc, 1, 20),
      _radiusToward(pts, b, c, bestHole.cb, bestHole.cc, -1, 20));

  // Profil le long de l'axe : rayon libre par tranche. Sert (a) à situer
  // l'OUVERTURE d'une pièce dont le trou n'est pas au centre — un plastron de
  // collier se porte par son ouverture haute, pas par son milieu — et (b) au
  // futur solveur de point de repos (le bijou glisse jusqu'où le membre
  // atteint son diamètre intérieur).
  const int ns = 20;
  final double lo = mn[a], hi = mx[a], w = (hi - lo) / ns;
  final List<Map<String, dynamic>> profile = <Map<String, dynamic>>[];
  double narrowestT = 0, narrowestR = double.infinity;
  for (int i = 0; i < ns; i++) {
    final double t0 = lo + i * w, t1 = t0 + w;
    double free = double.infinity;
    int cnt = 0;
    for (int k = 0; k < n; k++) {
      final double t = pts[k * 3 + a];
      if (t < t0 || t >= t1) continue;
      cnt++;
      final double dx = pts[k * 3 + b] - bestHole.cb;
      final double dy = pts[k * 3 + c] - bestHole.cc;
      final double r = math.sqrt(dx * dx + dy * dy);
      if (r < free) free = r;
    }
    if (cnt == 0) continue;
    profile.add(<String, dynamic>{
      't': _r(( t0 + t1) / 2),
      'freeRadius': _r(free),
      'vertices': cnt,
    });
    if (free < narrowestR) {
      narrowestR = free;
      narrowestT = (t0 + t1) / 2;
    }
  }

  // Face décorée : le côté qui porte le plus de sommets. Le runtime s'en sert
  // pour orienter la pièce vers la caméra.
  int plusB = 0, plusC = 0;
  for (int i = 0; i < n; i++) {
    if (pts[i * 3 + b] > bestHole.cb) plusB++;
    if (pts[i * 3 + c] > bestHole.cc) plusC++;
  }

  final List<double> center = <double>[0, 0, 0];
  center[b] = bestHole.cb;
  center[c] = bestHole.cc;

  return <String, dynamic>{
    'source': path.split(RegExp(r'[\\/]')).last,
    'generator': 'tool/fit_analyzer.dart',
    'vertices': n,
    'bbox': <String, dynamic>{
      'min': mn.map(_r).toList(),
      'max': mx.map(_r).toList(),
    },
    // Axe du trou et demi-étendue de la pièce le long de cet axe
    // (= kBandHalfHeight pour une manchette).
    'holeAxis': _kAxisName[a],
    'axisHalfExtent': _r((mx[a] - mn[a]) / 2),
    'axisMin': _r(mn[a]),
    'axisMax': _r(mx[a]),
    // Centre du trou dans le repère MODÈLE. L'origine d'un GLB Meshy tombe
    // sur le centre de la boîte englobante : dès que la pièce est
    // dissymétrique (la fleur d'une bague), les deux divergent et le membre
    // ne passe pas au centre du trou.
    'holeCenter': center.map(_r).toList(),
    'holeCenterOffset': _r(math.sqrt(
        bestHole.cb * bestHole.cb + bestHole.cc * bestHole.cc)),
    // Rayon du cylindre vide traversant, et profil ovale.
    'holeRadius': _r(bestHole.radius),
    'holeRadiusMin': _r(rMin),
    'holeRadiusMax': _r(rMax),
    'ovality': _r(rMin / rMax),
    // Rayons cardinaux : à confronter aux largeurs de membre mesurées dans la
    // même direction (la segmentation donne une largeur médio-latérale).
    'radiusToward${_kAxisName[b]}': _r(rTowardB),
    'radiusToward${_kAxisName[c]}': _r(rTowardC),
    // Position, le long de l'axe, de l'endroit le plus étroit : c'est là que
    // le membre s'insère (= kNecklaceOpeningY pour un plastron).
    'openingAt': _r(narrowestT),
    'openingRadius': _r(narrowestR),
    'frontDirection':
        plusB >= n / 2 ? '+${_kAxisName[b]}' : '-${_kAxisName[b]}',
    'denserSide': <String, dynamic>{
      '+${_kAxisName[b]}': plusB,
      '+${_kAxisName[c]}': plusC,
    },
    'radiusProfile': profile,
    // À renseigner depuis la fiche produit : c'est ce qui rend l'échelle
    // DÉTERMINISTE au lieu d'être un réglage visuel (⌀ intérieur réel du
    // bijou en mm ; une bague taille 52 fait 16.5 mm).
    'realInnerDiameterMm': null,
  };
}

double _r(double v) => (v * 10000).roundToDouble() / 10000;

void main(List<String> args) {
  if (args.isEmpty) {
    stderr.writeln('Usage: dart run tool/fit_analyzer.dart '
        '<fichier.glb | dossier> [...]');
    exit(64);
  }
  final List<String> files = <String>[];
  for (final String arg in args) {
    final FileSystemEntityType t = FileSystemEntity.typeSync(arg);
    if (t == FileSystemEntityType.directory) {
      for (final FileSystemEntity e
          in Directory(arg).listSync(recursive: true)) {
        if (e is File && e.path.toLowerCase().endsWith('.glb')) {
          files.add(e.path);
        }
      }
    } else if (t == FileSystemEntityType.file) {
      files.add(arg);
    } else {
      stderr.writeln('introuvable: $arg');
      exit(66);
    }
  }
  files.sort();

  int failed = 0;
  for (final String f in files) {
    try {
      final Map<String, dynamic> fit = analyze(f);
      final String out = '${f.substring(0, f.length - 4)}.fit.json';
      File(out).writeAsStringSync(
          '${const JsonEncoder.withIndent('  ').convert(fit)}\n');
      stdout.writeln('✓ ${fit['source']}');
      stdout.writeln('    axe du trou   : ${fit['holeAxis']}'
          '   rayon ${fit['holeRadius']}'
          '   (${fit['holeRadiusMin']} → ${fit['holeRadiusMax']},'
          ' ovalité ${fit['ovality']})');
      stdout.writeln('    centre du trou: ${fit['holeCenter']}'
          '   décalé de ${fit['holeCenterOffset']}');
      stdout.writeln('    ouverture     : à ${fit['openingAt']}'
          '   rayon ${fit['openingRadius']}'
          '   demi-hauteur ${fit['axisHalfExtent']}');
      stdout.writeln('    → $out');
    } catch (e) {
      failed++;
      stderr.writeln('✗ $f\n    $e');
    }
  }
  if (failed > 0) exit(1);
}
