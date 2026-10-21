-- Paiements déclarés par les membres : consignes de paiement de la coopérative (numéro Wave ou Orange Money,
-- compte bancaire) et compte de trésorerie Orange Money. Une déclaration n'est comptée qu'après vérification
-- par un responsable du bureau (encaissement manuel EN_VERIFICATION, déjà prévu par le schéma).
ALTER TABLE coop_settings ADD COLUMN payment_instructions VARCHAR(2000);

INSERT INTO financial_account (code, label, account_type, treasury) VALUES
 ('552300', 'Monnaie électronique — Orange Money', 'TRESORERIE', TRUE)
ON CONFLICT (code) DO NOTHING;

CREATE INDEX ix_intent_external_reference ON payment_intent (provider, channel, external_reference)
    WHERE external_reference IS NOT NULL;
