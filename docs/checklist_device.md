# Checklist de test manuel sur device

> À dérouler sur un appareil **physique** Android (et iOS quand FaceMesh iOS
> sera couvert — cf. limitation ci-dessous). L'émulateur ne fournit pas de vrai
> flux caméra exploitable pour le tracking.
>
> Build recommandé : `flutter run --release` (le mode debug est 5–10× plus lent
> sur le pipeline caméra et fausse la mesure de FPS).

## 0. Pré-requis

- [ ] Device milieu de gamme minimum (cible de perf de référence).
- [ ] Au moins 1 `.glb` **optimisé** (< 5 Mo) dans chaque catégorie.
- [ ] Bonne luminosité ambiante pour la première passe.

## 1. Permissions

- [ ] Premier lancement → la demande de permission caméra s'affiche.
- [ ] Refus → l'écran AR affiche « Permission caméra refusée. » (pas de crash).
- [ ] Acceptation → l'aperçu caméra démarre.
- [ ] Refus puis réglages système → activer la permission → retour app OK.

## 2. Navigation & découverte des assets

- [ ] Écran 1 : les 5 catégories non vides apparaissent (grille).
- [ ] Écran 2 : la liste des `.glb` du type s'affiche, libellés lisibles.
- [ ] Une catégorie sans `.glb` n'apparaît pas (ou EmptyState cohérent).

## 3. Tracking MAIN (bagues, bracelets) — Hand Landmarker

- [ ] Bague : le modèle se pose entre annulaire et phalange, suit le doigt.
- [ ] Bracelet : le modèle se cale au poignet.
- [ ] Rotation de la main → le bijou suit sans décrochage brutal.
- [ ] Éloignement/rapprochement → l'échelle du bijou s'adapte.
- [ ] Main hors champ → l'overlay disparaît ; retour main → ré-accroche net.

## 4. Tracking VISAGE (colliers, boucles, perles) — FaceMesh 468

- [ ] Boucles : ancrage au niveau du lobe d'oreille.
- [ ] Collier : ancrage sous le menton / base du cou, suit la tête.
- [ ] Perles/diadème : ancrage sur le front.
- [ ] Inclinaison de la tête (roll) → le bijou tourne avec (roulis correct).
- [ ] Vérifier le sens du miroir (caméra frontale) : le bijou est du bon côté.
- [ ] Visage hors champ → overlay masqué ; retour → ré-accroche.

## 5. Rendu PBR

- [ ] Or/argent : reflets métalliques visibles (pas un aplat mat « sticker »).
- [ ] Le bijou réagit à l'orientation (les reflets se déplacent).
- [ ] Fond de l'overlay parfaitement transparent (pas de rectangle/halo).

## 6. Stabilité (anti-jitter One-Euro)

- [ ] Tête/main immobiles → le bijou ne « vibre » pas.
- [ ] Mouvement rapide → le bijou suit avec une latence acceptable.

## 7. Performance

- [ ] FPS affiché (compteur haut-gauche) ≥ **30** sur milieu de gamme.
- [ ] ≥ **60** sur haut de gamme (Snapdragon 8 Gen 1 / iPhone 12+).
- [ ] Pas de fuite mémoire visible sur 2–3 min d'utilisation continue.

## 8. Cycle de vie

- [ ] App en arrière-plan → caméra libérée (voyant caméra éteint).
- [ ] Retour au premier plan → la caméra et le tracking reprennent (pas d'écran noir).
- [ ] Rotation refusée : l'app reste en portrait.
- [ ] Retour arrière depuis l'écran AR → caméra correctement libérée.

## 9. Conditions dégradées

- [ ] Faible luminosité → détection dégradée mais pas de crash.
- [ ] Plusieurs visages → un seul bijou ancré (premier visage), stable.
- [ ] Modèle `.glb` volumineux → temps de chargement acceptable (ou spinner).

## 10. Confidentialité (contrainte RGPD)

- [ ] Vérifier (proxy réseau / mode avion) qu'**aucune** donnée image ne sort :
      le tracking et le rendu fonctionnent **hors ligne** (three.js bundlé,
      MediaPipe/ML Kit locaux).

---

## Limitation connue — iOS

`google_mlkit_face_mesh_detection` est **Android-only** aujourd'hui. Sur iOS, la
détection visage (colliers/boucles/perles) n'est pas encore couverte ; la
détection main et tout le reste du pipeline fonctionnent. Repli iOS à traiter :
platform channel vers MediaPipe Tasks Face Landmarker natif. La section 4 de
cette checklist est donc **Android-only** en l'état.
