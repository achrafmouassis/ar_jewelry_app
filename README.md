# AR Jewelry App

Application Flutter d'essayage de bijoux en réalité augmentée temps réel.

3 écrans :
1. **Types** — grille des catégories découvertes dans `assets/jewelry/`
2. **Liste** — bijoux (`.glb`) du type sélectionné
3. **AR** — caméra temps réel + bijou 3D collé sur la main ou le visage

---

## Stack

| Rôle                          | Package                          |
|-------------------------------|----------------------------------|
| Caméra temps réel             | `camera`                         |
| Détection main (MediaPipe)    | `hand_landmarker` (delegate GPU) |
| Détection visage              | `google_mlkit_face_detection`    |
| Rendu 3D des `.glb`           | `model_viewer_plus`              |
| Permissions runtime           | `permission_handler`             |

---

## Lancer l'app

```bash
flutter pub get
flutter run --release
```

> Conseil : utiliser `--release` pour la vraie perf (debug = 5–10× plus lent
> sur le pipeline caméra).

Plateformes testées : Android 10+, iOS 13+.

---

## Ajouter un bijou

1. Récupère/crée un fichier `.glb` (export Blender → glTF Binary).
2. Dépose-le dans le bon sous-dossier de `assets/jewelry/` :

| Type      | Dossier                       | Tracking |
|-----------|-------------------------------|----------|
| Bague     | `assets/jewelry/bagues/`      | Main     |
| Bracelet  | `assets/jewelry/bracelets/`   | Main     |
| Collier   | `assets/jewelry/colliers/`    | Visage   |
| Boucle    | `assets/jewelry/boucles/`     | Visage   |
| Perle     | `assets/jewelry/perles/`      | Visage   |

3. Le nom de fichier (`anneau_or.glb`) devient le libellé affiché
   (« Anneau or »).
4. **Aucune modification de code requise.** Les sous-dossiers sont déjà
   déclarés dans `pubspec.yaml` ; relance simplement `flutter pub get`.

### Ajouter un nouveau type

1. Crée un sous-dossier `assets/jewelry/<nouveau_type>/`.
2. Ajoute la ligne dans `pubspec.yaml` :
   ```yaml
   assets:
     - assets/jewelry/<nouveau_type>/
   ```
3. Si la cible diffère du défaut (main), ajoute le mapping dans
   `lib/config/tracking_config.dart`.

---

## Architecture

```
lib/
├── main.dart                       # Bootstrap + thème + caméras globales
├── models/
│   ├── jewelry_type.dart           # Modèle type (folder + libellé + icône)
│   └── jewelry_model.dart          # Modèle bijou (chemin .glb)
├── config/
│   └── tracking_config.dart        # Mapping type → main/visage + ancrage
├── services/
│   ├── jewelry_service.dart        # Découverte assets via AssetManifest
│   └── ar_service.dart             # MediaPipe + ML Kit + flux d'ancrage
├── screens/
│   ├── jewelry_types_screen.dart   # Écran 1
│   ├── jewelry_list_screen.dart    # Écran 2
│   └── ar_view_screen.dart         # Écran 3
└── widgets/
    ├── jewelry_card.dart
    ├── fps_counter.dart
    └── loading_indicator.dart
```

**Séparation stricte logique / UI** : les écrans ne contiennent que la
composition + le branchement ; toute la logique (découverte des assets,
détection, mapping cible) vit dans `services/` et `config/`.

---

## Performance

- **GPU delegate MediaPipe** activé (`HandLandmarkerDelegate.GPU`).
- **Throttle de frames** : si une frame est en cours, les suivantes sont
  ignorées — évite la file d'attente qui ferait diverger latence/FPS.
- **Résolution caméra** : `ResolutionPreset.medium` (~720p) — équilibre
  qualité/perf.
- **Format YUV420** demandé explicitement pour minimiser les conversions.
- **Overlay isolé** via `ValueListenableBuilder` — seul le `Positioned`
  rebuild à chaque détection, pas le `CameraPreview` ni le `ModelViewer`.
- **Const + ValueKey** partout pour minimiser les rebuilds.
- **dispose()** strict : caméra, stream image, AR service, NotificationBox,
  réagit aux changements `AppLifecycleState`.
- **Cache des assets** : `AssetManifest.json` lu une seule fois.

Cible : **30 FPS minimum** sur device de milieu de gamme, **60 FPS** sur
un Snapdragon 8 Gen 1 / iPhone 12+.

---

## Permissions

- **Android** : `CAMERA` dans `android/app/src/main/AndroidManifest.xml`
  (déjà ajoutée).
- **iOS** : `NSCameraUsageDescription` dans `ios/Runner/Info.plist`
  (déjà ajoutée).
- À l'exécution, `permission_handler` demande la permission à l'utilisateur.

---

## Gestion des erreurs

| Cas                          | Comportement                                |
|------------------------------|---------------------------------------------|
| Permission caméra refusée    | Message dédié dans l'écran AR               |
| Aucun bijou dans le dossier  | EmptyState avec instruction de dépôt        |
| Aucun type détecté           | EmptyState avec lien vers `assets/jewelry/` |
| Pas de repère détecté        | Aperçu caméra seul, overlay masqué          |
| Échec init caméra/MediaPipe  | Message d'erreur dans l'écran AR            |

---

## Notes

- `model_viewer_plus` rend le `.glb` via une WebView ; sur très bas de
  gamme cela peut limiter le FPS. Si besoin, on peut migrer vers
  `flutter_3d_controller` ou un rendu Filament natif sans changer
  l'architecture (interface `_JewelryGlbView` isolée dans
  `ar_view_screen.dart`).
- Pour de vrais ancrages 3D (occlusion, profondeur), ARCore/ARKit serait
  nécessaire ; ici on fait du « tracking 2D + overlay » qui couvre 95 %
  du cas d'usage essayage.
