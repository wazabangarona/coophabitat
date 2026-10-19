# Attribution des lots

Ce document s'adresse aux membres comme au bureau. Il explique comment un lot est attribué, ce que chacun peut
vérifier, et ce que le dispositif prouve ou ne prouve pas.

## 1. Principe

Aucun lot ne peut être attribué « à la main ». Une attribution passe toujours par une **procédure** enregistrée,
dont chaque étape est datée, signée par la personne qui l'accomplit et inscrite dans le journal d'audit.
Deux méthodes existent :

- **Tirage au sort vérifiable** : l'ordre des candidats éligibles est tiré par un algorithme public que tout
  membre peut refaire lui-même.
- **Décision de commission** : chaque attribution est motivée par écrit et validée par plusieurs membres de la
  commission, distincts, qui ne sont pas candidats.

## 2. Les étapes

| Étape | Qui | Ce qui est figé ou publié |
|---|---|---|
| 1. Création de la procédure, choix des lots | Secrétariat | Les lots passent de « disponible » à « réservé » : ils ne peuvent plus être engagés ailleurs |
| 2. Publication des critères et des règles | Secrétariat | Ancienneté minimale, types de membres, arriérés tolérés, date de référence ; **plus modifiables ensuite** ; notification aux membres |
| 3. Ouverture des candidatures | Secrétariat | Les membres candidatent ou se retirent depuis leur espace |
| 4. Clôture et vérification d'éligibilité | Secrétariat | Pour chaque candidat : éligible ou non, **avec le motif** et un instantané des données utilisées (statut, ancienneté, arriérés à la date de référence) |
| 5. Publication de la liste | Secrétariat | Liste figée des participants et des lots, leurs **empreintes**, et l'**engagement** sur la graine secrète du tirage |
| 6. Validation de la liste | Deux responsables distincts | Aucun candidat ne peut valider ; une même personne ne compte qu'une fois |
| 7. Exécution du tirage | Président | **Une seule fois** ; avec l'aléa public annoncé en séance |
| 8. Procès-verbal | Automatique | PDF versionné reprenant critères, liste, empreintes, graine, aléa, résultats |
| 9. Validation du PV | Deux responsables distincts | Résultats « définitifs », lots « attribués » |
| 10. Publication des résultats | Secrétariat | Numéros d'adhérent et lots (jamais les noms) ; graine révélée ; notification des bénéficiaires |
| 11. Contestations | Membre candidat → président | Motif écrit, décision motivée et tracée |
| 12. Archivage, ou annulation formelle d'un résultat | Bureau | Annulation avec motif et plusieurs validations ; le lot redevient disponible |

Garde-fous techniques : un lot ne peut avoir qu'un seul résultat actif (index unique en base) ni être engagé
dans deux procédures ; un candidat n'a qu'un résultat actif ; le tirage ne peut pas être relancé ; rien n'est
supprimé (annulations par statut). Tests : `AllocationIT`.

## 3. Le tirage au sort expliqué simplement

Le problème à résoudre : prouver que personne, pas même l'administrateur de la base de données, n'a pu choisir
le résultat. La méthode utilisée s'appelle « engagement puis révélation ».

1. **Avant le tirage**, le serveur tire au hasard un grand nombre secret (la *graine*). Il ne la montre pas,
   mais il publie son **empreinte** (*engagement*). Une empreinte SHA-256 est comme un sceau : on ne peut pas
   retrouver la graine à partir d'elle, mais si la graine est montrée plus tard, chacun peut vérifier qu'elle
   correspond bien au sceau publié. Le serveur ne peut donc plus changer de graine.
2. La liste des participants et celle des lots sont aussi scellées par leurs empreintes : impossible d'ajouter
   ou de retirer quelqu'un après coup sans que cela se voie.
3. **En séance**, on annonce un *aléa public* (par exemple des numéros tirés devant l'assemblée). Personne ne le
   connaît à l'avance, pas même le serveur : celui qui connaît la graine ne peut donc pas prévoir le résultat.
4. Le serveur combine graine, empreintes et aléa public en une *graine finale*, puis mélange la liste des
   participants (triée par numéro d'adhérent) avec une méthode classique et sans biais (mélange de Fisher-Yates).
5. Le premier de la liste mélangée reçoit le premier lot (dans l'ordre alphabétique des références), le deuxième
   le deuxième lot, etc. Les suivants forment la **liste d'attente**, dans l'ordre.
6. **Après la publication**, la graine est révélée. Chacun peut tout refaire et obtenir exactement le même ordre.

Ce que cela prouve : le tirage publié est bien celui qui découle des données scellées avant le tirage et de
l'aléa public ; il n'a pas été retouché. Ce que cela **ne prouve pas** : que les critères d'éligibilité sont
justes ou conformes aux statuts, ni que les données d'éligibilité (ancienneté, arriérés) étaient exactes. Ces
points relèvent de l'assemblée générale et du contrôle des pièces.

## 4. Vérifier soi-même

**Dans l'application** : « Lots et attributions », bouton « Recalculer le tirage » (le serveur refait le calcul
et compare).

**Sans faire confiance au serveur** : la rubrique « Empreintes publiées avant le tirage » affiche toutes les
données nécessaires et une commande prête à copier pour le script `tools/verifier_tirage.py`, qui n'utilise que
Python 3 et sa bibliothèque standard (une soixantaine de lignes de calcul, lisibles) :

```bash
python3 tools/verifier_tirage.py \
  --campagne "ATT-2026-000199" --graine "<graine révélée>" --entropie "<aléa public>" \
  --participants "CH-2024-0001;CH-2024-0002;..." --lots "DEMO-LOT-04;DEMO-LOT-05" \
  --engagement "<engagement>" --empreinte-participants "<empreinte>" --empreinte-lots "<empreinte>"
```

Le script indique si l'engagement et les deux empreintes sont **conformes**, puis affiche l'ordre recalculé.
Un vecteur de référence commun garantit que le script et le serveur calculent la même chose
(`DrawEngineTest.goldenVectorMatchesTheIndependentPythonVerifier` et `tools/test_verifier_tirage.py`).

## 5. Spécification exacte (CH-TIRAGE-v1)

Toutes les chaînes sont encodées en UTF-8 ; `H(x)` = SHA-256 en hexadécimal minuscule ; listes triées par ordre
lexicographique.

```
empreinte_participants = H("CH-TIRAGE-v1|participants|" + campagne + "|" + join(";", numéros triés))
empreinte_lots         = H("CH-TIRAGE-v1|lots|" + campagne + "|" + join(";", références triées))
engagement             = H("CH-TIRAGE-v1|engagement|" + graine_hex)          graine : 32 octets aléatoires
graine_finale          = octets(H(join("|", ["CH-TIRAGE-v1", graine_hex, empreinte_participants,
                                              empreinte_lots, trim(aléa_public)])))
flux                   = blocs HMAC-SHA256(clé = graine_finale, message = compteur sur 8 octets gros-boutiste),
                         compteur = 0, 1, 2… ; chaque bloc de 32 octets fournit 4 entiers de 64 bits
                         gros-boutistes, dont on garde les 63 bits de poids faible
tirage_entier(n)       = rejet des valeurs ≥ (2^63 − 1) − ((2^63 − 1) mod n), puis valeur mod n
mélange                = pour i de n−1 à 1 : j = tirage_entier(i + 1) ; échanger(i, j)
rang k (à partir de 1) = lot k de la liste triée des lots si k ≤ nombre de lots, sinon liste d'attente
```

Implémentations : `backend/…/allocation/DrawEngine.java` et `tools/verifier_tirage.py`.

## 6. Données de démonstration

Le jeu de démonstration contient une procédure terminée (« tranche 0 » : 7 candidatures, 6 éligibles, 2 lots
attribués, 4 en liste d'attente, résultats publiés et recalculables) et une procédure ouverte aux candidatures
(« tranche 1 »). Toutes les personnes et tous les lots sont fictifs.
