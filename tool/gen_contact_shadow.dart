import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

/// Génère `assets/occluders/contact_shadow.glb` : la géométrie de l'OMBRE DE
/// CONTACT projetée par un bijou sur la peau.
///
/// POURQUOI. Un objet posé sur une surface y projette une ombre de contact.
/// Son absence est le marqueur numéro un du compositing raté, tous domaines
/// confondus — et c'est exactement ce qui donne l'impression que le bijou
/// « lévite d'un millimètre ». Snap livre d'ailleurs un maillage d'ombre EN
/// STANDARD avec son occulteur de poignet, à côté de l'occulteur de
/// profondeur : chez eux ce n'est pas une option, c'est une pièce du dispositif.
///
/// COMMENT. On ne peut pas assombrir la peau par une vraie ombre projetée : la
/// peau n'existe pas dans la scène 3D, c'est l'image caméra derrière une
/// surface translucide. On rend donc une JUPE cylindrique noire, en alpha
/// dégradé, posée juste au-dessus de la surface du membre. Le cylindre
/// occluseur, qui écrit la profondeur, en élimine automatiquement la moitié
/// arrière : ne subsiste que la moitié tournée vers la caméra, ce qui se lit
/// exactement comme un assombrissement de la peau visible.
///
/// REPÈRE. Le maillage est en unités de DEMI-HAUTEUR DU BIJOU : y = ±1 tombe
/// sur les bords de la pièce, quels qu'ils soient. Le runtime n'a donc qu'à
/// mettre à l'échelle par `s × bandHalfHeight`, et le dégradé se place tout
/// seul sur n'importe quel bijou. Rayon 1 → mis à l'échelle sur le membre.
///
/// Usage : dart run tool/gen_contact_shadow.dart [chemin de sortie]

/// Segments autour de l'axe. 48 suffit : la jupe est floue par nature.
const int kSegments = 48;

/// Anneaux le long de l'axe, par jupe. Porte la finesse du dégradé.
const int kRings = 10;

/// Portée de l'ombre au-delà du bord du bijou, en demi-hauteurs de bijou.
const double kSpan = 2.2;

/// Opacité au contact. Une ombre de contact est dense mais jamais opaque —
/// au-delà, ça se lit comme un trait dessiné, pas comme une ombre.
const double kMaxAlpha = 0.55;

/// Profil d'opacité : dense au contact, chute rapide. Le carré approche la
/// décroissance d'une vraie occlusion ambiante de proximité.
double alphaAt(double t) {
  final double u = (1.0 - t).clamp(0.0, 1.0);
  return kMaxAlpha * u * u;
}

void main(List<String> args) {
  final String out = args.isNotEmpty
      ? args[0]
      : 'assets/occluders/contact_shadow.glb';

  final List<double> pos = <double>[];
  final List<double> nrm = <double>[];
  final List<double> col = <double>[];
  final List<int> idx = <int>[];

  // Deux jupes disjointes, une par bord du bijou. Entre les deux (|y| < 1) il
  // n'y a rien à assombrir : cette zone est sous la pièce, donc cachée.
  for (final double side in <double>[1.0, -1.0]) {
    final int base = pos.length ~/ 3;
    for (int r = 0; r <= kRings; r++) {
      final double t = r / kRings; // 0 au contact, 1 à la portée maximale
      final double y = side * (1.0 + (kSpan - 1.0) * t);
      final double a = alphaAt(t);
      for (int s = 0; s <= kSegments; s++) {
        final double ang = 2 * math.pi * s / kSegments;
        final double cx = math.cos(ang);
        final double cz = math.sin(ang);
        pos..add(cx)..add(y)..add(cz);
        nrm..add(cx)..add(0)..add(cz);
        // RVB blanc : c'est baseColorFactor qui impose le noir. L'alpha
        // sommet porte tout le dégradé.
        col..add(1)..add(1)..add(1)..add(a);
      }
    }
    for (int r = 0; r < kRings; r++) {
      for (int s = 0; s < kSegments; s++) {
        final int a = base + r * (kSegments + 1) + s;
        final int b = a + 1;
        final int c = a + (kSegments + 1);
        final int d = c + 1;
        idx..add(a)..add(c)..add(b);
        idx..add(b)..add(c)..add(d);
      }
    }
  }

  final int vertexCount = pos.length ~/ 3;
  final Uint8List posBytes = _f32(pos);
  final Uint8List nrmBytes = _f32(nrm);
  final Uint8List colBytes = _f32(col);
  final Uint8List idxBytes = _u16(idx);

  int offset = 0;
  final List<Map<String, dynamic>> views = <Map<String, dynamic>>[];
  int addView(Uint8List b, int? target) {
    views.add(<String, dynamic>{
      'buffer': 0,
      'byteOffset': offset,
      'byteLength': b.length,
      if (target != null) 'target': target,
    });
    offset += b.length;
    return views.length - 1;
  }

  final int vPos = addView(posBytes, 34962);
  final int vNrm = addView(nrmBytes, 34962);
  final int vCol = addView(colBytes, 34962);
  final int vIdx = addView(idxBytes, 34963);

  final Map<String, dynamic> gltf = <String, dynamic>{
    'asset': <String, dynamic>{
      'version': '2.0',
      'generator': 'tool/gen_contact_shadow.dart',
    },
    'extensionsUsed': <String>['KHR_materials_unlit'],
    'scene': 0,
    'scenes': <dynamic>[
      <String, dynamic>{'nodes': <int>[0]},
    ],
    'nodes': <dynamic>[
      <String, dynamic>{'mesh': 0, 'name': 'contact_shadow'},
    ],
    'meshes': <dynamic>[
      <String, dynamic>{
        'primitives': <dynamic>[
          <String, dynamic>{
            'attributes': <String, dynamic>{
              'POSITION': 0,
              'NORMAL': 1,
              'COLOR_0': 2,
            },
            'indices': 3,
            'material': 0,
          },
        ],
      },
    ],
    'materials': <dynamic>[
      <String, dynamic>{
        'name': 'contact_shadow',
        'doubleSided': true,
        'alphaMode': 'BLEND',
        // UNLIT : une ombre ne doit pas recevoir de lumière, sinon la
        // directionnelle l'éclaircirait et elle cesserait d'être une ombre.
        'extensions': <String, dynamic>{
          'KHR_materials_unlit': <String, dynamic>{},
        },
        'pbrMetallicRoughness': <String, dynamic>{
          'baseColorFactor': <double>[0, 0, 0, 1],
          'metallicFactor': 0,
          'roughnessFactor': 1,
        },
      },
    ],
    'buffers': <dynamic>[
      <String, dynamic>{'byteLength': offset},
    ],
    'bufferViews': views,
    'accessors': <dynamic>[
      <String, dynamic>{
        'bufferView': vPos,
        'componentType': 5126,
        'count': vertexCount,
        'type': 'VEC3',
        'min': <double>[-1, -kSpan, -1],
        'max': <double>[1, kSpan, 1],
      },
      <String, dynamic>{
        'bufferView': vNrm,
        'componentType': 5126,
        'count': vertexCount,
        'type': 'VEC3',
      },
      <String, dynamic>{
        'bufferView': vCol,
        'componentType': 5126,
        'count': vertexCount,
        'type': 'VEC4',
      },
      <String, dynamic>{
        'bufferView': vIdx,
        'componentType': 5123,
        'count': idx.length,
        'type': 'SCALAR',
      },
    ],
  };

  final BytesBuilder bin = BytesBuilder()
    ..add(posBytes)
    ..add(nrmBytes)
    ..add(colBytes)
    ..add(idxBytes);
  _writeGlb(out, gltf, _pad4(bin.toBytes()));

  stdout.writeln('✓ $out');
  stdout.writeln('  $vertexCount sommets, ${idx.length ~/ 3} triangles');
  stdout.writeln('  2 jupes, y ∈ ±[1, $kSpan] en demi-hauteurs de bijou');
  stdout.writeln('  opacité $kMaxAlpha au contact → 0 à la portée maximale');
}

Uint8List _f32(List<double> v) {
  final Float32List f = Float32List.fromList(v);
  return Uint8List.view(f.buffer, 0, f.lengthInBytes);
}

Uint8List _u16(List<int> v) {
  final Uint16List u = Uint16List.fromList(v);
  return Uint8List.view(u.buffer, 0, u.lengthInBytes);
}

/// Les chunks GLB doivent être alignés sur 4 octets (spec).
Uint8List _pad4(Uint8List b) {
  final int rem = b.length % 4;
  if (rem == 0) return b;
  return Uint8List(b.length + (4 - rem))..setAll(0, b);
}

void _writeGlb(String path, Map<String, dynamic> gltf, Uint8List bin) {
  List<int> js = utf8.encode(jsonEncode(gltf));
  while (js.length % 4 != 0) {
    js = <int>[...js, 0x20]; // bourrage par des espaces, pas des zéros
  }
  final int total = 12 + 8 + js.length + 8 + bin.length;
  final BytesBuilder b = BytesBuilder();
  void u32(int v) {
    final ByteData d = ByteData(4)..setUint32(0, v, Endian.little);
    b.add(d.buffer.asUint8List());
  }

  u32(0x46546C67); // 'glTF'
  u32(2);
  u32(total);
  u32(js.length);
  u32(0x4E4F534A); // JSON
  b.add(js);
  u32(bin.length);
  u32(0x004E4942); // BIN
  b.add(bin);
  File(path).writeAsBytesSync(b.toBytes());
}
