# AR Jewelry App

Application Flutter d'essayage de bijoux en réalité augmentée temps réel — un
**miroir virtuel** : le client choisit un bijou, active la caméra, et le voit
posé sur lui, suivant ses mouvements de façon fluide et stable.

3 écrans :
1. **Types** — grille des catégories découvertes dans `assets/jewelry/`
2. **Liste** — bijoux (`.glb`) du type sélectionné
3. **AR** — caméra temps réel + bijou 3D collé sur la main ou le visage

Plateformes cibles : **Android** et **iOS** (mobile-only ; les plateformes
desktop/web ont été retirées).

---

## Stack

| Rôle                          | Package / techno                              |
|-------------------------------|-----------------------------------------------|
| Caméra temps réel             | `camera`                                      |
| Détection main (21 pts)       | `hand_landmarker` (MediaPipe, delegate GPU)   |
| Détection visage (468 pts)    | `google_mlkit_face_mesh_detection` (FaceMesh) |
| Rendu 3D temps réel + PBR     | `webview_flutter` + **three.js** (bundlé)     |
| Permissions runtime           | `permission_handler`                          |

---

## Pipeline AR (temps réel)

```
Caméra (camera) ──frames──▶ ARService ──AnchorResult──▶ AROverlayView
                             │                           │
              ┌──────────────┴───────────────┐           ▼
       main : Hand Landmarker (21)     WebView (webview_flutter)
       visage : FaceMesh (468)         └─ assets/web/ar_scene.html (three.js)
                             │                  ├─ modèle .glb (GLTFLoader)
                    One-Euro (anti-jitter)      ├─ matériaux PBR + env map
                                                └─ arUpdate(x,y,scale,rot) / frame
```

- **Détection 100 % locale** : MediaPipe/ML Kit tournent sur l'appareil ;
  three.js est **bundlé** dans les assets (aucune requête réseau). Aucune image
  du flux caméra ne quitte l'appareil (contrainte RGPD).
- **Overlay non recréé** : la WebView three.js couvre exactement l'aperçu
  caméra (même boîte AspectRatio → coordonnées alignées malgré le letterboxing)
  et repositionne le bijou à chaque frame via un message JS léger — pas de
  widget reconstruit, pas de rechargement de vue.
- **Rendu PBR** : `MeshStandardMaterial` (metalness/roughness) + environnement
  `RoomEnvironment` (PMREM) pour des reflets métalliques crédibles.
- **Assets compressés Draco** : décodeur `DRACOLoader` bundlé (`three/draco/`),
  chaque bijou < 5 Mo (cf. [scripts/optimize_glb.md](scripts/optimize_glb.md)).
- **Anti-jitter** : filtre One-Euro sur (x, y, échelle, rotation).

> Choix du moteur de rendu justifié dans **[docs/decision_rendu_3d.md](docs/decision_rendu_3d.md)**
> (three.js retenu ; Filament/`thermion` = plan B si plafond de FPS constaté).

---

## Lancer l'app

```bash
flutter pub get
flutter run --release
```

> `--release` recommandé : le mode debug est 5–10× plus lent sur le pipeline
> caméra et fausse la mesure de FPS.

Checklist de validation sur device : **[docs/checklist_device.md](docs/checklist_device.md)**.

---

## Ajouter un bijou

1. Récupère/crée un `.glb` (export Blender → glTF Binary).
2. **Optimise-le** (< 5 Mo) via **[scripts/optimize_glb.md](scripts/optimize_glb.md)**.
3. Dépose-le dans le bon sous-dossier de `assets/jewelry/` :

| Type      | Dossier                       | Tracking |
|-----------|-------------------------------|----------|
| Bague     | `assets/jewelry/bagues/`      | Main     |
| Bracelet  | `assets/jewelry/bracelets/`   | Main     |
| Collier   | `assets/jewelry/colliers/`    | Visage   |
| Boucle    | `assets/jewelry/boucles/`     | Visage   |
| Perle     | `assets/jewelry/perles/`      | Visage   |

4. Le nom de fichier (`anneau_or.glb`) devient le libellé (« Anneau or »).
5. **Aucune modification de code requise.** Les sous-dossiers sont déclarés dans
   `pubspec.yaml` ; relance `flutter pub get`.

### Ajouter un nouveau type

1. Crée `assets/jewelry/<nouveau_type>/` et ajoute-le aux `assets:` du `pubspec.yaml`.
2. Si la cible diffère du défaut (main), ajoute le mapping dans
   `lib/config/tracking_config.dart` (cible + point d'ancrage + indices FaceMesh).

---

## Architecture

```
lib/
├── main.dart                       # Bootstrap + thème + caméras globales
├── models/
│   ├── jewelry_type.dart           # Modèle type (folder + libellé + icône)
│   └── jewelry_model.dart          # Modèle bijou (chemin .glb)
├── config/
│   └── tracking_config.dart        # Mapping type → main/visage + ancrage + indices FaceMesh
├── services/
│   ├── jewelry_service.dart        # Découverte assets via AssetManifest
│   ├── ar_service.dart             # Hand Landmarker + FaceMesh + flux AnchorResult
│   ├── one_euro_filter.dart        # Filtre anti-jitter (testable)
│   └── anchor_math.dart            # Géométrie d'ancrage pure (testable)
├── screens/
│   ├── jewelry_types_screen.dart   # Écran 1
│   ├── jewelry_list_screen.dart    # Écran 2
│   └── ar_view_screen.dart         # Écran 3 (caméra + overlay)
└── widgets/
    ├── jewelry_card.dart
    ├── fps_counter.dart
    ├── loading_indicator.dart
    └── ar_overlay_view.dart        # WebView three.js pilotée par AnchorResult

assets/web/
├── ar_scene.html                   # Scène three.js (overlay PBR temps réel)
└── three/                          # three.js r0.137 UMD + GLTFLoader + DRACOLoader
    └── draco/                      # décodeur Draco (les .glb sont compressés Draco)

docs/     decision_rendu_3d.md · checklist_device.md
scripts/  optimize_glb.md
test/     one_euro_filter · anchor_math · jewelry_service · widget
```

**Séparation stricte logique / UI** : les écrans ne font que composer et
brancher ; la logique (découverte, détection, mapping, filtrage) vit dans
`services/` et `config/`.

---

## Performance

- **GPU delegate MediaPipe** (main).
- **Throttle de frames** visage (garde `_busy`) : pas de file d'attente.
- **Résolution caméra** `ResolutionPreset.medium` (~720p).
- **Formats image** : NV21 (visage/ML Kit), YUV420 (main/MediaPipe).
- **Overlay isolé** : la scène three.js se met à jour sans reconstruire de widget.
- **dispose()** strict : caméra, stream, service AR ; réagit à `AppLifecycleState`.
- **Cache manifeste** lu une seule fois.

Cible : **30 FPS min** sur milieu de gamme, **60 FPS** sur haut de gamme.

---

## Permissions

- **Android** : `CAMERA` dans `android/app/src/main/AndroidManifest.xml`.
- **iOS** : `NSCameraUsageDescription` dans `ios/Runner/Info.plist`.
- À l'exécution, `permission_handler` demande la permission.

---

## Gestion des erreurs

| Cas                          | Comportement                                |
|------------------------------|---------------------------------------------|
| Permission caméra refusée    | Message dédié dans l'écran AR               |
| Aucun bijou dans le dossier  | EmptyState avec instruction de dépôt        |
| Aucun type détecté           | EmptyState avec lien vers `assets/jewelry/` |
| Pas de repère détecté        | Aperçu caméra seul, overlay masqué          |
| Échec init caméra/détecteur  | Message d'erreur dans l'écran AR            |

---

## Limitations connues

- **iOS / détection visage** : `google_mlkit_face_mesh_detection` est
  **Android-only**. Sur iOS, main + reste du pipeline OK ; le visage
  (colliers/boucles/perles) nécessitera un platform channel vers MediaPipe
  Tasks Face Landmarker natif.
- **Poids du dépôt git** : les modèles `.glb` bruts (~130 Mo) ont été commités
  dans l'historique initial. Les optimiser dans un nouveau commit n'allège pas
  le `.git` (~309 Mo) ; une purge d'historique (git-filter-repo / BFG) reste à
  décider (réécrit l'historique, force-push requis).
- **model_viewer_plus retiré** : remplacé par un overlay three.js custom capable
  d'ancrer le modèle sur des landmarks en mouvement (cf. pipeline AR).
