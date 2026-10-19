#!/usr/bin/env bash
# Restauration de CoopHabitat depuis un dossier produit par scripts/backup.sh.
# REMPLACE INTÉGRALEMENT la base et les documents actuels. Exige une confirmation explicite.
# Usage : scripts/restore.sh <dossier_de_sauvegarde> --confirmer <nom_de_la_base>
set -euo pipefail

COMPOSE=${COMPOSE:-docker compose}
SRC=${1:?Usage : scripts/restore.sh <dossier_de_sauvegarde> --confirmer <nom_de_la_base>}
[[ "${2:-}" == "--confirmer" ]] || { echo "Refusé : ajouter --confirmer <nom_de_la_base>"; exit 2; }
CONFIRM=${3:-}

for f in base.dump documents.tar.gz MANIFESTE.txt SHA256SUMS; do
  [[ -f "$SRC/$f" ]] || { echo "Fichier manquant : $SRC/$f"; exit 2; }
done
echo "→ Vérification des empreintes"
(cd "$SRC" && sha256sum --quiet -c SHA256SUMS)
cat "$SRC/MANIFESTE.txt"

read -r DB_USER DB_NAME <<<"$($COMPOSE exec -T db sh -c "printf '%s %s' \"\$POSTGRES_USER\" \"\$POSTGRES_DB\"")"
if [[ "$CONFIRM" != "$DB_NAME" ]]; then
  echo "Refusé : la confirmation doit être le nom exact de la base ciblée ($DB_NAME)."; exit 2
fi

echo "→ Arrêt de l'application (la base reste démarrée)"
$COMPOSE stop web backend

echo "→ Recréation de la base $DB_NAME"
$COMPOSE exec -T db psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -qc "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)"
$COMPOSE exec -T db psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -qc "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\""
$COMPOSE exec -T db pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges --exit-on-error < "$SRC/base.dump"

echo "→ Restauration des documents"
$COMPOSE run --rm --no-deps -T --entrypoint sh backend -c \
  'find /data/documents -mindepth 1 -delete && tar -xzf - -C /data/documents' < "$SRC/documents.tar.gz"

echo "→ Redémarrage"
$COMPOSE up -d backend web
echo "✓ Restauration terminée. Vérifier ensuite l'intégrité du journal d'audit (Bureau → Journal d'audit)."
