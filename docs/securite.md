# Sécurité et protection des données

Ce document décrit les protections **effectivement implémentées et testées**, puis ce qui reste à faire.
CoopHabitat n'a fait l'objet d'aucun test d'intrusion ni audit indépendant ; il n'est pas certifié et ne doit
pas être présenté comme conforme à une réglementation sans audit.

## 1. Authentification et sessions

| Mesure | Détail | Vérification |
|---|---|---|
| Mots de passe | Argon2id (m = 19 Mio, t = 2, p = 1) ; 12 à 128 caractères ; refus des mots de passe triviaux et de ceux contenant l'identifiant (inspiré de NIST SP 800-63B, sans règles de composition arbitraires) | `CommonTest.passwordPolicy`, vérifié sur l'image Docker |
| Aucun compte par défaut | Le premier administrateur reçoit une invitation à usage unique (72 h) ; sans SMTP, le lien est écrit **une fois** dans le journal du serveur | essai réel sur l'image de production : activation 204, réutilisation refusée 400 |
| Jeton d'accès | JWT HS256 signé (secret ≥ 32 caractères), validité 15 min, **conservé en mémoire seulement** côté navigateur (jamais en `localStorage`) | `AuthIT` |
| Renouvellement | Cookie `CH_REFRESH` `HttpOnly`, `Secure` (obligatoire en production), `SameSite=Strict`, limité à `/api/v1/auth` ; rotation à chaque usage ; **réutilisation d'un ancien jeton = révocation de toute la famille** | `AuthIT.loginRefreshRotationAndReuseDetection` |
| Protection CSRF | Les seules routes qui lisent le cookie (renouvellement, déconnexion) exigent l'en-tête `X-Requested-With: CoopHabitat`, impossible à poser depuis un autre site sans CORS ; les autres routes n'utilisent pas de cookie | `AuthIT` |
| Verrouillage | 5 échecs → compte verrouillé 15 min ; 10 tentatives par adresse et par 15 min ; réponse identique pour compte inconnu et mauvais mot de passe | `AuthIT.accountIsLockedAfterRepeatedFailures`, `wrongPasswordAndUnknownAccountGiveTheSameAnswer` |
| Limitation de débit par IP | 20 requêtes/min sur connexion, réinitialisation et activation ; 240/min sur les autres routes d'authentification (adresses partagées des opérateurs mobiles) | `AuthIT.credentialEndpointsAreThrottledButSessionRenewalIsNot` |
| Désactivation | Un compte désactivé perd l'accès immédiatement (jetons de renouvellement révoqués, contrôle à chaque requête) | `AuthIT.disablingAnAccountRevokesAccessImmediately` |
| Déconnexion partout | Révoque toutes les sessions de l'utilisateur | interface « Mon compte » |

## 2. Autorisations

Contrôle **côté serveur** à deux niveaux :

1. **Par route** : chaque méthode de contrôleur porte `@PreAuthorize(hasAuthority('…'))` sur une permission fine
   (41 permissions). Les gardes Angular ne servent qu'au confort d'affichage.
2. **Par ressource** (anti-IDOR) : un membre n'accède qu'à ses propres échéances, paiements, reçus, documents et
   candidatures ; les identifiants d'un autre membre renvoient 403 ou 404 (404 pour un reçu, afin de ne pas
   révéler son existence).

Matrice des rôles (référentiel `V2`, modifiable par l'administrateur technique) :

| Permission | Président | Trésorier | Secrétaire | Agent | Auditeur | Admin. tech. | Membre |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Tableau de bord du bureau | ✓ | ✓ | ✓ | ✓ | ✓ | | |
| Lire / modifier les membres | lire | lire | ✓ | ✓ | lire | | |
| Plans et échéances | lire | ✓ | lire | lire | lire | | |
| Saisir un encaissement manuel | | ✓ | | ✓ | | | |
| **Valider** un encaissement manuel (autre personne) | ✓ | | | | | | |
| Remboursement, réconciliation | | ✓ | | | | | |
| Comptabilité (lecture / écriture) | lire | ✓ | | | lire | | synthèse |
| Dépenses : saisir / approuver / payer | approuver | saisir, payer | | saisir | | | |
| Rapports : générer / publier | publier | générer | | | | | lire publiés |
| Lots | lire | lire | ✓ | lire | lire | | |
| Attribution : préparer / valider / exécuter / vérifier | valider, exécuter, contestations | lire | préparer, valider | | vérifier | | candidater, recalculer |
| Assemblées, documents | lire | lire | ✓ | documents | lire | | lire publiés |
| Journal d'audit | ✓ | | | | ✓ | ✓ | |
| Comptes, rôles, paramètres | | | | | | ✓ | |

**Séparation des tâches**, imposée par le code et testée : le saisissant d'une dépense ne l'approuve pas,
l'approbateur ne la paie pas, au-delà de 500 000 FCFA deux approbations distinctes sont exigées
(`FinanceIT`) ; un encaissement manuel est confirmé par une autre personne que celle qui l'a saisi (`PaymentIT`) ;
un rapport est publié par une autre personne que son auteur (`FinanceIT`) ; une même personne ne compte pas deux
fois dans les validations d'attribution et un candidat ne valide pas sa propre procédure (`AllocationIT`) ;
l'administrateur technique ne peut pas modifier ses propres rôles, et les cumuls incompatibles (président et trésorier) sont refusés (`ApiSweepIT`).

## 3. Protection de l'API et de l'interface (OWASP Top 10)

| Risque | Protection |
|---|---|
| Contrôle d'accès défaillant (A01) | Permissions par route + contrôle par ressource ; balayage de toutes les routes du bureau par rôle (`ApiSweepIT`) |
| Défaillances cryptographiques (A02) | HTTPS obligatoire en production (cookie `Secure` exigé), HSTS ; Argon2id ; secrets en variables d'environnement ; codes et jetons aléatoires `SecureRandom`, stockés hachés (SHA-256) |
| Injection (A03) | Requêtes JPA paramétrées, aucune concaténation SQL ; validation Bean Validation de chaque DTO ; cellules Excel commençant par `= + - @` neutralisées (`CommonTest.excelExportNeutralisesFormulas`) ; Angular échappe toute interpolation |
| Conception non sûre (A04) | Invariants financiers dans la base (triggers, contraintes) en plus du code ; idempotence ; double validation |
| Mauvaise configuration (A05) | Démarrage refusé si configuration dangereuse en production ; erreurs sans trace ni message interne (`include-stacktrace: never`) ; Swagger désactivé par défaut en production et non publié par nginx ; `/actuator` réservé à l'administrateur (seules les sondes de santé sont publiques) |
| Composants vulnérables (A06) | `npm audit` : 0 vulnérabilité ; audit exécuté en CI ; dépendances aux dernières versions stables à la date du développement |
| Authentification (A07) | Voir section 1 |
| Intégrité des données (A08) | Webhooks signés HMAC-SHA256 avec anti-rejeu ; journal d'audit chaîné par empreintes ; documents avec empreinte SHA-256 |
| Journalisation (A09) | Audit de toutes les opérations sensibles (connexions, droits, finances, attribution, documents) ; aucun mot de passe, jeton ni corps de réponse de fournisseur dans les journaux |
| SSRF (A10) | Le serveur n'appelle que des adresses configurées (Wave, SMTP), jamais une URL fournie par un utilisateur |

En-têtes HTTP envoyés par nginx : `Content-Security-Policy` stricte (`script-src 'self'`, aucun script en ligne
ni tiers, `frame-ancestors 'none'`), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`,
`Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Strict-Transport-Security`. Les parcours Playwright échouent
à la moindre violation de CSP ou erreur JavaScript ; ils passent contre l'image de production.

## 4. Documents et fichiers

- Types acceptés : PDF, PNG, JPEG, **détectés par leur contenu** (signature binaire), pas par l'extension ni le
  type annoncé ; 10 Mio maximum ; nom de fichier assaini ; fichier stocké sous un nom généré, hors de la racine web.
- Visibilité : `PUBLIC`, `MEMBRES`, `BUREAU` ou `PRIVE_MEMBRE` (membre concerné seul) ; chaque téléchargement vérifie le
  droit d'accès (`FinanceIT.uploadsAreCheckedByContentAndPrivateDocumentsStayPrivate`).
- Empreinte SHA-256 enregistrée ; le contenu d'un document archivé ne peut plus être modifié (trigger).
- Les reçus et PV ne sont **pas signés électroniquement** ; ils le disent explicitement.

## 5. Données personnelles (loi sénégalaise n° 2008-12, CDP)

Ce qui est en place :

- **Minimisation** : identité, coordonnées, numéro d'adhérent, situation financière ; aucune pièce d'identité
  obligatoire ; aucune donnée sensible au sens de la loi.
- **Cloisonnement** : un membre ne voit jamais les données d'un autre. Les résultats d'attribution publiés aux
  membres ne contiennent que des numéros d'adhérent et des références de lot ; la transparence financière ne
  montre que des agrégats.
- **Demandes des personnes** : un membre dépose une demande d'accès, de rectification, d'opposition, de
  suppression ou de portabilité depuis « Mon compte » ; le bureau la traite avec un statut et une réponse tracés.
  Le membre peut exporter ses propres données.
- **Durées de conservation configurables** (table `data_retention_policy`) : valeurs indicatives (pièces
  comptables 10 ans, dossier membre 5 ans après sortie, journal de sécurité 12 mois, notifications 24 mois,
  procédures d'attribution 10 ans) **à arrêter avec un juriste**.
- **Démonstration** : identités fictives sur un domaine réservé (`.invalid`), jamais de données réelles.

Ce qui reste à la charge de la coopérative avant une exploitation réelle : déclaration ou demande d'autorisation
auprès de la Commission de Protection des Données Personnelles (CDP), information des membres (mentions,
finalités, durées), registre des traitements, contrat avec l'hébergeur (localisation des données, transferts
hors du Sénégal), procédure de notification des violations, purge effective à l'échéance des durées de
conservation (les durées sont paramétrées mais **la purge automatique n'est pas implémentée**).

## 6. Secrets et exploitation

- Aucun secret dans le dépôt ni dans le frontend ; `.env` ignoré par Git ; `.env.example` sans valeur réelle.
- Conteneurs exécutés sous un utilisateur non privilégié ; base et serveur non exposés sur l'hôte.
- Sauvegardes produites avec des permissions `600`/`700`, chiffrables avec GnuPG (`BACKUP_GPG_RECIPIENT`).

## 7. Limites et travaux recommandés

- Test d'intrusion et revue de code indépendants avant toute mise en service avec des fonds réels.
- Double authentification (TOTP) pour les rôles financiers : non implémentée.
- Limitation de débit en mémoire : à déplacer dans un stockage partagé si plusieurs instances.
- Analyse antivirus des fichiers téléversés : non implémentée (seuls les types sûrs sont acceptés).
- Rotation du secret JWT : possible en redémarrant avec un nouveau secret (toutes les sessions sont alors fermées).
