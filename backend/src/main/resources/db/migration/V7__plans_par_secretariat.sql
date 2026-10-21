-- Le secrétariat peut aussi créer, activer et archiver les plans de cotisation, et les affecter aux membres
-- (comme la trésorerie et l'administrateur technique). Personne n'affecte son propre plan.
INSERT INTO role_permission (role_code, permission_code)
VALUES ('SECRETAIRE', 'PLAN_WRITE')
ON CONFLICT DO NOTHING;
