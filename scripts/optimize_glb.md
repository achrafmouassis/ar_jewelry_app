# Pipeline d'optimisation des modèles `.glb`

> Objectif : **< 5 Mo par bijou** (idéalement 1–3 Mo) sans dégradation visuelle
> perceptible, pour un chargement rapide dans la scène three.js AR.

Les modèles bruts (export Meshy AI / Blender) pèsent souvent 50–80 Mo :
géométrie non compressée + textures 4K non compressées. On les réduit via
[`gltf-transform`](https://gltf-transform.dev/) (Node.js).

## Prérequis (une fois)

```bash
# Node.js >= 18 requis
npm install -g @gltf-transform/cli
# Encodeurs optionnels tirés à la volée par npx si non installés :
#  - draco (compression géométrie)
#  - KTX-Software / toktx (compression textures KTX2/Basis)
```

> Sans installation globale, préfixer chaque commande par `npx` :
> `npx @gltf-transform/cli ...`

## Procédure retenue (validée sur les modèles du projet)

Les modèles Meshy sont **très haute densité** (la bague : 1,75 M triangles).
Le poste dominant est donc la **géométrie**, pas les textures — c'est la
**compression Draco** qui fait l'essentiel du gain (la simplification seule
plafonne à cause du verrouillage des bordures sur ces maillages multi-coques).

Pour chaque fichier `entree.glb` :

```bash
# 1) Inspection avant (poids, triangles, textures, résolution)
npx --yes @gltf-transform/cli inspect entree.glb

# 2) Pipeline : simplify léger + Draco (géométrie) + WebP (textures) 1024
npx --yes @gltf-transform/cli optimize entree.glb sortie.glb \
  --compress draco \
  --texture-compress webp \
  --texture-size 1024 \
  --simplify-ratio 0.2 \
  --simplify-error 0.01

# 3) Vérification après (doit être < 5 Mo)
npx --yes @gltf-transform/cli inspect sortie.glb
```

Résultats obtenus sur ce projet :

| Modèle    | Avant     | Après (Draco+WebP) |
|-----------|-----------|--------------------|
| Bague     | 71,74 Mo  | **2,32 Mo**        |
| Bracelet  | 58,69 Mo  | **1,79 Mo**        |

### Leviers

| Levier | Effet poids | Risque visuel |
|---|---|---|
| `--compress draco` | **Fort** (géométrie) | Quasi nul |
| `--texture-compress webp` | Fort (textures) | Léger, imperceptible ici |
| `--texture-size 512/1024` | Moyen | Perte de détail si gros plan |
| `--simplify-ratio` | Moyen | Peut plafonner (bordures verrouillées) |
| `--simplify-lock-border false` | + de réduction | Petits trous possibles |

> ⚠️ **Alternative sans décodeur** : remplacer `--compress draco` par
> `--compress quantize` (KHR_mesh_quantization, supporté nativement par
> GLTFLoader) évite d'embarquer le décodeur Draco, mais donne des fichiers
> nettement plus gros (~6 Mo ici) — insuffisant pour la cible < 5 Mo.

## ⚙️ Prérequis côté rendu : décodeur Draco

Comme on compresse en Draco, la scène three.js doit décoder le Draco. C'est
déjà câblé dans `assets/web/ar_scene.html` :

```js
var draco = new THREE.DRACOLoader();
draco.setDecoderPath('three/draco/'); // décodeur bundlé localement
gltfLoader.setDRACOLoader(draco);
```

Le décodeur (`assets/web/three/draco/`) est bundlé — aucune requête réseau.
Si le décodage Draco pose problème en WebView sur certains devices (Web Workers),
repli possible : ré-exporter les assets en `--compress quantize` et retirer le
`DRACOLoader` de la scène.

## Contrôle qualité obligatoire

1. `gltf-transform inspect sortie.glb` → confirmer **< 5 Mo**.
2. Ouvrir `sortie.glb` dans <https://gltf-viewer.donmccurdy.com/> ou dans l'app
   (écran AR) → vérifier reflets métalliques, couleurs, absence d'artefacts.
3. **KTX2 + three.js** : la scène doit charger `KTX2Loader` si des textures KTX2
   sont présentes. ⚠️ Si le rendu des textures échoue en WebView, retomber sur
   des textures classiques compressées (`--texture-compress webp`) plutôt que KTX2.

## Application aux modèles existants

| Modèle | Avant | Cible |
|---|---|---|
| `bagues/Meshy_AI_Diamond_Flower_Ring_...glb` | ~71 Mo | < 5 Mo |
| `bracelets/Meshy_AI_Gold_Filigree_Leaf_...glb` | ~58 Mo | < 5 Mo |

> Remplacer le fichier d'origine par la version optimisée (même nom, même
> dossier) : la découverte automatique via AssetManifest reste inchangée.

## Note historique git

Les modèles bruts (~130 Mo) sont **déjà commités** dans l'historique : les
remplacer par des versions optimisées n'allège **pas** le `.git` (~309 Mo).
Purge d'historique (git-filter-repo / BFG) = opération séparée à décider
explicitement (réécrit l'historique, force-push requis). Voir README.
