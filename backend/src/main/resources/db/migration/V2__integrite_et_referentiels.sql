-- =====================================================================
-- CoopHabitat — règles d'intégrité au niveau base et référentiels
-- =====================================================================

-- ---------------------------------------------------------------------
-- Immutabilité : les tables de journal refusent UPDATE / DELETE / TRUNCATE.
-- Toute correction passe par une contre-écriture ou un nouvel enregistrement.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ch_forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % en ajout seul : % interdit (utiliser une contre-écriture)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['accounting_entry','accounting_line','audit_event','payment_allocation',
                             'membership_status_history','lot_status_history','obligation_adjustment',
                             'expense_approval','allocation_approval','notification_attempt']
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ch_forbid_modification()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ch_forbid_modification()', t, t);
    END LOOP;
END $$;

-- Pas de suppression physique pour les données financières et administratives
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['member','payment_intent','payment_transaction','receipt','member_obligation',
                             'expense','financial_report','property_lot','allocation_campaign',
                             'allocation_result','allocation_candidate','document','general_assembly','resolution']
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ch_forbid_modification()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ch_forbid_modification()', t, t);
    END LOOP;
END $$;

-- Une transaction confirmée ne peut changer que vers REMBOURSE ; montant, membre,
-- fournisseur et caractère simulé sont figés.
CREATE OR REPLACE FUNCTION ch_payment_transaction_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.amount <> OLD.amount OR NEW.member_id <> OLD.member_id OR NEW.provider <> OLD.provider
       OR NEW.simulated <> OLD.simulated OR NEW.intent_id <> OLD.intent_id
       OR NEW.confirmed_at <> OLD.confirmed_at OR NEW.reference <> OLD.reference
       OR COALESCE(NEW.provider_transaction_id,'') <> COALESCE(OLD.provider_transaction_id,'') THEN
        RAISE EXCEPTION 'Les données d''une transaction confirmée sont immuables'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'REMBOURSE' AND NEW.status <> 'REMBOURSE' THEN
        RAISE EXCEPTION 'Une transaction remboursée ne peut pas redevenir confirmée'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_payment_transaction_guard BEFORE UPDATE ON payment_transaction
    FOR EACH ROW EXECUTE FUNCTION ch_payment_transaction_guard();

-- Le reçu est figé une fois émis (seul le lien vers le document archivé peut être posé une fois)
CREATE OR REPLACE FUNCTION ch_receipt_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.receipt_number <> OLD.receipt_number OR NEW.transaction_id <> OLD.transaction_id
       OR NEW.verification_code <> OLD.verification_code OR NEW.simulated <> OLD.simulated
       OR (OLD.document_id IS NOT NULL AND NEW.document_id IS DISTINCT FROM OLD.document_id) THEN
        RAISE EXCEPTION 'Un reçu émis est immuable' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_receipt_guard BEFORE UPDATE ON receipt FOR EACH ROW EXECUTE FUNCTION ch_receipt_guard();

-- Le contenu d'un document archivé (empreinte, stockage) est figé
CREATE OR REPLACE FUNCTION ch_document_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.sha256 <> OLD.sha256 OR NEW.storage_key <> OLD.storage_key OR NEW.size_bytes <> OLD.size_bytes THEN
        RAISE EXCEPTION 'Le contenu d''un document archivé est immuable' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_guard BEFORE UPDATE ON document FOR EACH ROW EXECUTE FUNCTION ch_document_guard();

-- Partie double : chaque écriture doit être équilibrée et comporter au moins deux lignes,
-- vérifié en fin de transaction (contrainte différée).
CREATE OR REPLACE FUNCTION ch_check_entry_balanced() RETURNS trigger AS $$
DECLARE d NUMERIC; c NUMERIC; n INTEGER;
BEGIN
    SELECT COALESCE(SUM(debit),0), COALESCE(SUM(credit),0), COUNT(*) INTO d, c, n
      FROM accounting_line WHERE entry_id = NEW.entry_id;
    IF n < 2 OR d <> c OR d = 0 THEN
        RAISE EXCEPTION 'Écriture % déséquilibrée (débit %, crédit %, lignes %)', NEW.entry_id, d, c, n
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_accounting_line_balanced AFTER INSERT ON accounting_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ch_check_entry_balanced();

-- ---------------------------------------------------------------------
-- Référentiel : rôles
-- ---------------------------------------------------------------------
INSERT INTO role (code, label, description, technical) VALUES
 ('PRESIDENT', 'Président', 'Pilotage, validation des dépenses et des procédures d''attribution', FALSE),
 ('TRESORIER', 'Trésorier', 'Plans de cotisation, encaissements, comptabilité et rapports', FALSE),
 ('SECRETAIRE', 'Secrétaire', 'Dossiers des membres, assemblées, documents, procédures d''attribution', FALSE),
 ('MEMBRE', 'Membre', 'Accès à son espace personnel et aux informations publiées aux membres', FALSE),
 ('AGENT_DE_GESTION', 'Agent de gestion', 'Saisie administrative sous contrôle du bureau', FALSE),
 ('AUDITEUR', 'Auditeur', 'Consultation en lecture seule et vérification des procédures', FALSE),
 ('ADMINISTRATEUR_TECHNIQUE', 'Administrateur technique', 'Gestion technique des comptes et paramètres, sans droit financier', TRUE);

-- ---------------------------------------------------------------------
-- Référentiel : permissions (doit rester synchronisé avec l'énumération Java Permission)
-- ---------------------------------------------------------------------
INSERT INTO permission (code, description) VALUES
 ('SELF_SERVICE', 'Consulter son propre espace membre'),
 ('DASHBOARD_BUREAU', 'Accéder aux tableaux de bord du bureau'),
 ('MEMBER_READ_ALL', 'Consulter les dossiers de tous les membres'),
 ('MEMBER_WRITE', 'Créer et modifier les dossiers des membres'),
 ('MEMBER_STATUS_CHANGE', 'Changer le statut d''un membre'),
 ('MEMBER_EXPORT', 'Exporter la liste des membres'),
 ('PLAN_READ', 'Consulter les plans de cotisation'),
 ('PLAN_WRITE', 'Créer et activer des plans de cotisation'),
 ('OBLIGATION_GENERATE', 'Générer les échéances et appliquer les pénalités'),
 ('OBLIGATION_ADJUST', 'Ajuster une échéance (dérogation motivée)'),
 ('PAYMENT_READ_ALL', 'Consulter tous les paiements'),
 ('PAYMENT_MANUAL_RECORD', 'Saisir un encaissement manuel (espèces, virement)'),
 ('PAYMENT_MANUAL_VERIFY', 'Vérifier et confirmer un encaissement manuel saisi par un autre utilisateur'),
 ('PAYMENT_REFUND', 'Rembourser un paiement confirmé'),
 ('PAYMENT_RECONCILE', 'Relancer la vérification des paiements auprès du fournisseur'),
 ('ACCOUNTING_READ', 'Consulter la comptabilité'),
 ('ACCOUNTING_WRITE', 'Passer des écritures manuelles et contre-écritures'),
 ('EXPENSE_CREATE', 'Saisir des dépenses'),
 ('EXPENSE_APPROVE', 'Approuver ou rejeter des dépenses'),
 ('EXPENSE_PAY', 'Enregistrer le paiement d''une dépense approuvée'),
 ('BUDGET_WRITE', 'Établir le budget prévisionnel'),
 ('BANK_RECONCILE', 'Déclarer les soldes bancaires et rapprocher les relevés'),
 ('REPORT_GENERATE', 'Générer les rapports financiers'),
 ('REPORT_PUBLISH', 'Publier les rapports financiers aux membres'),
 ('LOT_READ_ALL', 'Consulter les informations internes des lots'),
 ('LOT_WRITE', 'Créer et modifier les programmes et lots'),
 ('LOT_STATUS_CHANGE', 'Changer le statut d''un lot (hors attribution)'),
 ('ALLOCATION_READ_ALL', 'Consulter toutes les procédures d''attribution'),
 ('ALLOCATION_MANAGE', 'Préparer et conduire une procédure d''attribution'),
 ('ALLOCATION_APPROVE', 'Valider une étape d''une procédure d''attribution'),
 ('ALLOCATION_EXECUTE', 'Exécuter le tirage au sort validé'),
 ('ALLOCATION_CONTEST_DECIDE', 'Statuer sur une contestation'),
 ('ALLOCATION_VERIFY', 'Vérifier a posteriori un tirage au sort'),
 ('ASSEMBLY_MANAGE', 'Programmer et documenter les assemblées générales'),
 ('DOCUMENT_MANAGE', 'Téléverser et publier des documents'),
 ('DOCUMENT_READ_BUREAU', 'Consulter les documents réservés au bureau'),
 ('NOTIFICATION_MANAGE', 'Superviser la file de notifications'),
 ('AUDIT_READ', 'Consulter le journal d''audit'),
 ('USER_ADMIN', 'Gérer les comptes utilisateurs et leurs rôles'),
 ('SETTINGS_ADMIN', 'Modifier les paramètres de la coopérative'),
 ('DATA_REQUEST_HANDLE', 'Traiter les demandes relatives aux données personnelles');

INSERT INTO role_permission (role_code, permission_code)
SELECT 'PRESIDENT', p FROM unnest(ARRAY['DASHBOARD_BUREAU','MEMBER_READ_ALL','MEMBER_STATUS_CHANGE','PLAN_READ',
  'PAYMENT_READ_ALL','PAYMENT_MANUAL_VERIFY','ACCOUNTING_READ','EXPENSE_APPROVE','REPORT_PUBLISH','LOT_READ_ALL',
  'ALLOCATION_READ_ALL','ALLOCATION_APPROVE','ALLOCATION_EXECUTE','ALLOCATION_CONTEST_DECIDE','DOCUMENT_READ_BUREAU',
  'AUDIT_READ','DATA_REQUEST_HANDLE']) AS p
UNION ALL
SELECT 'TRESORIER', p FROM unnest(ARRAY['DASHBOARD_BUREAU','MEMBER_READ_ALL','MEMBER_EXPORT','PLAN_READ','PLAN_WRITE',
  'OBLIGATION_GENERATE','OBLIGATION_ADJUST','PAYMENT_READ_ALL','PAYMENT_MANUAL_RECORD','PAYMENT_REFUND',
  'PAYMENT_RECONCILE','ACCOUNTING_READ','ACCOUNTING_WRITE','EXPENSE_CREATE','EXPENSE_PAY','BUDGET_WRITE',
  'BANK_RECONCILE','REPORT_GENERATE','LOT_READ_ALL','ALLOCATION_READ_ALL','DOCUMENT_READ_BUREAU','NOTIFICATION_MANAGE']) AS p
UNION ALL
SELECT 'SECRETAIRE', p FROM unnest(ARRAY['DASHBOARD_BUREAU','MEMBER_READ_ALL','MEMBER_WRITE','MEMBER_STATUS_CHANGE',
  'MEMBER_EXPORT','PLAN_READ','LOT_READ_ALL','LOT_WRITE','LOT_STATUS_CHANGE','ALLOCATION_READ_ALL','ALLOCATION_MANAGE',
  'ALLOCATION_APPROVE','ASSEMBLY_MANAGE','DOCUMENT_MANAGE','DOCUMENT_READ_BUREAU','NOTIFICATION_MANAGE',
  'DATA_REQUEST_HANDLE']) AS p
UNION ALL
SELECT 'MEMBRE', 'SELF_SERVICE'
UNION ALL
SELECT 'AGENT_DE_GESTION', p FROM unnest(ARRAY['DASHBOARD_BUREAU','MEMBER_READ_ALL','MEMBER_WRITE','PLAN_READ',
  'PAYMENT_MANUAL_RECORD','EXPENSE_CREATE','LOT_READ_ALL','DOCUMENT_MANAGE','DOCUMENT_READ_BUREAU']) AS p
UNION ALL
SELECT 'AUDITEUR', p FROM unnest(ARRAY['DASHBOARD_BUREAU','MEMBER_READ_ALL','PLAN_READ','PAYMENT_READ_ALL',
  'ACCOUNTING_READ','LOT_READ_ALL','ALLOCATION_READ_ALL','ALLOCATION_VERIFY','DOCUMENT_READ_BUREAU','AUDIT_READ']) AS p
UNION ALL
SELECT 'ADMINISTRATEUR_TECHNIQUE', p FROM unnest(ARRAY['USER_ADMIN','SETTINGS_ADMIN','AUDIT_READ','NOTIFICATION_MANAGE']) AS p;

-- ---------------------------------------------------------------------
-- Plan de comptes simplifié, inspiré du SYSCOHADA révisé.
-- À valider par un expert-comptable avant toute utilisation réelle.
-- ---------------------------------------------------------------------
INSERT INTO financial_account (code, label, account_type, treasury) VALUES
 ('521100', 'Banque — compte principal', 'TRESORERIE', TRUE),
 ('552100', 'Monnaie électronique — Wave', 'TRESORERIE', TRUE),
 ('552200', 'Monnaie électronique — SendFlash', 'TRESORERIE', TRUE),
 ('571100', 'Caisse', 'TRESORERIE', TRUE),
 ('559900', 'Monnaie électronique — SIMULATION (aucun fonds réel)', 'TRESORERIE', FALSE),
 ('467100', 'Membres — apports et cotisations pour programmes', 'PASSIF', FALSE),
 ('758100', 'Produits divers — pénalités de retard', 'PRODUIT', FALSE),
 ('222100', 'Terrains', 'ACTIF', FALSE),
 ('231100', 'Bâtiments en cours de construction', 'ACTIF', FALSE),
 ('604100', 'Achats de fournitures', 'CHARGE', FALSE),
 ('618100', 'Frais de déplacement', 'CHARGE', FALSE),
 ('622600', 'Honoraires et études', 'CHARGE', FALSE),
 ('628100', 'Frais administratifs', 'CHARGE', FALSE),
 ('631100', 'Frais bancaires', 'CHARGE', FALSE),
 ('658100', 'Charges diverses', 'CHARGE', FALSE);

-- ---------------------------------------------------------------------
-- Paramètres par défaut (à compléter par l'administrateur)
-- ---------------------------------------------------------------------
INSERT INTO coop_settings (id, name, legal_form, city) VALUES
 (1, 'Coopérative d''habitat (à configurer)', 'Société coopérative d''habitat (forme à préciser selon les statuts)', NULL);

-- ---------------------------------------------------------------------
-- Durées de conservation : valeurs indicatives et configurables,
-- à arrêter avec un juriste au regard de la loi n° 2008-12 et des
-- recommandations de la CDP.
-- ---------------------------------------------------------------------
INSERT INTO data_retention_policy (data_category, retention_months, legal_basis, description) VALUES
 ('PIECES_COMPTABLES', 120, 'Obligations comptables (durée à confirmer par l''expert-comptable)', 'Écritures, reçus, justificatifs de dépenses'),
 ('DOSSIER_MEMBRE', 60, 'Exécution du contrat d''adhésion ; durée après radiation à confirmer', 'Identité et coordonnées après sortie de la coopérative'),
 ('JOURNAL_SECURITE', 12, 'Intérêt légitime : sécurité du système', 'Connexions, échecs, changements de droits'),
 ('NOTIFICATIONS', 24, 'Intérêt légitime : preuve d''information des membres', 'Historique des rappels et tentatives'),
 ('PROCEDURES_ATTRIBUTION', 120, 'Preuve de la régularité des attributions', 'Candidatures, tirages, PV et contestations');
