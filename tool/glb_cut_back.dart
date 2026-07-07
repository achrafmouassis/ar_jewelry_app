import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// Génère une variante "_arhalf.glb" d'un GLB : supprime les triangles de la
/// moitié arrière du bijou (celle cachée par le doigt/poignet en essayage AR).
/// Usage: dart glb_cut_back.dart <in.glb> <axe: x|y|z> <seuil normalisé -1..1>
///
/// Hypothèses (vérifiées sur les exports Meshy/glTF-Transform du projet) :
/// - 1 mesh / 1 primitive TRIANGLES, indices uint32 (componentType 5125)
/// - POSITION int16 normalisé (KHR_mesh_quantization), stride dans bufferView
void main(List<String> args) {
  final inPath = args[0];
  final axis = {'x': 0, 'y': 1, 'z': 2}[args[1]]!;
  final threshold = (double.parse(args[2]) * 32767).round();

  final bytes = File(inPath).readAsBytesSync();
  final data = ByteData.sublistView(bytes);
  final jsonLen = data.getUint32(12, Endian.little);
  final gltf = jsonDecode(utf8.decode(bytes.sublist(20, 20 + jsonLen)))
      as Map<String, dynamic>;
  final binOfs = 20 + jsonLen + 8; // début des données BIN

  final accessors = gltf['accessors'] as List;
  final views = gltf['bufferViews'] as List;
  final prim = ((gltf['meshes'] as List)[0]['primitives'] as List)[0]
      as Map<String, dynamic>;

  final idxAcc = accessors[prim['indices']] as Map<String, dynamic>;
  final posAcc = accessors[(prim['attributes']
      as Map<String, dynamic>)['POSITION']] as Map<String, dynamic>;
  if (idxAcc['componentType'] != 5125 || posAcc['componentType'] != 5122) {
    stderr.writeln('Format inattendu, abandon.');
    exit(1);
  }

  final idxView = views[idxAcc['bufferView']] as Map<String, dynamic>;
  final posView = views[posAcc['bufferView']] as Map<String, dynamic>;
  final idxStart = binOfs + ((idxView['byteOffset'] ?? 0) as int);
  final posStart = binOfs +
      ((posView['byteOffset'] ?? 0) as int) +
      ((posAcc['byteOffset'] ?? 0) as int);
  final stride = (posView['byteStride'] ?? 6) as int;
  final idxCount = idxAcc['count'] as int;

  // Coordonnée [axis] de chaque vertex (int16, échelle uniforme sur les 3 axes).
  final vtxCount = posAcc['count'] as int;
  final coord = Int32List(vtxCount);
  for (var v = 0; v < vtxCount; v++) {
    coord[v] = data.getInt16(posStart + v * stride + axis * 2, Endian.little);
  }

  // Filtre : garde un triangle si le centroïde est devant le plan de coupe.
  final kept = Uint32List(idxCount);
  var keptCount = 0;
  for (var t = 0; t < idxCount; t += 3) {
    final a = data.getUint32(idxStart + t * 4, Endian.little);
    final b = data.getUint32(idxStart + (t + 1) * 4, Endian.little);
    final c = data.getUint32(idxStart + (t + 2) * 4, Endian.little);
    if (coord[a] + coord[b] + coord[c] > threshold * 3) {
      kept[keptCount] = a;
      kept[keptCount + 1] = b;
      kept[keptCount + 2] = c;
      keptCount += 3;
    }
  }
  stdout.writeln('$inPath: ${idxCount ~/ 3} triangles -> ${keptCount ~/ 3} '
      '(${(100 * keptCount / idxCount).toStringAsFixed(1)}%)');

  // Réécrit les indices filtrés au même emplacement, réduit le count.
  final out = Uint8List.fromList(bytes);
  final outData = ByteData.sublistView(out);
  for (var i = 0; i < keptCount; i++) {
    outData.setUint32(idxStart + i * 4, kept[i], Endian.little);
  }
  idxAcc['count'] = keptCount;
  // Réduit aussi la bufferView des indices à la taille utile (les octets
  // au-delà restent dans le BIN mais ne sont plus référencés).
  idxView['byteLength'] = keptCount * 4;

  // Ré-encode le JSON (padding espaces à 4 octets) et réassemble le GLB.
  final jsonRaw = utf8.encode(jsonEncode(gltf));
  final pad = (4 - jsonRaw.length % 4) % 4;
  final jsonOut = Uint8List.fromList(
      <int>[...jsonRaw, ...List<int>.filled(pad, 0x20)]);
  final binChunk = out.sublist(20 + jsonLen, out.length); // header+data du BIN
  final totalLen = 12 + 8 + jsonOut.length + binChunk.length;

  final result = BytesBuilder();
  final header = ByteData(20);
  header.setUint32(0, 0x46546C67, Endian.little); // magic 'glTF'
  header.setUint32(4, 2, Endian.little);
  header.setUint32(8, totalLen, Endian.little);
  header.setUint32(12, jsonOut.length, Endian.little);
  header.setUint32(16, 0x4E4F534A, Endian.little); // 'JSON'
  result
    ..add(header.buffer.asUint8List())
    ..add(jsonOut)
    ..add(binChunk);

  final outPath = inPath.replaceFirst('.glb', '_arhalf.glb');
  File(outPath).writeAsBytesSync(result.takeBytes());
  stdout.writeln('  -> $outPath');
}
