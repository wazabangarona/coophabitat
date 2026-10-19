import { Page, expect, test } from '@playwright/test';

const PASSWORD = process.env['E2E_DEMO_PASSWORD'] ?? 'Demo-CoopHabitat-2026';

async function login(page: Page, email: string): Promise<void> {
  await page.goto('/connexion');
  await page.getByLabel('Adresse électronique').fill(email);
  await page.getByLabel('Mot de passe').fill(PASSWORD);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).not.toHaveURL(/connexion/);
}

// Toute violation de la politique de sécurité du contenu ou erreur JavaScript non interceptée fait échouer le test.
const pageProblems = new WeakMap<Page, string[]>();
test.beforeEach(({ page }) => {
  const problems: string[] = [];
  pageProblems.set(page, problems);
  page.on('pageerror', (e) => problems.push(`Erreur JavaScript : ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && /Content Security Policy|Refused to/i.test(m.text())) {
      problems.push(`CSP : ${m.text()}`);
    }
  });
});
test.afterEach(({ page }) => {
  expect(pageProblems.get(page) ?? []).toEqual([]);
});

async function noHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

test('le site public affiche la garde des fonds réelle et les bandeaux d’environnement', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByText('Fonds suivis comptablement — séquestre externe non vérifié ou non intégré').first()).toBeVisible();
  await expect(page.getByText(/toutes les personnes, montants et documents affichés sont fictifs/)).toBeVisible();
  await expect(page.getByText('SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ').first()).toBeVisible();
  await noHorizontalScroll(page);
});

test('un reçu inconnu est déclaré non valide', async ({ page }) => {
  await page.goto('/verification-recu');
  await page.getByLabel('Numéro du reçu').fill('REC-0000-000000');
  await page.getByLabel('Code de vérification').fill('FAUXCODE00');
  await page.getByRole('button', { name: 'Vérifier' }).click();
  await expect(page.getByText(/Aucun reçu|introuvable|non valide|ne correspond/i).first()).toBeVisible();
});

test('un membre paie une échéance avec le simulateur sans que la demande soit comptée avant confirmation', async ({ page }) => {
  await login(page, 'membre2@demo.coophabitat.invalid');
  await expect(page.getByRole('heading', { name: /Bonjour/ })).toBeVisible();
  await expect(page.getByText('Reste à payer (échu)')).toBeVisible();
  await noHorizontalScroll(page);

  await page.goto('/espace/cotisations');
  await page.getByRole('button', { name: 'Payer' }).first().click();
  await page.getByLabel('Simulateur').check();
  await expect(page.getByText('SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ').first()).toBeVisible();
  await page.getByRole('button', { name: 'Continuer' }).click();

  await expect(page).toHaveURL(/\/espace\/paiements\/simulation\//);
  await page.getByRole('button', { name: 'Simuler un paiement réussi' }).click();

  await expect(page).toHaveURL(/\/espace\/paiements\/suivi\//);
  await expect(page.getByText('Paiement confirmé')).toBeVisible();
  await expect(page.getByText('SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ').first()).toBeVisible();

  await page.goto('/espace/paiements');
  await expect(page.getByText('Simulation').first()).toBeVisible();
  await noHorizontalScroll(page);
});

test('un membre ne peut pas ouvrir l’espace de gestion', async ({ page }) => {
  await login(page, 'membre3@demo.coophabitat.invalid');
  await expect(page.getByRole('heading', { name: /Bonjour/ })).toBeVisible();
  await page.goto('/bureau/membres');
  await expect(page.getByRole('heading', { name: 'Accès refusé' })).toBeVisible();
});

test('la présidence consulte le tableau de bord, un membre et l’intégrité du journal', async ({ page }) => {
  await login(page, 'presidence@demo.coophabitat.invalid');
  await expect(page.getByRole('heading', { name: 'Tableau de bord' })).toBeVisible();
  await expect(page.getByText('Taux de recouvrement du mois')).toBeVisible();
  await noHorizontalScroll(page);

  await page.goto('/bureau/membres');
  await page.getByRole('link', { name: /CH-\d{4}-\d{4}/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Échéances' })).toBeVisible();

  await page.goto('/bureau/audit');
  await page.getByRole('button', { name: 'Vérifier l’intégrité de la chaîne' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'événements contrôlés' })).toBeVisible();
});

test('la trésorerie voit la procédure d’attribution et ses étapes', async ({ page }) => {
  await login(page, 'tresorerie@demo.coophabitat.invalid');
  await page.goto('/bureau/attributions');
  await page.getByRole('link', { name: /ATT-/ }).first().click();
  await expect(page.getByRole('list', { name: 'Étapes de la procédure' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Intégrité du tirage' })).toBeVisible();
});
