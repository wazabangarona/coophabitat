/**
 * Photographies d'illustration embarquées dans l'application (aucune image distante : la politique de sécurité
 * n'autorise que les ressources du site). Chaque entrée conserve sa source et sa licence ; elles sont reprises
 * sur la page « Crédits photographiques ». Aucune de ces images ne représente un projet réel de la coopérative.
 *
 * Fichiers attendus dans public/images : <nom>-800.webp, <nom>-1400.webp, <nom>-2000.webp et <nom>-1400.jpg
 * (produits par scripts/optimize-photos.mjs). Une entrée absente affiche un visuel de remplacement sobre.
 */
export interface PhotoCredit {
  /** Texte alternatif décrivant ce que montre réellement la photographie. */
  alt: string;
  author: string;
  /** Page de la photographie chez la banque d'images. */
  source: string;
  license: string;
  /** Point focal pour object-position (ex. « 50% 40% »). */
  focus?: string;
  width: number;
  height: number;
}

export const PHOTOS: Record<string, PhotoCredit> = {};

/** Emplacements de l'application et photographie retenue pour chacun (ou null tant qu'aucune n'est disponible). */
export const SLOTS = {
  hero: null as string | null,
  contributions: null as string | null,
  projects: null as string | null,
  living: null as string | null,
  auth: null as string | null,
};
