package sn.coophabitat.document;

/**
 * PUBLIC : visible sans connexion une fois publié.
 * MEMBRES : visible par tout membre connecté une fois publié.
 * BUREAU : réservé aux rôles disposant de DOCUMENT_READ_BUREAU.
 * PRIVE_MEMBRE : visible par le membre propriétaire et le bureau uniquement.
 */
public enum DocumentVisibility { PUBLIC, MEMBRES, BUREAU, PRIVE_MEMBRE }
