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
  /** Mention courte affichée sous l'image (ex. « Auteur / Unsplash »). */
  credit: string;
  /** Page de la photographie chez la banque d'images, si elle en vient. */
  source?: string;
  license: string;
  /** Point focal pour object-position (ex. « 50% 40% »). */
  focus?: string;
  width: number;
  height: number;
}

export const PHOTOS: Record<string, PhotoCredit> = {
  'villas-palmiers': {
    alt: 'Villas contemporaines à étage autour d’une piscine, entourées de palmiers, au coucher du soleil',
    author: 'Visuel fourni par la coopérative',
    credit: 'visuel fourni par la coopérative',
    license: 'Usage autorisé par la coopérative pour illustrer ce site',
    focus: '50% 60%',
    width: 1122,
    height: 1402,
  },
};

/** Emplacements de l'application et photographie retenue pour chacun (ou null tant qu'aucune n'est disponible). */
export const SLOTS = {
  hero: 'villas-palmiers' as string | null,
  contributions: null as string | null,
  projects: null as string | null,
  living: null as string | null,
  auth: 'villas-palmiers' as string | null,
};
