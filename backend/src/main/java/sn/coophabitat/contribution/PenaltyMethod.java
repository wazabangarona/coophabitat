package sn.coophabitat.contribution;

/**
 * AUCUNE : pas de pénalité.
 * FORFAIT : montant fixe appliqué une seule fois après la période de grâce.
 * POURCENTAGE_MENSUEL : pourcentage du principal restant dû, appliqué à chaque mois de retard entamé
 * après la période de grâce, dans la limite du plafond.
 * Les paramètres doivent refléter les règles effectivement adoptées par la coopérative.
 */
public enum PenaltyMethod { AUCUNE, FORFAIT, POURCENTAGE_MENSUEL }
