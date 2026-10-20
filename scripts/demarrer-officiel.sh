#!/usr/bin/env bash
# Démarre la version OFFICIELLE locale : aucune donnée fictive, aucun simulateur de paiement, demandes
# d'adhésion en ligne ouvertes. Elle tourne à côté de la démonstration (projet Docker et données séparés).
# Usage : scripts/demarrer-officiel.sh [adresse-de-l-administrateur]   (port 8091 par défaut)
# Au premier lancement, .env.officiel est créé avec des secrets aléatoires (ne pas le partager, ne pas le versionner)
# et un lien d'activation à usage unique est affiché pour le premier administrateur.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=.env.officiel
if [[ ! -f "$ENV_FILE" ]]; then
  ADMIN=${1:-}
  if [[ -z "$ADMIN" ]]; then read -rp "Adresse électronique du premier administrateur : " ADMIN; fi
  PORT=${COOPHABITAT_HTTP_PORT:-8091}
  umask 077
  cat > "$ENV_FILE" <<CONF
# Version officielle locale — généré le $(date +%F). Secrets propres à cette installation.
COOPHABITAT_ENVIRONMENT=PRODUCTION
COOPHABITAT_PUBLIC_URL=http://localhost:$PORT
COOPHABITAT_HTTP_BIND=127.0.0.1
COOPHABITAT_HTTP_PORT=$PORT
COOPHABITAT_DB_PASSWORD=$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')
COOPHABITAT_JWT_SECRET=$(od -An -tx1 -N48 /dev/urandom | tr -d ' \n')
COOPHABITAT_COOKIE_SECURE=true
COOPHABITAT_BOOTSTRAP_ADMIN_EMAIL=$ADMIN
COOPHABITAT_PUBLIC_REGISTRATION=true
COOPHABITAT_PAYMENT_MOCK_ENABLED=false
COOPHABITAT_DEMO_ENABLED=false
# Courriel (facultatif) : sans SMTP, la réinitialisation de mot de passe par courriel est indisponible.
COOPHABITAT_MAIL_ENABLED=false
CONF
  echo "Configuration créée : $ENV_FILE"
fi
COMPOSE=(docker compose -p coophabitat-officiel --env-file "$ENV_FILE" -f docker-compose.yml)
"${COMPOSE[@]}" up -d --build --wait
PORT=$(sed -n 's/^COOPHABITAT_HTTP_PORT=//p' "$ENV_FILE")
echo
echo "✓ Version officielle prête : http://localhost:$PORT"
LIEN=$("${COMPOSE[@]}" logs backend 2>/dev/null | grep -o "lien d'activation.*" | tail -n1 || true)
[[ -n "$LIEN" ]] && echo "  Premier administrateur, $LIEN"
echo "  Les nouveaux membres cliquent sur « Adhérer » ; le bureau active ensuite leur adhésion."
echo "  Arrêt : ${COMPOSE[*]} down   (sans -v, pour garder les données)"
