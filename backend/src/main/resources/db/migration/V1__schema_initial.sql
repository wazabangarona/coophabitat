-- =====================================================================
-- CoopHabitat — schéma initial
-- Conventions :
--   * montants en francs CFA (XOF) : NUMERIC(15,0), jamais de flottants ;
--   * horodatages en TIMESTAMPTZ (UTC en base, affichage Africa/Dakar) ;
--   * statuts contrôlés par contraintes CHECK ;
--   * aucune suppression physique des données financières : archivage
--     et contre-écritures (voir les triggers d'immutabilité en V2).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Paramètres de la coopérative (ligne unique)
-- ---------------------------------------------------------------------
CREATE TABLE coop_settings (
    id                     SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name                   VARCHAR(200) NOT NULL,
    legal_form             VARCHAR(200),
    registration_number    VARCHAR(100),
    address                VARCHAR(300),
    city                   VARCHAR(100),
    phone                  VARCHAR(40),
    email                  VARCHAR(200),
    -- Mention affichée tant qu'aucun séquestre externe réel n'est intégré
    escrow_statement       VARCHAR(300) NOT NULL DEFAULT 'Fonds suivis comptablement — séquestre externe non vérifié ou non intégré',
    escrow_integrated      BOOLEAN NOT NULL DEFAULT FALSE,
    publish_public_stats   BOOLEAN NOT NULL DEFAULT FALSE,
    is_demo                BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Rôles et permissions
-- ---------------------------------------------------------------------
CREATE TABLE role (
    code        VARCHAR(40) PRIMARY KEY,
    label       VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    technical   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE permission (
    code        VARCHAR(60) PRIMARY KEY,
    description VARCHAR(300) NOT NULL
);

CREATE TABLE role_permission (
    role_code       VARCHAR(40) NOT NULL REFERENCES role(code),
    permission_code VARCHAR(60) NOT NULL REFERENCES permission(code),
    PRIMARY KEY (role_code, permission_code)
);

-- ---------------------------------------------------------------------
-- Plans de cotisation (créés avant member pour la FK)
-- ---------------------------------------------------------------------
CREATE TABLE contribution_plan (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40) NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(1000),
    amount           NUMERIC(15,0) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(3) NOT NULL DEFAULT 'XOF' CHECK (currency = 'XOF'),
    periodicity      VARCHAR(20) NOT NULL CHECK (periodicity IN ('MENSUELLE','TRIMESTRIELLE','SEMESTRIELLE','ANNUELLE')),
    due_day_of_month INTEGER NOT NULL CHECK (due_day_of_month BETWEEN 1 AND 31),
    valid_from       DATE NOT NULL,
    valid_to         DATE,
    grace_days       INTEGER NOT NULL DEFAULT 0 CHECK (grace_days >= 0),
    penalty_method   VARCHAR(20) NOT NULL CHECK (penalty_method IN ('AUCUNE','FORFAIT','POURCENTAGE_MENSUEL')),
    penalty_value    NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (penalty_value >= 0),
    penalty_cap      NUMERIC(15,0) CHECK (penalty_cap IS NULL OR penalty_cap >= 0),
    rounding_mode    VARCHAR(20) NOT NULL DEFAULT 'HALF_UP' CHECK (rounding_mode IN ('HALF_UP','DOWN','UP')),
    status           VARCHAR(20) NOT NULL DEFAULT 'BROUILLON' CHECK (status IN ('BROUILLON','ACTIF','ARCHIVE')),
    adopted_reference VARCHAR(300),
    created_by       BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0,
    is_demo          BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (penalty_method <> 'POURCENTAGE_MENSUEL' OR penalty_value <= 100)
);

-- ---------------------------------------------------------------------
-- Membres
-- ---------------------------------------------------------------------
CREATE TABLE member (
    id                   BIGSERIAL PRIMARY KEY,
    member_number        VARCHAR(30) NOT NULL UNIQUE,
    first_name           VARCHAR(100) NOT NULL,
    last_name            VARCHAR(100) NOT NULL,
    email                VARCHAR(200),
    phone                VARCHAR(30),
    address              VARCHAR(300),
    city                 VARCHAR(100),
    membership_date      DATE NOT NULL,
    member_type          VARCHAR(20) NOT NULL CHECK (member_type IN ('FONDATEUR','ORDINAIRE','BENEFICIAIRE')),
    status               VARCHAR(20) NOT NULL DEFAULT 'EN_ATTENTE' CHECK (status IN ('EN_ATTENTE','ACTIF','SUSPENDU','RADIE')),
    contribution_plan_id BIGINT REFERENCES contribution_plan(id),
    notes                VARCHAR(1000),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT NOT NULL DEFAULT 0,
    is_demo              BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_member_email ON member (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX ux_member_phone ON member (phone) WHERE phone IS NOT NULL;
CREATE INDEX ix_member_status ON member (status);
CREATE INDEX ix_member_name ON member (lower(last_name), lower(first_name));

CREATE TABLE membership_status_history (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT NOT NULL REFERENCES member(id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    reason      VARCHAR(1000) NOT NULL,
    changed_by  BIGINT,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_msh_member ON membership_status_history (member_id, changed_at);

-- ---------------------------------------------------------------------
-- Comptes utilisateurs
-- ---------------------------------------------------------------------
CREATE TABLE user_account (
    id               BIGSERIAL PRIMARY KEY,
    email            VARCHAR(200) NOT NULL,
    password_hash    VARCHAR(255),
    display_name     VARCHAR(200) NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts  INTEGER NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    token_version    INTEGER NOT NULL DEFAULT 0,
    member_id        BIGINT UNIQUE REFERENCES member(id),
    last_login_at    TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0,
    is_demo          BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_user_email ON user_account (lower(email));

CREATE TABLE user_role (
    user_id   BIGINT NOT NULL REFERENCES user_account(id),
    role_code VARCHAR(40) NOT NULL REFERENCES role(code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_account(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    revoke_reason VARCHAR(100),
    replaced_by BIGINT REFERENCES refresh_token(id),
    user_agent  VARCHAR(300),
    ip_address  VARCHAR(64)
);
CREATE INDEX ix_refresh_user ON refresh_token (user_id);
CREATE INDEX ix_refresh_family ON refresh_token (family_id);

CREATE TABLE one_time_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_account(id),
    purpose     VARCHAR(30) NOT NULL CHECK (purpose IN ('PASSWORD_RESET','EMAIL_VERIFICATION','INVITATION')),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ott_user ON one_time_token (user_id, purpose);

-- ---------------------------------------------------------------------
-- Obligations des membres (cotisations et pénalités)
-- ---------------------------------------------------------------------
CREATE TABLE member_obligation (
    id                BIGSERIAL PRIMARY KEY,
    member_id         BIGINT NOT NULL REFERENCES member(id),
    plan_id           BIGINT NOT NULL REFERENCES contribution_plan(id),
    kind              VARCHAR(20) NOT NULL CHECK (kind IN ('COTISATION','PENALITE')),
    parent_id         BIGINT REFERENCES member_obligation(id),
    penalty_sequence  INTEGER,
    period_label      VARCHAR(40) NOT NULL,
    period_start      DATE NOT NULL,
    period_end        DATE NOT NULL,
    due_date          DATE NOT NULL,
    amount_due        NUMERIC(15,0) NOT NULL CHECK (amount_due >= 0),
    amount_paid       NUMERIC(15,0) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0),
    -- Instantané des règles appliquées au moment de la génération :
    -- une modification ultérieure du plan ne réécrit pas l'historique.
    grace_days        INTEGER NOT NULL,
    penalty_method    VARCHAR(20) NOT NULL,
    penalty_value     NUMERIC(15,4) NOT NULL,
    penalty_cap       NUMERIC(15,0),
    rounding_mode     VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'A_PAYER' CHECK (status IN ('A_PAYER','PARTIELLE','PAYEE','ANNULEE')),
    cancel_reason     VARCHAR(1000),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0,
    CHECK (amount_paid <= amount_due),
    CHECK ((kind = 'PENALITE') = (parent_id IS NOT NULL)),
    CHECK ((kind = 'PENALITE') = (penalty_sequence IS NOT NULL))
);
CREATE UNIQUE INDEX ux_obligation_period ON member_obligation (member_id, plan_id, period_start) WHERE kind = 'COTISATION';
CREATE UNIQUE INDEX ux_obligation_penalty ON member_obligation (parent_id, penalty_sequence) WHERE kind = 'PENALITE';
CREATE INDEX ix_obligation_member_due ON member_obligation (member_id, due_date);
CREATE INDEX ix_obligation_due_status ON member_obligation (due_date, status);

-- Ajustements explicites et motivés des obligations (dérogations, annulations)
CREATE TABLE obligation_adjustment (
    id              BIGSERIAL PRIMARY KEY,
    obligation_id   BIGINT NOT NULL REFERENCES member_obligation(id),
    adjustment_type VARCHAR(30) NOT NULL CHECK (adjustment_type IN ('REMISE_PENALITE','ANNULATION','CORRECTION_MONTANT')),
    previous_amount NUMERIC(15,0) NOT NULL,
    new_amount      NUMERIC(15,0) NOT NULL CHECK (new_amount >= 0),
    reason          VARCHAR(1000) NOT NULL,
    decision_reference VARCHAR(300),
    performed_by    BIGINT NOT NULL REFERENCES user_account(id),
    performed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Paiements
-- ---------------------------------------------------------------------
CREATE TABLE payment_intent (
    id                   BIGSERIAL PRIMARY KEY,
    reference            VARCHAR(40) NOT NULL UNIQUE,
    member_id            BIGINT NOT NULL REFERENCES member(id),
    obligation_id        BIGINT REFERENCES member_obligation(id),
    amount               NUMERIC(15,0) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3) NOT NULL DEFAULT 'XOF',
    provider             VARCHAR(20) NOT NULL CHECK (provider IN ('MOCK','WAVE','SENDFLASH','MANUEL')),
    channel              VARCHAR(30),
    status               VARCHAR(20) NOT NULL CHECK (status IN ('INITIE','EN_ATTENTE','CONFIRME','ECHOUE','EXPIRE','REMBOURSE','EN_VERIFICATION')),
    idempotency_key      VARCHAR(100) NOT NULL,
    provider_session_id  VARCHAR(200),
    provider_launch_url  VARCHAR(1000),
    external_reference   VARCHAR(200),
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason       VARCHAR(1000),
    created_by           BIGINT REFERENCES user_account(id),
    verified_by          BIGINT REFERENCES user_account(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ,
    confirmed_at         TIMESTAMPTZ,
    last_checked_at      TIMESTAMPTZ,
    check_attempts       INTEGER NOT NULL DEFAULT 0,
    version              BIGINT NOT NULL DEFAULT 0,
    UNIQUE (member_id, idempotency_key)
);
CREATE UNIQUE INDEX ux_intent_provider_session ON payment_intent (provider, provider_session_id) WHERE provider_session_id IS NOT NULL;
CREATE INDEX ix_intent_status ON payment_intent (status);
CREATE INDEX ix_intent_member ON payment_intent (member_id, created_at DESC);

CREATE TABLE payment_transaction (
    id                      BIGSERIAL PRIMARY KEY,
    reference               VARCHAR(40) NOT NULL UNIQUE,
    intent_id               BIGINT NOT NULL UNIQUE REFERENCES payment_intent(id),
    member_id               BIGINT NOT NULL REFERENCES member(id),
    amount                  NUMERIC(15,0) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3) NOT NULL DEFAULT 'XOF',
    provider                VARCHAR(20) NOT NULL,
    provider_transaction_id VARCHAR(200),
    simulated               BOOLEAN NOT NULL,
    status                  VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRME','REMBOURSE')),
    confirmed_at            TIMESTAMPTZ NOT NULL,
    confirmation_source     VARCHAR(40) NOT NULL,
    refunded_at             TIMESTAMPTZ,
    refund_reason           VARCHAR(1000),
    refunded_by             BIGINT REFERENCES user_account(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_tx_provider_ref ON payment_transaction (provider, provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
CREATE INDEX ix_tx_member ON payment_transaction (member_id, confirmed_at DESC);

-- Affectation des paiements aux obligations (append-only, montants négatifs = contre-passation)
CREATE TABLE payment_allocation (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  BIGINT NOT NULL REFERENCES payment_transaction(id),
    obligation_id   BIGINT NOT NULL REFERENCES member_obligation(id),
    amount          NUMERIC(15,0) NOT NULL CHECK (amount <> 0),
    reversal_of_id  BIGINT REFERENCES payment_allocation(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_alloc_tx ON payment_allocation (transaction_id);
CREATE INDEX ix_alloc_obligation ON payment_allocation (obligation_id);

CREATE TABLE payment_provider_event (
    id               BIGSERIAL PRIMARY KEY,
    provider         VARCHAR(20) NOT NULL,
    event_id         VARCHAR(200) NOT NULL,
    event_type       VARCHAR(100),
    signature_valid  BOOLEAN NOT NULL,
    payload          TEXT,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at     TIMESTAMPTZ,
    processing_result VARCHAR(500),
    intent_id        BIGINT REFERENCES payment_intent(id),
    UNIQUE (provider, event_id)
);

-- ---------------------------------------------------------------------
-- Documents (métadonnées ; contenu stocké hors base)
-- ---------------------------------------------------------------------
CREATE TABLE document (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(40) NOT NULL UNIQUE,
    title             VARCHAR(300) NOT NULL,
    category          VARCHAR(40) NOT NULL CHECK (category IN ('STATUTS','REGLEMENT','CONVOCATION','COMPTE_RENDU_AG','PV_ATTRIBUTION','RECU','RAPPORT_FINANCIER','JUSTIFICATIF_DEPENSE','DOCUMENT_FONCIER','DOCUMENT_MEMBRE','AUTRE')),
    visibility        VARCHAR(20) NOT NULL CHECK (visibility IN ('PUBLIC','MEMBRES','BUREAU','PRIVE_MEMBRE')),
    owner_member_id   BIGINT REFERENCES member(id),
    related_type      VARCHAR(40),
    related_id        BIGINT,
    storage_key       VARCHAR(300) NOT NULL UNIQUE,
    original_filename VARCHAR(300) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256            VARCHAR(64) NOT NULL,
    generated         BOOLEAN NOT NULL DEFAULT FALSE,
    published_at      TIMESTAMPTZ,
    uploaded_by       BIGINT REFERENCES user_account(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at       TIMESTAMPTZ,
    is_demo           BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (visibility <> 'PRIVE_MEMBRE' OR owner_member_id IS NOT NULL)
);
CREATE INDEX ix_document_related ON document (related_type, related_id);
CREATE INDEX ix_document_owner ON document (owner_member_id);

-- ---------------------------------------------------------------------
-- Reçus
-- ---------------------------------------------------------------------
CREATE TABLE receipt (
    id                BIGSERIAL PRIMARY KEY,
    receipt_number    VARCHAR(40) NOT NULL UNIQUE,
    transaction_id    BIGINT NOT NULL UNIQUE REFERENCES payment_transaction(id),
    member_id         BIGINT NOT NULL REFERENCES member(id),
    verification_code VARCHAR(40) NOT NULL UNIQUE,
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    document_id       BIGINT REFERENCES document(id),
    simulated         BOOLEAN NOT NULL
);

-- ---------------------------------------------------------------------
-- Comptabilité
-- ---------------------------------------------------------------------
CREATE TABLE financial_account (
    code        VARCHAR(20) PRIMARY KEY,
    label       VARCHAR(200) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ACTIF','PASSIF','PRODUIT','CHARGE','TRESORERIE')),
    treasury    BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE accounting_entry (
    id              BIGSERIAL PRIMARY KEY,
    reference       VARCHAR(40) NOT NULL UNIQUE,
    entry_date      DATE NOT NULL,
    label           VARCHAR(500) NOT NULL,
    source_type     VARCHAR(40) NOT NULL,
    source_id       BIGINT,
    reversal_of_id  BIGINT UNIQUE REFERENCES accounting_entry(id),
    simulated       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES user_account(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_entry_date ON accounting_entry (entry_date);
CREATE INDEX ix_entry_source ON accounting_entry (source_type, source_id);

CREATE TABLE accounting_line (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      BIGINT NOT NULL REFERENCES accounting_entry(id),
    account_code  VARCHAR(20) NOT NULL REFERENCES financial_account(code),
    debit         NUMERIC(15,0) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit        NUMERIC(15,0) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    member_id     BIGINT REFERENCES member(id),
    CHECK ((debit = 0) <> (credit = 0))
);
CREATE INDEX ix_line_entry ON accounting_line (entry_id);
CREATE INDEX ix_line_account ON accounting_line (account_code);

CREATE TABLE budget (
    id          BIGSERIAL PRIMARY KEY,
    fiscal_year INTEGER NOT NULL UNIQUE,
    label       VARCHAR(200) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'BROUILLON' CHECK (status IN ('BROUILLON','ADOPTE','CLOTURE')),
    adopted_reference VARCHAR(300),
    created_by  BIGINT REFERENCES user_account(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE budget_line (
    id             BIGSERIAL PRIMARY KEY,
    budget_id      BIGINT NOT NULL REFERENCES budget(id),
    category       VARCHAR(40) NOT NULL,
    direction      VARCHAR(10) NOT NULL CHECK (direction IN ('RECETTE','DEPENSE')),
    planned_amount NUMERIC(15,0) NOT NULL CHECK (planned_amount >= 0),
    UNIQUE (budget_id, category, direction)
);

CREATE TABLE expense (
    id              BIGSERIAL PRIMARY KEY,
    reference       VARCHAR(40) NOT NULL UNIQUE,
    category        VARCHAR(40) NOT NULL,
    label           VARCHAR(300) NOT NULL,
    supplier        VARCHAR(200),
    amount          NUMERIC(15,0) NOT NULL CHECK (amount > 0),
    expense_date    DATE NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('BROUILLON','SOUMISE','APPROUVEE','REJETEE','PAYEE','ANNULEE')),
    payment_account VARCHAR(20) REFERENCES financial_account(code),
    justification_document_id BIGINT REFERENCES document(id),
    required_approvals INTEGER NOT NULL DEFAULT 1 CHECK (required_approvals BETWEEN 1 AND 3),
    created_by      BIGINT NOT NULL REFERENCES user_account(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    paid_by         BIGINT REFERENCES user_account(id),
    accounting_entry_id BIGINT REFERENCES accounting_entry(id),
    cancel_reason   VARCHAR(1000),
    version         BIGINT NOT NULL DEFAULT 0,
    is_demo         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_expense_status ON expense (status);

CREATE TABLE expense_approval (
    id          BIGSERIAL PRIMARY KEY,
    expense_id  BIGINT NOT NULL REFERENCES expense(id),
    approver_id BIGINT NOT NULL REFERENCES user_account(id),
    decision    VARCHAR(20) NOT NULL CHECK (decision IN ('APPROUVEE','REJETEE')),
    comment     VARCHAR(1000),
    decided_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (expense_id, approver_id)
);

-- Soldes bancaires déclarés ou importés (distincts du solde comptable)
CREATE TABLE bank_balance_declaration (
    id            BIGSERIAL PRIMARY KEY,
    account_code  VARCHAR(20) NOT NULL REFERENCES financial_account(code),
    balance_date  DATE NOT NULL,
    balance       NUMERIC(15,0) NOT NULL,
    source        VARCHAR(30) NOT NULL CHECK (source IN ('DECLARATION','RELEVE_IMPORTE')),
    statement_reference VARCHAR(200),
    declared_by   BIGINT NOT NULL REFERENCES user_account(id),
    declared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_code, balance_date, source)
);

CREATE TABLE bank_statement_line (
    id              BIGSERIAL PRIMARY KEY,
    account_code    VARCHAR(20) NOT NULL REFERENCES financial_account(code),
    operation_date  DATE NOT NULL,
    label           VARCHAR(500) NOT NULL,
    amount          NUMERIC(15,0) NOT NULL,
    bank_reference  VARCHAR(200),
    matched_entry_id BIGINT REFERENCES accounting_entry(id),
    matched_by      BIGINT REFERENCES user_account(id),
    matched_at      TIMESTAMPTZ,
    imported_by     BIGINT NOT NULL REFERENCES user_account(id),
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    import_hash     VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE financial_report (
    id            BIGSERIAL PRIMARY KEY,
    reference     VARCHAR(40) NOT NULL UNIQUE,
    report_type   VARCHAR(20) NOT NULL CHECK (report_type IN ('SEMESTRIEL','ANNUEL','PERIODE')),
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    status        VARCHAR(20) NOT NULL CHECK (status IN ('BROUILLON','PUBLIE')),
    snapshot      TEXT NOT NULL,
    document_id   BIGINT REFERENCES document(id),
    generated_by  BIGINT NOT NULL REFERENCES user_account(id),
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by  BIGINT REFERENCES user_account(id),
    published_at  TIMESTAMPTZ,
    CHECK (period_end >= period_start)
);

-- ---------------------------------------------------------------------
-- Programmes immobiliers et lots
-- ---------------------------------------------------------------------
CREATE TABLE property_project (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    region      VARCHAR(100),
    departement VARCHAR(100),
    commune     VARCHAR(100),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('ETUDE','EN_COURS','LIVRE','SUSPENDU')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0,
    is_demo     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE property_lot (
    id                   BIGSERIAL PRIMARY KEY,
    reference            VARCHAR(40) NOT NULL UNIQUE,
    project_id           BIGINT NOT NULL REFERENCES property_project(id),
    lot_type             VARCHAR(20) NOT NULL CHECK (lot_type IN ('TERRAIN','VILLA','APPARTEMENT','DUPLEX','LOCAL')),
    region               VARCHAR(100) NOT NULL,
    departement          VARCHAR(100) NOT NULL,
    commune              VARCHAR(100) NOT NULL,
    locality             VARCHAR(200),
    area_m2              NUMERIC(12,2) NOT NULL CHECK (area_m2 > 0),
    latitude             NUMERIC(9,6) CHECK (latitude BETWEEN -90 AND 90),
    longitude            NUMERIC(9,6) CHECK (longitude BETWEEN -180 AND 180),
    coordinates_source   VARCHAR(300),
    coordinates_verified BOOLEAN NOT NULL DEFAULT FALSE,
    status               VARCHAR(20) NOT NULL CHECK (status IN ('EN_PREPARATION','DISPONIBLE','RESERVE','ATTRIBUE','LITIGIEUX','INDISPONIBLE')),
    description          VARCHAR(2000),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT NOT NULL DEFAULT 0,
    is_demo              BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX ix_lot_project ON property_lot (project_id);
CREATE INDEX ix_lot_status ON property_lot (status);

CREATE TABLE lot_status_history (
    id          BIGSERIAL PRIMARY KEY,
    lot_id      BIGINT NOT NULL REFERENCES property_lot(id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    reason      VARCHAR(1000) NOT NULL,
    source_type VARCHAR(40),
    source_id   BIGINT,
    changed_by  BIGINT REFERENCES user_account(id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_lsh_lot ON lot_status_history (lot_id, changed_at);

-- ---------------------------------------------------------------------
-- Attribution des lots
-- ---------------------------------------------------------------------
CREATE TABLE allocation_campaign (
    id                    BIGSERIAL PRIMARY KEY,
    reference             VARCHAR(40) NOT NULL UNIQUE,
    title                 VARCHAR(300) NOT NULL,
    description           VARCHAR(4000),
    method                VARCHAR(20) NOT NULL CHECK (method IN ('TIRAGE_AU_SORT','COMMISSION')),
    status                VARCHAR(30) NOT NULL CHECK (status IN ('BROUILLON','CRITERES_PUBLIES','CANDIDATURES_OUVERTES','CANDIDATURES_CLOSES','LISTE_PUBLIEE','VALIDEE','EXECUTEE','PV_VALIDE','RESULTATS_PUBLIES','ARCHIVEE','ANNULEE')),
    eligibility_text      VARCHAR(4000),
    rules_text            VARCHAR(4000),
    min_seniority_months  INTEGER NOT NULL DEFAULT 0,
    require_up_to_date    BOOLEAN NOT NULL DEFAULT TRUE,
    max_arrears_amount    NUMERIC(15,0) NOT NULL DEFAULT 0,
    allowed_member_types  VARCHAR(100) NOT NULL DEFAULT 'FONDATEUR,ORDINAIRE,BENEFICIAIRE',
    reference_date        DATE,
    application_opens_at  TIMESTAMPTZ,
    application_closes_at TIMESTAMPTZ,
    required_approvals    INTEGER NOT NULL DEFAULT 2 CHECK (required_approvals BETWEEN 1 AND 5),
    session_date          TIMESTAMPTZ,
    session_location      VARCHAR(300),
    criteria_published_at TIMESTAMPTZ,
    list_published_at     TIMESTAMPTZ,
    frozen_at             TIMESTAMPTZ,
    participants_digest   VARCHAR(64),
    lots_digest           VARCHAR(64),
    seed_commitment       VARCHAR(64),
    seed_secret           VARCHAR(64),
    seed_revealed         BOOLEAN NOT NULL DEFAULT FALSE,
    public_entropy        VARCHAR(200),
    algorithm_version     VARCHAR(40),
    executed_at           TIMESTAMPTZ,
    executed_by           BIGINT REFERENCES user_account(id),
    results_published_at  TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,
    cancel_reason         VARCHAR(2000),
    created_by            BIGINT NOT NULL REFERENCES user_account(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT NOT NULL DEFAULT 0,
    is_demo               BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE allocation_campaign_lot (
    campaign_id BIGINT NOT NULL REFERENCES allocation_campaign(id),
    lot_id      BIGINT NOT NULL REFERENCES property_lot(id),
    PRIMARY KEY (campaign_id, lot_id)
);

CREATE TABLE allocation_commission_member (
    campaign_id BIGINT NOT NULL REFERENCES allocation_campaign(id),
    user_id     BIGINT NOT NULL REFERENCES user_account(id),
    role_in_commission VARCHAR(100),
    conflict_declared BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_details  VARCHAR(1000),
    PRIMARY KEY (campaign_id, user_id)
);

CREATE TABLE allocation_candidate (
    id                 BIGSERIAL PRIMARY KEY,
    campaign_id        BIGINT NOT NULL REFERENCES allocation_campaign(id),
    member_id          BIGINT NOT NULL REFERENCES member(id),
    applied_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    eligibility_status VARCHAR(20) NOT NULL DEFAULT 'A_VERIFIER' CHECK (eligibility_status IN ('A_VERIFIER','ELIGIBLE','NON_ELIGIBLE')),
    eligibility_reason VARCHAR(1000),
    eligibility_snapshot TEXT,
    checked_at         TIMESTAMPTZ,
    withdrawn_at       TIMESTAMPTZ,
    UNIQUE (campaign_id, member_id)
);

CREATE TABLE allocation_result (
    id            BIGSERIAL PRIMARY KEY,
    campaign_id   BIGINT NOT NULL REFERENCES allocation_campaign(id),
    candidate_id  BIGINT NOT NULL REFERENCES allocation_candidate(id),
    lot_id        BIGINT REFERENCES property_lot(id),
    draw_rank     INTEGER,
    justification VARCHAR(2000),
    status        VARCHAR(20) NOT NULL CHECK (status IN ('PROVISOIRE','DEFINITIF','LISTE_ATTENTE','ANNULE')),
    cancel_reason VARCHAR(2000),
    decided_by    BIGINT REFERENCES user_account(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);
-- Un lot ne peut avoir qu'un seul résultat actif, toutes procédures confondues
CREATE UNIQUE INDEX ux_result_active_lot ON allocation_result (lot_id) WHERE lot_id IS NOT NULL AND status IN ('PROVISOIRE','DEFINITIF');
-- Un candidat ne peut obtenir qu'un seul résultat actif dans une procédure
CREATE UNIQUE INDEX ux_result_active_candidate ON allocation_result (candidate_id) WHERE status <> 'ANNULE';

CREATE TABLE allocation_approval (
    id          BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES allocation_campaign(id),
    step        VARCHAR(30) NOT NULL CHECK (step IN ('VALIDATION_LISTE','VALIDATION_PV','DECISION_COMMISSION','ANNULATION_RESULTAT')),
    approver_id BIGINT NOT NULL REFERENCES user_account(id),
    decision    VARCHAR(20) NOT NULL CHECK (decision IN ('APPROUVE','REFUSE')),
    comment     VARCHAR(2000),
    target_id   BIGINT,
    decided_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_alloc_approval ON allocation_approval (campaign_id, step, approver_id, COALESCE(target_id, 0));

CREATE TABLE allocation_minutes (
    id           BIGSERIAL PRIMARY KEY,
    campaign_id  BIGINT NOT NULL REFERENCES allocation_campaign(id),
    version_no   INTEGER NOT NULL,
    document_id  BIGINT NOT NULL REFERENCES document(id),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('BROUILLON','VALIDE','REMPLACE')),
    generated_by BIGINT NOT NULL REFERENCES user_account(id),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    validated_at TIMESTAMPTZ,
    UNIQUE (campaign_id, version_no)
);

CREATE TABLE allocation_contest (
    id           BIGSERIAL PRIMARY KEY,
    reference    VARCHAR(40) NOT NULL UNIQUE,
    campaign_id  BIGINT NOT NULL REFERENCES allocation_campaign(id),
    member_id    BIGINT NOT NULL REFERENCES member(id),
    result_id    BIGINT REFERENCES allocation_result(id),
    lot_id       BIGINT REFERENCES property_lot(id),
    reason       VARCHAR(4000) NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('DEPOSEE','EN_EXAMEN','REJETEE','ACCEPTEE')),
    decision     VARCHAR(4000),
    decided_by   BIGINT REFERENCES user_account(id),
    decided_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------
-- Assemblées générales
-- ---------------------------------------------------------------------
CREATE TABLE general_assembly (
    id                 BIGSERIAL PRIMARY KEY,
    reference          VARCHAR(40) NOT NULL UNIQUE,
    title              VARCHAR(300) NOT NULL,
    assembly_type      VARCHAR(20) NOT NULL CHECK (assembly_type IN ('ORDINAIRE','EXTRAORDINAIRE')),
    scheduled_at       TIMESTAMPTZ NOT NULL,
    location           VARCHAR(300),
    modality           VARCHAR(20) NOT NULL CHECK (modality IN ('PRESENTIEL','EN_LIGNE','MIXTE')),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PROGRAMMEE','CONVOQUEE','TENUE','CLOTUREE','ANNULEE')),
    quorum_rule        VARCHAR(500),
    convocation_text   VARCHAR(8000),
    convened_at        TIMESTAMPTZ,
    held_at            TIMESTAMPTZ,
    cancel_reason      VARCHAR(1000),
    created_by         BIGINT NOT NULL REFERENCES user_account(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT NOT NULL DEFAULT 0,
    is_demo            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE agenda_item (
    id          BIGSERIAL PRIMARY KEY,
    assembly_id BIGINT NOT NULL REFERENCES general_assembly(id),
    position    INTEGER NOT NULL,
    title       VARCHAR(300) NOT NULL,
    description VARCHAR(4000),
    UNIQUE (assembly_id, position)
);

CREATE TABLE resolution (
    id             BIGSERIAL PRIMARY KEY,
    assembly_id    BIGINT NOT NULL REFERENCES general_assembly(id),
    agenda_item_id BIGINT REFERENCES agenda_item(id),
    number         VARCHAR(20) NOT NULL,
    text           VARCHAR(8000) NOT NULL,
    votes_for      INTEGER CHECK (votes_for >= 0),
    votes_against  INTEGER CHECK (votes_against >= 0),
    abstentions    INTEGER CHECK (abstentions >= 0),
    outcome        VARCHAR(20) NOT NULL CHECK (outcome IN ('ADOPTEE','REJETEE','AJOURNEE')),
    recorded_by    BIGINT NOT NULL REFERENCES user_account(id),
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assembly_id, number)
);

CREATE TABLE attendance (
    assembly_id     BIGINT NOT NULL REFERENCES general_assembly(id),
    member_id       BIGINT NOT NULL REFERENCES member(id),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT','REPRESENTE','ABSENT','EXCUSE')),
    proxy_member_id BIGINT REFERENCES member(id),
    recorded_by     BIGINT NOT NULL REFERENCES user_account(id),
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (assembly_id, member_id),
    CHECK (status <> 'REPRESENTE' OR proxy_member_id IS NOT NULL),
    CHECK (proxy_member_id IS NULL OR proxy_member_id <> member_id)
);

CREATE TABLE assembly_minutes (
    id          BIGSERIAL PRIMARY KEY,
    assembly_id BIGINT NOT NULL REFERENCES general_assembly(id),
    version_no  INTEGER NOT NULL,
    content     TEXT NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('BROUILLON','PUBLIE','REMPLACE')),
    author_id   BIGINT NOT NULL REFERENCES user_account(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    document_id BIGINT REFERENCES document(id),
    UNIQUE (assembly_id, version_no)
);

-- ---------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES user_account(id),
    member_id       BIGINT REFERENCES member(id),
    channel         VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','SMS','IN_APP')),
    template_code   VARCHAR(60) NOT NULL,
    recipient       VARCHAR(200),
    subject         VARCHAR(300) NOT NULL,
    body            VARCHAR(4000) NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('EN_ATTENTE','ENVOYE','ECHOUE','LIVRE')),
    status_detail   VARCHAR(500),
    dedup_key       VARCHAR(200) NOT NULL UNIQUE,
    attempts        INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    related_type    VARCHAR(40),
    related_id      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_notification_dispatch ON notification (status, next_attempt_at);
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);

CREATE TABLE notification_attempt (
    id              BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES notification(id),
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome         VARCHAR(20) NOT NULL,
    detail          VARCHAR(1000)
);

CREATE TABLE notification_preference (
    member_id BIGINT NOT NULL REFERENCES member(id),
    channel   VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','SMS','IN_APP')),
    enabled   BOOLEAN NOT NULL,
    PRIMARY KEY (member_id, channel)
);

-- ---------------------------------------------------------------------
-- Tâches planifiées : verrou applicatif (une seule instance exécute)
-- ---------------------------------------------------------------------
CREATE TABLE scheduled_job_lock (
    job_name     VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    locked_by    VARCHAR(200) NOT NULL,
    last_run_at  TIMESTAMPTZ,
    last_result  VARCHAR(1000)
);

-- ---------------------------------------------------------------------
-- Audit (chaîne de hachage, append-only)
-- ---------------------------------------------------------------------
CREATE TABLE audit_event (
    id            BIGSERIAL PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    category      VARCHAR(20) NOT NULL CHECK (category IN ('SECURITE','FINANCE','MEMBRE','ATTRIBUTION','LOT','DOCUMENT','ASSEMBLEE','ADMIN','NOTIFICATION','DONNEES')),
    action        VARCHAR(80) NOT NULL,
    actor_user_id BIGINT,
    actor_label   VARCHAR(200),
    entity_type   VARCHAR(60),
    entity_id     VARCHAR(60),
    details       TEXT,
    ip_address    VARCHAR(64),
    previous_hash VARCHAR(64),
    hash          VARCHAR(64) NOT NULL UNIQUE
);
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id);
CREATE INDEX ix_audit_time ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_category ON audit_event (category, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Protection des données personnelles
-- ---------------------------------------------------------------------
CREATE TABLE data_retention_policy (
    data_category    VARCHAR(60) PRIMARY KEY,
    retention_months INTEGER NOT NULL CHECK (retention_months > 0),
    legal_basis      VARCHAR(500) NOT NULL,
    description      VARCHAR(1000)
);

CREATE TABLE data_subject_request (
    id           BIGSERIAL PRIMARY KEY,
    reference    VARCHAR(40) NOT NULL UNIQUE,
    member_id    BIGINT NOT NULL REFERENCES member(id),
    request_type VARCHAR(20) NOT NULL CHECK (request_type IN ('ACCES','RECTIFICATION','OPPOSITION','SUPPRESSION','PORTABILITE')),
    details      VARCHAR(4000) NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('RECUE','EN_COURS','TRAITEE','REJETEE')),
    response     VARCHAR(4000),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_at       TIMESTAMPTZ NOT NULL,
    handled_by   BIGINT REFERENCES user_account(id),
    handled_at   TIMESTAMPTZ
);
