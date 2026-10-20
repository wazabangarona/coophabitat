-- Validation des adhésions : une demande peut être refusée (statut REFUSE), distinct d'une radiation.
-- Un refus peut être réexaminé (retour en attente) ; l'historique et l'audit restent complets.
ALTER TABLE member DROP CONSTRAINT member_status_check;
ALTER TABLE member ADD CONSTRAINT member_status_check
    CHECK (status IN ('EN_ATTENTE','ACTIF','SUSPENDU','RADIE','REFUSE'));
