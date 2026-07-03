import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../services/ar_service.dart';

/// Overlay 3D temps réel : une WebView transparente hébergeant la scène
/// three.js (`assets/web/ar_scene.html`), pilotée en continu par les
/// [AnchorResult] issus du tracking.
///
/// Contrairement à l'ancien `model_viewer_plus`, la WebView n'est **pas**
/// recréée ni déplacée à chaque frame : elle couvre exactement l'aperçu
/// caméra (même boîte AspectRatio, cf. ARViewScreen) et c'est la scène
/// three.js interne qui repositionne le bijou (message JS léger par frame).
/// Cf. docs/decision_rendu_3d.md.
class AROverlayView extends StatefulWidget {
  /// Ancrage courant (position/échelle/rotation). `null` = pas de repère.
  final ValueListenable<AnchorResult?> anchor;

  /// Chemin d'asset du .glb à afficher (ex: "assets/jewelry/bagues/x.glb").
  final String assetPath;

  /// Si vrai, affiche la sphère PBR dorée de prototype au lieu du .glb —
  /// sert à valider l'approche de rendu avant de brancher les vrais modèles.
  final bool prototypeSphere;

  /// Remonte le FPS de rendu interne de la scène three.js (diagnostic).
  final ValueChanged<int>? onFps;

  /// Appelé quand le modèle courant est chargé et prêt à être affiché.
  final VoidCallback? onLoaded;

  /// Appelé si la scène signale une erreur (ex: .glb introuvable/corrompu).
  final ValueChanged<String>? onError;

  const AROverlayView({
    super.key,
    required this.anchor,
    required this.assetPath,
    this.prototypeSphere = false,
    this.onFps,
    this.onLoaded,
    this.onError,
  });

  /// Convertit un chemin d'asset Flutter en URL relative résolvable depuis
  /// `assets/web/ar_scene.html`. Ex: "assets/jewelry/bagues/x.glb"
  /// -> "../jewelry/bagues/x.glb".
  ///
  /// Chaque segment est percent-encodé : un nom de fichier contenant espace,
  /// accent ou `#` (fichiers déposés à la main) casserait sinon le fetch du
  /// GLTFLoader. Statique/pur pour être testable.
  @visibleForTesting
  static String assetToSceneUrl(String assetPath) {
    const String prefix = 'assets/';
    final String rel = assetPath.startsWith(prefix)
        ? assetPath.substring(prefix.length)
        : assetPath;
    final String encoded = rel.split('/').map(Uri.encodeComponent).join('/');
    return '../$encoded';
  }

  @override
  State<AROverlayView> createState() => _AROverlayViewState();
}

class _AROverlayViewState extends State<AROverlayView> {
  late final WebViewController _web;
  bool _sceneReady = false;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000)) // WebView transparente
      ..addJavaScriptChannel('ARChannel', onMessageReceived: _onSceneMessage)
      ..loadFlutterAsset('assets/web/ar_scene.html');

    widget.anchor.addListener(_pushAnchor);
  }

  @override
  void didUpdateWidget(covariant AROverlayView old) {
    super.didUpdateWidget(old);
    if (old.anchor != widget.anchor) {
      old.anchor.removeListener(_pushAnchor);
      widget.anchor.addListener(_pushAnchor);
    }
    if (old.assetPath != widget.assetPath ||
        old.prototypeSphere != widget.prototypeSphere) {
      if (_sceneReady) _setModel();
    }
  }

  @override
  void dispose() {
    widget.anchor.removeListener(_pushAnchor);
    super.dispose();
  }

  void _onSceneMessage(JavaScriptMessage msg) {
    Map<String, dynamic> data;
    try {
      data = jsonDecode(msg.message) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    switch (data['type']) {
      case 'ready':
        _sceneReady = true;
        _setModel();
        _pushAnchor(); // pousse l'état courant immédiatement
        break;
      case 'loaded':
        widget.onLoaded?.call();
        break;
      case 'fps':
        final Object? v = data['value'];
        if (v is num) widget.onFps?.call(v.toInt());
        break;
      case 'error':
        final String message = '${data['message']}';
        debugPrint('AR scene error: $message');
        widget.onError?.call(message);
        break;
    }
  }

  void _setModel() {
    final String arg = widget.prototypeSphere
        ? 'sphere'
        : AROverlayView.assetToSceneUrl(widget.assetPath);
    _web.runJavaScript("window.arSetModel(${jsonEncode(arg)});");
  }

  void _pushAnchor() {
    if (!_sceneReady) return;
    final AnchorResult? r = widget.anchor.value;
    if (r == null) {
      _web.runJavaScript('window.arUpdate(0,0,0,0,false);');
      return;
    }
    _web.runJavaScript(
      'window.arUpdate(${r.position.dx},${r.position.dy},'
      '${r.scale},${r.rotationRadians},true);',
    );
  }

  @override
  Widget build(BuildContext context) {
    return WebViewWidget(controller: _web);
  }
}
