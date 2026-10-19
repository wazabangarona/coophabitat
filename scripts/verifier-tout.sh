#!/usr/bin/env bash
# Rejoue localement les vérifications de l'intégration continue (hors parcours navigateur).
# Prérequis : JDK 21, Maven 3.9, Node 24, Python 3, Docker (Testcontainers lance PostgreSQL 16).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== Serveur : compilation, tests unitaires et d'intégration, couverture JaCoCo (seuil 60 % des lignes)"
(cd backend && mvn -B verify)
python3 - <<'PY'
import csv
rows = list(csv.DictReader(open("backend/target/site/jacoco/jacoco.csv")))
cov = sum(int(r["LINE_COVERED"]) for r in rows); miss = sum(int(r["LINE_MISSED"]) for r in rows)
print(f"   Couverture mesurée : {100 * cov / (cov + miss):.1f} % des lignes (rapport : backend/target/site/jacoco/index.html)")
PY

echo "== Vérificateur indépendant du tirage"
python3 -m unittest discover tools

echo "== Frontend : dépendances, audit, lint, tests unitaires, build de production"
(cd frontend && npm ci --no-audit --no-fund && npm audit --audit-level=high --omit=dev \
  && npm run lint && npx ng test --watch=false && npx ng build --configuration production)

echo "✓ Toutes les vérifications sont passées. Parcours navigateur : voir docs/tests.md."
