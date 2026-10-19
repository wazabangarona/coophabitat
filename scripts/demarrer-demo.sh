#!/usr/bin/env bash
# Démarre la pile de DÉMONSTRATION locale (identités fictives, simulateur de paiement) avec Docker Compose.
# Usage : COOPHABITAT_DEMO_PASSWORD='un-mot-de-passe-long' [COOPHABITAT_HTTP_PORT=8090] scripts/demarrer-demo.sh
# Si le port demandé (8090 par défaut) est déjà occupé, le premier port libre parmi les 20 suivants est utilisé.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${COOPHABITAT_DEMO_PASSWORD:-}" ]]; then
  echo "Définir COOPHABITAT_DEMO_PASSWORD (12 caractères minimum) : mot de passe commun des comptes fictifs."; exit 2
fi
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.demo.yml)

port_occupe() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

DEMANDE=${COOPHABITAT_HTTP_PORT:-8090}
# Port déjà publié par cette même pile (redémarrage) : on le garde.
ACTUEL=$("${COMPOSE[@]}" port web 8080 2>/dev/null | sed -n 's/.*:\([0-9]*\)$/\1/p' | head -n1 || true)
if [[ -n "$ACTUEL" ]]; then
  PORT=$ACTUEL
else
  PORT=$DEMANDE
  while port_occupe "$PORT"; do
    PORT=$((PORT + 1))
    if (( PORT > DEMANDE + 20 )); then
      echo "Aucun port libre entre $DEMANDE et $((DEMANDE + 20)). Choisir un port : COOPHABITAT_HTTP_PORT=9000 $0"; exit 3
    fi
  done
  [[ "$PORT" != "$DEMANDE" ]] && echo "Le port $DEMANDE est déjà occupé : utilisation du port $PORT."
fi
export COOPHABITAT_HTTP_PORT=$PORT

"${COMPOSE[@]}" up -d --build --wait
echo
echo "✓ Démonstration prête : http://localhost:$PORT"
echo "  Comptes fictifs : presidence@, tresorerie@, secretariat@, audit@, admin@, membre1@ … membre8@demo.coophabitat.invalid"
echo "  SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ"
echo "  Arrêt : docker compose -f docker-compose.yml -f docker-compose.demo.yml down   (ajouter -v pour effacer les données)"
