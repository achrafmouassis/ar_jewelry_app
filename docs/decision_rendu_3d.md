# Décision — Moteur de rendu 3D pour l'overlay AR temps réel

> Statut : **arrêté** — Option 1 retenue (WebView + three.js custom, bundlé localement).
> Date : 2026-07-01. Contexte : remplacement de `model_viewer_plus` (visionneuse
> statique inadaptée à un ancrage temps réel à 30 FPS).

## Problème

`model_viewer_plus` (`<model-viewer>`) est une **visionneuse** : elle encapsule
three.js dans une WebView mais n'expose aucune API pour repositionner l'objet à
chaque frame en fonction de landmarks. Dans la V0, on contournait ça en déplaçant
le `Positioned` Flutter parent — ce qui déplace le rectangle de la WebView, pas la
caméra 3D interne, et recrée un contexte coûteux. Inadapté à un miroir virtuel.

## Exigences (rappel objectif produit)

1. Suivi continu de l'`AnchorResult` (position + rotation + échelle) à ≥ 30 FPS.
2. Matériaux **PBR** réalistes (or/argent métallique avec reflets, pierres).
3. **100 % local** : aucune frame caméra ne sort de l'appareil, y compris pas de
   dépendance réseau à l'exécution.
4. Overlay transparent parfaitement superposé au flux caméra Flutter.
5. Maintenabilité + parité Android/iOS.

## Options évaluées

### Option 1 — WebView + three.js custom (RETENUE)

Une **unique** WebView plein écran, fond transparent, hébergeant une scène three.js
que l'on pilote via un canal JS (`runJavaScript`). Flutter pousse chaque
`AnchorResult` ; la scène three.js positionne/oriente/redimensionne le mesh
elle-même. three.js est **bundlé dans les assets** (aucun CDN à l'exécution).

| Critère | Évaluation |
|---|---|
| FPS | Bon : le rendu se fait dans le contexte WebGL natif du WebView ; seul un petit message JSON transite par frame. À valider device (cible ≥ 30 FPS). |
| PBR | ★★★ Natif dans three.js : `MeshStandardMaterial` (metalness/roughness) + environment map (`RoomEnvironment` + `PMREMGenerator`) pour des reflets métalliques crédibles sans fichier HDR externe. |
| Latence d'ancrage | Pont Flutter↔JS à mesurer, mais un seul `runJavaScript` léger par frame (pas de rechargement de vue). |
| Maintenabilité | ★★★ three.js = techno cible du projet, écosystème mûr, chargement GLB standard (`GLTFLoader`). |
| Parité iOS/Android | ★★★ `webview_flutter` officiel supporte les deux, WebView transparent des deux côtés. |

**Risque principal :** latence du pont Flutter↔WebView sous forte cadence. Mitigé
par : (a) un seul message par frame, (b) le lissage One-Euro côté Dart en amont,
(c) possibilité de passer au `requestAnimationFrame` interne pour interpoler.

### Option 2 — Filament natif (`thermion`)

Rendu natif Filament, PBR de référence, perf maximale.

- **Contre :** intégration nettement plus lourde (moteur natif, build Android/iOS),
  package tiers à la maturité/maintenance à surveiller, courbe d'apprentissage.
- **Verdict :** sur-dimensionné pour un overlay 2.5D d'essayage. Gardé comme
  **plan B** si l'Option 1 ne tient pas les 30 FPS sur device milieu de gamme.

### Option 3 — `flutter_gl` / portage three.js en Dart

Contexte WebGL exposé à Dart, three.js réécrit/porté.

- **Contre :** maturité et maintenance insuffisantes, portage three.js Dart
  incomplet et fragile, peu de PBR clé-en-main. Risque projet élevé.
- **Verdict :** écarté.

## Décision

**Option 1 — WebView + three.js bundlé localement.**

Meilleur compromis PBR / maintenabilité / parité / respect du 100 % local, et
c'est la techno explicitement visée par le cahier des charges. Filament (`thermion`)
reste le plan B documenté si le device révèle un plafond de FPS.

## Conséquences d'implémentation

- Nouveau widget `lib/widgets/ar_overlay_view.dart` : WebView (`webview_flutter`)
  hébergeant `assets/web/ar_scene.html`, fond transparent, piloté par un
  `ValueListenable<AnchorResult?>`.
- three.js (UMD r0.149) + `GLTFLoader` + `RoomEnvironment` **bundlés** dans
  `assets/web/` — zéro requête réseau à l'exécution.
- `model_viewer_plus` retiré de `pubspec.yaml`.
- L'interface (`AnchorResult`, écrans) **ne change pas** : seul le moteur d'overlay
  est remplacé.
- **Validation par prototype d'abord** : une sphère PBR dorée ancrée sur la main,
  FPS mesuré, avant de brancher les vrais `.glb`.
