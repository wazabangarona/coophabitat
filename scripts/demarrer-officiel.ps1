# Démarre la version OFFICIELLE locale sous Windows : aucune donnée fictive, aucun simulateur de paiement,
# demandes d'adhésion en ligne ouvertes. Elle tourne à côté de la démonstration (projet Docker et données séparés).
# Usage : powershell -ExecutionPolicy Bypass -File scripts\demarrer-officiel.ps1 [-Admin adresse] [-Port 8091]
# Au premier lancement, .env.officiel est créé avec des secrets aléatoires (ne pas le partager, ne pas le versionner)
# et un lien d'activation à usage unique est affiché pour le premier administrateur.
param([string]$Admin = "", [int]$Port = 8091)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
$EnvFile = ".env.officiel"

function Hex([int]$n) {
  $b = New-Object byte[] $n
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  -join ($b | ForEach-Object { $_.ToString("x2") })
}

if (-not (Test-Path $EnvFile)) {
  if (-not $Admin) { $Admin = Read-Host "Adresse électronique du premier administrateur" }
  $conf = @(
    "# Version officielle locale - généré le $(Get-Date -Format yyyy-MM-dd). Secrets propres à cette installation.",
    "COOPHABITAT_ENVIRONMENT=PRODUCTION",
    "COOPHABITAT_PUBLIC_URL=http://localhost:$Port",
    "COOPHABITAT_HTTP_BIND=127.0.0.1",
    "COOPHABITAT_HTTP_PORT=$Port",
    "COOPHABITAT_DB_PASSWORD=$(Hex 24)",
    "COOPHABITAT_JWT_SECRET=$(Hex 48)",
    "COOPHABITAT_COOKIE_SECURE=true",
    "COOPHABITAT_BOOTSTRAP_ADMIN_EMAIL=$Admin",
    "COOPHABITAT_PUBLIC_REGISTRATION=true",
    "COOPHABITAT_PAYMENT_MOCK_ENABLED=false",
    "COOPHABITAT_DEMO_ENABLED=false",
    "# Courriel (facultatif) : sans SMTP, la réinitialisation de mot de passe par courriel est indisponible.",
    "COOPHABITAT_MAIL_ENABLED=false"
  )
  [System.IO.File]::WriteAllLines((Join-Path (Get-Location) $EnvFile), $conf)
  Write-Host "Configuration créée : $EnvFile"
}

$compose = @("compose", "-p", "coophabitat-officiel", "--env-file", $EnvFile, "-f", "docker-compose.yml")
& docker @compose up -d --build --wait
if ($LASTEXITCODE -ne 0) { throw "Le démarrage a échoué (docker compose)." }
$Port = (Select-String -Path $EnvFile -Pattern '^COOPHABITAT_HTTP_PORT=(\d+)').Matches[0].Groups[1].Value
Write-Host ""
Write-Host "Version officielle prête : http://localhost:$Port"
$lien = (& docker @compose logs backend 2>$null | Select-String "lien d'activation" | Select-Object -Last 1)
if ($lien) { Write-Host ("Premier administrateur : " + ($lien.ToString() -replace "^.*?(lien d'activation)", '$1')) }
Write-Host "Les nouveaux membres cliquent sur « Adhérer » ; le bureau active ensuite leur adhésion."
Write-Host "Arrêt : docker $($compose -join ' ') down   (sans -v, pour garder les données)"
