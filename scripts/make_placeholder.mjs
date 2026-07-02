/*
 * Génère des .glb placeholder minimaux (torus PBR doré) pour exercer le
 * pipeline visage de bout en bout dans les catégories encore sans modèle.
 *
 * Usage :
 *   npm install --no-save @gltf-transform/core   # dépendance dev temporaire
 *   node scripts/make_placeholder.mjs
 *   # (node_modules/ est gitignoré)
 *
 * Produit un petit tore métallique (~quelques Ko) dans chaque catégorie cible.
 * À remplacer par de vrais bijoux optimisés (cf. scripts/optimize_glb.md).
 */
import { Document, NodeIO } from '@gltf-transform/core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

// --- Géométrie d'un tore (anneau) paramétrique ---------------------------
function torus(R = 0.5, r = 0.18, segU = 48, segV = 24) {
  const pos = [];
  const nrm = [];
  const idx = [];
  for (let i = 0; i <= segU; i++) {
    const u = (i / segU) * Math.PI * 2;
    const cu = Math.cos(u), su = Math.sin(u);
    for (let j = 0; j <= segV; j++) {
      const v = (j / segV) * Math.PI * 2;
      const cv = Math.cos(v), sv = Math.sin(v);
      const x = (R + r * cv) * cu;
      const y = (R + r * cv) * su;
      const z = r * sv;
      pos.push(x, y, z);
      nrm.push(cv * cu, cv * su, sv);
    }
  }
  const stride = segV + 1;
  for (let i = 0; i < segU; i++) {
    for (let j = 0; j < segV; j++) {
      const a = i * stride + j;
      const b = (i + 1) * stride + j;
      idx.push(a, b, a + 1, a + 1, b, b + 1);
    }
  }
  return {
    position: new Float32Array(pos),
    normal: new Float32Array(nrm),
    indices: new Uint16Array(idx),
  };
}

function buildGlb(color) {
  const doc = new Document();
  const buffer = doc.createBuffer();
  const g = torus();

  const position = doc.createAccessor()
    .setType('VEC3').setArray(g.position).setBuffer(buffer);
  const normal = doc.createAccessor()
    .setType('VEC3').setArray(g.normal).setBuffer(buffer);
  const indices = doc.createAccessor()
    .setType('SCALAR').setArray(g.indices).setBuffer(buffer);

  const material = doc.createMaterial('placeholder')
    .setBaseColorFactor(color)
    .setMetallicFactor(1.0)
    .setRoughnessFactor(0.3);

  const prim = doc.createPrimitive()
    .setAttribute('POSITION', position)
    .setAttribute('NORMAL', normal)
    .setIndices(indices)
    .setMaterial(material);

  const mesh = doc.createMesh('placeholder').addPrimitive(prim);
  const node = doc.createNode('placeholder').setMesh(mesh);
  doc.createScene().addChild(node);
  return doc;
}

const targets = [
  { path: 'assets/jewelry/colliers/placeholder_collier.glb', color: [0.83, 0.68, 0.21, 1] },
  { path: 'assets/jewelry/boucles/placeholder_boucle.glb',  color: [0.90, 0.90, 0.95, 1] },
  { path: 'assets/jewelry/perles/placeholder_perle.glb',    color: [0.95, 0.93, 0.88, 1] },
];

const io = new NodeIO();
for (const t of targets) {
  const doc = buildGlb(t.color);
  const glb = await io.writeBinary(doc);
  mkdirSync(dirname(t.path), { recursive: true });
  writeFileSync(t.path, glb);
  console.log(`écrit ${t.path} (${glb.byteLength} octets)`);
}
