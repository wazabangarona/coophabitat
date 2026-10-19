#!/usr/bin/env bash
# Démarre la pile de DÉMONSTRATION locale (identités fictives, simulateur de paiement) avec Docker Compose.
# Usage : COOPHABITAT_DEMO_PASSWORD='un-mot-de-passe-long' scripts/demarrer-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${COOPHABITAT_DEMO_PASSWORD:-}" ]]; then
  echo "Définir COOPHABITAT_DEMO_PASSWORD (12 caractères minimum) : mot de passe commun des comptes fictifs."; exit 2
fi
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait
PORT=${COOPHABITAT_HTTP_PORT:-8080}
echo
echo "✓ Démonstration prête : http://localhost:$PORT"
echo "  Comptes fictifs : presidence@, tresorerie@, secretariat@, audit@, admin@, membre1@ … membre8@demo.coophabitat.invalid"
echo "  SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ"
echo "  Arrêt : docker compose -f docker-compose.yml -f docker-compose.demo.yml down   (ajouter -v pour effacer les données)"
