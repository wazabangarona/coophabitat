-- Séquences pour les numérotations continues (reçus, adhérents, écritures).
-- Une numérotation continue facilite le contrôle de l'exhaustivité des pièces.
CREATE SEQUENCE receipt_number_seq START 1;
CREATE SEQUENCE member_number_seq START 1;
CREATE SEQUENCE accounting_entry_seq START 1;
CREATE SEQUENCE business_reference_seq START 1;
