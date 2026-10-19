/** Libellés français des valeurs d'énumération renvoyées par l'API. */
const LABELS: Record<string, string> = {
  // Membres
  EN_ATTENTE: 'En attente', ACTIF: 'Actif', SUSPENDU: 'Suspendu', RADIE: 'Radié',
  FONDATEUR: 'Fondateur', ORDINAIRE: 'Ordinaire', BENEFICIAIRE: 'Bénéficiaire',
  // Cotisations
  BROUILLON: 'Brouillon', ARCHIVE: 'Archivé', MENSUELLE: 'Mensuelle', TRIMESTRIELLE: 'Trimestrielle',
  SEMESTRIELLE: 'Semestrielle', ANNUELLE: 'Annuelle', AUCUNE: 'Aucune', FORFAIT: 'Forfait',
  POURCENTAGE_MENSUEL: 'Pourcentage mensuel', COTISATION: 'Cotisation', PENALITE: 'Pénalité',
  A_PAYER: 'À payer', PARTIELLE: 'Partiellement payée', PAYEE: 'Payée', ANNULEE: 'Annulée',
  REMISE_PENALITE: 'Remise de pénalité', ANNULATION: 'Annulation', CORRECTION_MONTANT: 'Correction du montant',
  HALF_UP: 'Au plus proche (0,5 vers le haut)', HALF_EVEN: 'Au plus proche (pair)', UP: 'Vers le haut',
  DOWN: 'Vers le bas', CEILING: 'Plafond', FLOOR: 'Plancher', HALF_DOWN: 'Au plus proche (0,5 vers le bas)',
  // Paiements
  INITIE: 'Initié', CONFIRME: 'Confirmé', ECHOUE: 'Échoué', EXPIRE: 'Expiré', REMBOURSE: 'Remboursé',
  EN_VERIFICATION: 'En vérification', MOCK: 'Simulateur', WAVE: 'Wave', SENDFLASH: 'SendFlash', MANUEL: 'Manuel',
  // Lots
  EN_PREPARATION: 'En préparation', DISPONIBLE: 'Disponible', RESERVE: 'Réservé', ATTRIBUE: 'Attribué',
  LITIGIEUX: 'Litigieux', INDISPONIBLE: 'Indisponible', TERRAIN: 'Terrain', VILLA: 'Villa',
  APPARTEMENT: 'Appartement', DUPLEX: 'Duplex', LOCAL: 'Local', ETUDE: 'À l’étude', EN_COURS: 'En cours',
  LIVRE: 'Livré',
  // Attribution
  TIRAGE_AU_SORT: 'Tirage au sort', COMMISSION: 'Décision de commission', CRITERES_PUBLIES: 'Critères publiés',
  CANDIDATURES_OUVERTES: 'Candidatures ouvertes', CANDIDATURES_CLOSES: 'Candidatures closes',
  LISTE_PUBLIEE: 'Liste publiée', VALIDEE: 'Liste validée', EXECUTEE: 'Exécutée', PV_VALIDE: 'PV validé',
  RESULTATS_PUBLIES: 'Résultats publiés', ARCHIVEE: 'Archivée', A_VERIFIER: 'À vérifier', ELIGIBLE: 'Éligible',
  NON_ELIGIBLE: 'Non éligible', PROVISOIRE: 'Provisoire', DEFINITIF: 'Définitif', LISTE_ATTENTE: 'Liste d’attente',
  ANNULE: 'Annulé', DEPOSEE: 'Déposée', EN_EXAMEN: 'En examen', REJETEE: 'Rejetée', ACCEPTEE: 'Acceptée',
  VALIDATION_LISTE: 'Validation de la liste', VALIDATION_PV: 'Validation du PV',
  DECISION_COMMISSION: 'Décision de la commission', ANNULATION_RESULTAT: 'Annulation d’un résultat',
  APPROUVE: 'Approuvé', REFUSE: 'Refusé',
  // Assemblées
  EXTRAORDINAIRE: 'Extraordinaire', PROGRAMMEE: 'Programmée', CONVOQUEE: 'Convoquée', TENUE: 'Tenue',
  CLOTUREE: 'Clôturée', PRESENTIEL: 'Présentiel', EN_LIGNE: 'En ligne', MIXTE: 'Mixte', PRESENT: 'Présent',
  REPRESENTE: 'Représenté', ABSENT: 'Absent', EXCUSE: 'Excusé', ADOPTEE: 'Adoptée', AJOURNEE: 'Ajournée',
  PUBLIE: 'Publié', REMPLACE: 'Remplacé',
  // Finances
  SOUMISE: 'Soumise', APPROUVEE: 'Approuvée', ADOPTE: 'Adopté', CLOTURE: 'Clôturé', RECETTE: 'Recette',
  DEPENSE: 'Dépense', DECLARATION: 'Déclaration', RELEVE_IMPORTE: 'Relevé importé', SEMESTRIEL: 'Semestriel',
  ANNUEL: 'Annuel', PERIODE: 'Période', CONCORDANT: 'Concordant', ECART: 'Écart constaté',
  AUCUN_SOLDE_DECLARE: 'Aucun solde déclaré',
  FOURNITURES: 'Fournitures', DEPLACEMENT: 'Déplacements', HONORAIRES_ETUDES: 'Honoraires et études',
  FRAIS_ADMINISTRATIFS: 'Frais administratifs', FRAIS_BANCAIRES: 'Frais bancaires',
  ACQUISITION_TERRAIN: 'Acquisition de terrain', CONSTRUCTION: 'Construction', DIVERS: 'Divers',
  COTISATIONS: 'Cotisations', PENALITES: 'Pénalités',
  // Documents
  STATUTS: 'Statuts', REGLEMENT: 'Règlement intérieur', CONVOCATION: 'Convocation',
  COMPTE_RENDU_AG: 'Compte rendu d’AG', PV_ATTRIBUTION: 'PV d’attribution', RECU: 'Reçu',
  RAPPORT_FINANCIER: 'Rapport financier', JUSTIFICATIF_DEPENSE: 'Justificatif de dépense',
  DOCUMENT_FONCIER: 'Document foncier', DOCUMENT_MEMBRE: 'Document du membre', AUTRE: 'Autre',
  PUBLIC: 'Public', MEMBRES: 'Membres', BUREAU: 'Bureau', PRIVE_MEMBRE: 'Privé (membre concerné)',
  // Notifications
  ENVOYE: 'Envoyé', EMAIL: 'Courriel', SMS: 'SMS', IN_APP: 'Dans l’application',
  // Données personnelles
  ACCES: 'Accès', RECTIFICATION: 'Rectification', OPPOSITION: 'Opposition', SUPPRESSION: 'Suppression',
  PORTABILITE: 'Portabilité', RECUE: 'Reçue', TRAITEE: 'Traitée',
  // Audit
  SECURITE: 'Sécurité', FINANCE: 'Finances', MEMBRE: 'Membre', ATTRIBUTION: 'Attribution', LOT: 'Lot',
  DOCUMENT: 'Document', ASSEMBLEE: 'Assemblée', ADMIN: 'Administration', NOTIFICATION: 'Notification',
  DONNEES: 'Données personnelles',
};

export function label(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return LABELS[value] ?? value.replaceAll('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'neutral';

const OK = ['ACTIF', 'PAYEE', 'CONFIRME', 'DISPONIBLE', 'ELIGIBLE', 'DEFINITIF', 'ACCEPTEE', 'APPROUVE', 'APPROUVEE',
  'ADOPTEE', 'ADOPTE', 'PUBLIE', 'PAYEE', 'CONCORDANT', 'TRAITEE', 'ENVOYE', 'LIVRE', 'PRESENT', 'RESULTATS_PUBLIES',
  'PV_VALIDE', 'CLOTUREE', 'ATTRIBUE'];
const WARN = ['EN_ATTENTE', 'PARTIELLE', 'A_PAYER', 'EN_VERIFICATION', 'INITIE', 'RESERVE', 'A_VERIFIER', 'PROVISOIRE',
  'SOUMISE', 'DEPOSEE', 'EN_EXAMEN', 'LISTE_ATTENTE', 'SUSPENDU', 'ECART', 'RECUE', 'EN_COURS', 'REPRESENTE',
  'CANDIDATURES_OUVERTES', 'CONVOQUEE', 'AJOURNEE', 'EXCUSE'];
const BAD = ['RADIE', 'ECHOUE', 'EXPIRE', 'ANNULEE', 'ANNULE', 'LITIGIEUX', 'NON_ELIGIBLE', 'REJETEE', 'REFUSE',
  'REMBOURSE', 'INDISPONIBLE', 'ABSENT', 'AUCUN_SOLDE_DECLARE'];

export function tone(value: string | null | undefined): Tone {
  if (!value) return 'neutral';
  if (OK.includes(value)) return 'ok';
  if (WARN.includes(value)) return 'warn';
  if (BAD.includes(value)) return 'bad';
  return 'info';
}

export const ROLE_LABELS: Record<string, string> = {
  MEMBRE: 'Membre', PRESIDENT: 'Président', TRESORIER: 'Trésorier', SECRETAIRE: 'Secrétaire',
  AUDITEUR: 'Auditeur', AGENT_DE_GESTION: 'Agent de gestion',
  ADMINISTRATEUR_TECHNIQUE: 'Administrateur technique',
};

export function enumOptions(values: string[]): { value: string; label: string }[] {
  return values.map((v) => ({ value: v, label: label(v) }));
}
