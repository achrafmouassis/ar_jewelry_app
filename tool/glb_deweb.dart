import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

// Script autonome (exécuté via son propre package, cf. commentaire d'usage) —
// `image` n'est pas une dépendance de l'app.
// ignore: depend_on_referenced_packages
import 'package:image/image.dart' as img;

/// Convertit les textures EXT_texture_webp d'un GLB en PNG (que gltfio de
/// Filament décode sans planter) et reconstruit le binaire.
/// Usage: dart run bin/deweb.dart <in.glb> <out.glb>
void main(List<String> args) {
  final inPath = args[0];
  final outPath = args[1];
  final bytes = File(inPath).readAsBytesSync();
  final data = ByteData.sublistView(bytes);

  if (data.getUint32(0, Endian.little) != 0x46546C67) {
    stderr.writeln('pas un GLB');
    exit(1);
  }
  final jsonLen = data.getUint32(12, Endian.little);
  final gltf = jsonDecode(utf8.decode(bytes.sublist(20, 20 + jsonLen)))
      as Map<String, dynamic>;
  final binOfs = 20 + jsonLen;
  final binLen = data.getUint32(binOfs, Endian.little);
  final binStart = binOfs + 8;
  final bin = Uint8List.sublistView(bytes, binStart, binStart + binLen);

  final views = (gltf['bufferViews'] as List).cast<Map<String, dynamic>>();
  final images = ((gltf['images'] as List?) ?? []).cast<Map<String, dynamic>>();
  final textures =
      ((gltf['textures'] as List?) ?? []).cast<Map<String, dynamic>>();

  // Décode chaque image webp → png ; mémorise les nouveaux octets par bufferView.
  final Map<int, Uint8List> newViewBytes = {};
  var converted = 0;
  for (final im in images) {
    final mime = im['mimeType'];
    final bvIndex = im['bufferView'];
    if (bvIndex == null) continue;
    final bv = views[bvIndex as int];
    final ofs = (bv['byteOffset'] ?? 0) as int;
    final len = bv['byteLength'] as int;
    final src = Uint8List.sublistView(bin, ofs, ofs + len);
    if (mime == 'image/webp') {
      final decoded = img.decodeWebP(src);
      if (decoded == null) {
        stderr.writeln('échec décodage webp (image bufferView $bvIndex)');
        exit(2);
      }
      newViewBytes[bvIndex] = img.encodePng(decoded);
      im['mimeType'] = 'image/png';
      converted++;
    }
  }

  // Textures : bascule EXT_texture_webp.source → source standard.
  for (final tex in textures) {
    final ext = tex['extensions'] as Map<String, dynamic>?;
    final webp = ext?['EXT_texture_webp'] as Map<String, dynamic>?;
    if (webp != null) {
      tex['source'] = webp['source'];
      ext!.remove('EXT_texture_webp');
      if (ext.isEmpty) tex.remove('extensions');
    }
  }

  // Retire l'extension des listes used/required.
  for (final key in ['extensionsUsed', 'extensionsRequired']) {
    final list = gltf[key] as List?;
    if (list != null) {
      list.remove('EXT_texture_webp');
      if (list.isEmpty) gltf.remove(key);
    }
  }

  // Reconstruit le BIN : chaque bufferView recopié (ou remplacé par le png),
  // réaligné sur 4 octets ; on met à jour offset/longueur.
  final out = BytesBuilder();
  for (var i = 0; i < views.length; i++) {
    final bv = views[i];
    final Uint8List vb;
    if (newViewBytes.containsKey(i)) {
      vb = newViewBytes[i]!;
    } else {
      final ofs = (bv['byteOffset'] ?? 0) as int;
      final len = bv['byteLength'] as int;
      vb = Uint8List.sublistView(bin, ofs, ofs + len);
    }
    while (out.length % 4 != 0) {
      out.addByte(0);
    }
    bv['byteOffset'] = out.length;
    bv['byteLength'] = vb.length;
    out.add(vb);
  }
  while (out.length % 4 != 0) {
    out.addByte(0);
  }
  final newBin = out.takeBytes();
  (gltf['buffers'] as List)[0]['byteLength'] = newBin.length;

  // Ré-assemble le GLB (JSON padué d'espaces, BIN sur 4 octets).
  final jsonRaw = utf8.encode(jsonEncode(gltf));
  final jsonPad = (4 - jsonRaw.length % 4) % 4;
  final jsonOut =
      Uint8List.fromList([...jsonRaw, ...List<int>.filled(jsonPad, 0x20)]);
  final total = 12 + 8 + jsonOut.length + 8 + newBin.length;

  final result = BytesBuilder();
  final header = ByteData(12);
  header.setUint32(0, 0x46546C67, Endian.little);
  header.setUint32(4, 2, Endian.little);
  header.setUint32(8, total, Endian.little);
  result.add(header.buffer.asUint8List());
  final jsonHdr = ByteData(8);
  jsonHdr.setUint32(0, jsonOut.length, Endian.little);
  jsonHdr.setUint32(4, 0x4E4F534A, Endian.little); // 'JSON'
  result..add(jsonHdr.buffer.asUint8List())..add(jsonOut);
  final binHdr = ByteData(8);
  binHdr.setUint32(0, newBin.length, Endian.little);
  binHdr.setUint32(4, 0x004E4942, Endian.little); // 'BIN\0'
  result..add(binHdr.buffer.asUint8List())..add(newBin);

  File(outPath).writeAsBytesSync(result.takeBytes());
  stdout.writeln('$inPath: $converted texture(s) webp→png -> $outPath '
      '(${(File(outPath).lengthSync() / 1e6).toStringAsFixed(2)} MB)');
}
