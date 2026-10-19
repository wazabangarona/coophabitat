#!/usr/bin/env node
/**
 * Prépare les photographies d'illustration pour le site : recadrage léger, trois largeurs WebP et une version JPEG
 * de repli, sans métadonnées. Usage (sharp n'est pas une dépendance du projet) :
 *
 *   npm i --no-save sharp && node scripts/optimize-photos.mjs <dossier-source> nom=fichier.jpg [nom=fichier.jpg …]
 *
 * Les fichiers produits vont dans public/images ; ajoutez ensuite l'entrée correspondante dans src/app/ui/photos.ts
 * (auteur, page source, licence, texte alternatif). N'utilisez que des images dont la licence autorise cet usage.
 */
import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import sharp from 'sharp';

const [srcDir, ...pairs] = process.argv.slice(2);
if (!srcDir || !pairs.length) {
  console.error('Usage : node scripts/optimize-photos.mjs <dossier-source> nom=fichier.jpg …');
  process.exit(1);
}
const out = resolve(import.meta.dirname, '../public/images');
mkdirSync(out, { recursive: true });

for (const pair of pairs) {
  const [name, file] = pair.split('=');
  const input = sharp(join(srcDir, file)).rotate();
  const meta = await input.metadata();
  for (const w of [800, 1400, 2000]) {
    const width = Math.min(w, meta.width ?? w);
    await input.clone().resize({ width }).webp({ quality: w >= 2000 ? 70 : 76, effort: 6 }).toFile(join(out, `${name}-${w}.webp`));
  }
  const jpg = await input.clone().resize({ width: Math.min(1400, meta.width ?? 1400) }).jpeg({ quality: 78, mozjpeg: true, progressive: true })
    .toFile(join(out, `${name}-1400.jpg`));
  console.log(`${name} : ${meta.width}×${meta.height} → ${jpg.width}×${jpg.height} (JPEG ${Math.round(jpg.size / 1024)} Ko)`);
}
