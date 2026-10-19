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
  /**
   * « plan » (dessin d'architecte) et « maquette » (écrans d'interface) sont affichés en entier, sans recadrage, et
   * légendés comme tels ; une photographie est recadrée pour remplir son emplacement.
   */
  kind?: 'photo' | 'plan' | 'maquette';
  width: number;
  height: number;
}

export const PHOTOS: Record<string, PhotoCredit> = {
  'chantier': {
    alt: 'Chantier de maisons à étage en gros œuvre au coucher du soleil, deux techniciens en gilet consultant un plan',
    author: 'Visuel fourni par la coopérative',
    credit: 'ne représente pas un chantier de la coopérative',
    license: 'Usage autorisé par la coopérative pour illustrer ce site',
    focus: '40% 55%',
    width: 1536,
    height: 1024,
  },
  'portail-membre': {
    alt: 'Une membre consulte son espace sur un ordinateur portable, à côté d’aperçus des rubriques documents, rapports et comptes, et espace personnel',
    author: 'Visuel fourni par la coopérative',
    credit: 'noms, dates et documents fictifs',
    license: 'Usage autorisé par la coopérative pour illustrer ce site. Les écrans montrés sont une maquette, pas des données réelles.',
    kind: 'maquette',
    width: 1024,
    height: 595,
  },
  'villa-crepuscule': {
    alt: 'Maison contemporaine à étage éclairée au crépuscule, avec portail, palmiers et jardin tropical',
    author: 'Visuel fourni par la coopérative',
    credit: 'visuel fourni par la coopérative',
    license: 'Usage autorisé par la coopérative pour illustrer ce site',
    focus: '45% 55%',
    width: 1536,
    height: 1024,
  },
  'plan-rdc': {
    alt: 'Plan d’architecte d’une maison de plain-pied : trois chambres, séjour, cuisine, garage, terrasse et piscine, avec tableau des surfaces',
    author: 'Plan fourni par la coopérative',
    credit: 'ne représente pas un projet de la coopérative',
    license: 'Usage autorisé par la coopérative pour illustrer ce site. Ce n’est ni un plan de projet ni un document cadastral.',
    kind: 'plan',
    width: 1536,
    height: 1024,
  },
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
  hero: 'plan-rdc' as string | null,
  contributions: 'villas-palmiers' as string | null,
  projects: 'chantier' as string | null,
  living: 'portail-membre' as string | null,
  auth: 'villa-crepuscule' as string | null,
};
