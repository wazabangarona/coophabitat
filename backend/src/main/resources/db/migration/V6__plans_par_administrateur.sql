-- L'administrateur technique peut aussi définir les plans de cotisation (création, activation) et les affecter
-- aux membres, ce qui suppose de consulter la liste des membres. Il ne valide toujours pas les adhésions
-- (MEMBER_STATUS_CHANGE reste réservé à la présidence et au secrétariat) et ne touche pas aux paiements.
INSERT INTO role_permission (role_code, permission_code)
SELECT 'ADMINISTRATEUR_TECHNIQUE', p FROM unnest(ARRAY['PLAN_READ','PLAN_WRITE','MEMBER_READ_ALL']) AS p
ON CONFLICT DO NOTHING;
