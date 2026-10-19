#!/usr/bin/env bash
# Sauvegarde complète de CoopHabitat : base PostgreSQL (format personnalisé pg_dump) + documents stockés.
# Usage : scripts/backup.sh [dossier_de_destination]      (défaut : ./backups)
# Variables : COMPOSE (défaut « docker compose »), BACKUP_GPG_RECIPIENT (chiffrement facultatif par GnuPG).
# À planifier quotidiennement (cron) et à copier hors du serveur : voir docs/deploiement.md.
set -euo pipefail

COMPOSE=${COMPOSE:-docker compose}
DEST_ROOT=${1:-./backups}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="$DEST_ROOT/coophabitat-$STAMP"
umask 077
mkdir -p "$DEST"

db_env() { $COMPOSE exec -T db sh -c "printf '%s %s' \"\$POSTGRES_USER\" \"\$POSTGRES_DB\""; }
read -r DB_USER DB_NAME <<<"$(db_env)"

echo "→ Base de données $DB_NAME"
$COMPOSE exec -T db pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --no-owner --no-privileges > "$DEST/base.dump"

echo "→ Documents"
$COMPOSE exec -T backend tar -C /data/documents -czf - . > "$DEST/documents.tar.gz"

MIGRATION=$($COMPOSE exec -T db psql -U "$DB_USER" -d "$DB_NAME" -Atc \
  "select coalesce(max(version),'?') from flyway_schema_history where success" | tr -d '\r')
cat > "$DEST/MANIFESTE.txt" <<MANIFEST
Sauvegarde CoopHabitat
Date (UTC)          : $STAMP
Base                : $DB_NAME
Migration Flyway    : $MIGRATION
Contenu             : base.dump (pg_dump --format=custom), documents.tar.gz
Contient des données personnelles et financières : conserver chiffré, accès restreint.
MANIFEST

(cd "$DEST" && sha256sum base.dump documents.tar.gz MANIFESTE.txt > SHA256SUMS)

if [[ -n "${BACKUP_GPG_RECIPIENT:-}" ]]; then
  tar -C "$DEST_ROOT" -cf - "coophabitat-$STAMP" | gpg --batch --yes --encrypt --recipient "$BACKUP_GPG_RECIPIENT" \
    --output "$DEST.tar.gpg"
  rm -rf "$DEST"
  echo "✓ Sauvegarde chiffrée : $DEST.tar.gpg"
else
  echo "✓ Sauvegarde : $DEST (non chiffrée : définir BACKUP_GPG_RECIPIENT pour la chiffrer)"
fi
