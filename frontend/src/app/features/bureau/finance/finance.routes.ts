import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet, Routes } from '@angular/router';
import { Auth } from '../../../core/auth';
import { permissionGuard } from '../../../core/guards';

const TABS = [
  { path: 'synthese', label: 'Synthèse', perm: 'ACCOUNTING_READ' },
  { path: 'depenses', label: 'Dépenses', perm: 'EXPENSE_CREATE|ACCOUNTING_READ' },
  { path: 'budgets', label: 'Budgets', perm: 'ACCOUNTING_READ' },
  { path: 'ecritures', label: 'Écritures et balance', perm: 'ACCOUNTING_READ' },
  { path: 'rapprochement', label: 'Rapprochement bancaire', perm: 'ACCOUNTING_READ' },
  { path: 'rapports', label: 'Rapports', perm: 'ACCOUNTING_READ' },
];

@Component({
  selector: 'app-finance-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <nav class="tabs" aria-label="Sections financières">
      @for (t of tabs(); track t.path) { <a [routerLink]="t.path" routerLinkActive="active">{{ t.label }}</a> }
    </nav>
    <router-outlet />
  `,
})
export class FinanceShell {
  private readonly auth = inject(Auth);
  protected readonly tabs = computed(() => TABS.filter((t) => this.auth.hasAny(...t.perm.split('|'))));
}

const t = (s: string) => `${s} · Finances · CoopHabitat`;

export const FINANCE_ROUTES: Routes = [
  {
    path: '',
    component: FinanceShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: () => (inject(Auth).has('ACCOUNTING_READ') ? 'synthese' : 'depenses') },
      { path: 'synthese', title: t('Synthèse'), canActivate: [permissionGuard('ACCOUNTING_READ')], loadComponent: () => import('./finance-summary').then((m) => m.FinanceSummary) },
      { path: 'depenses', title: t('Dépenses'), loadComponent: () => import('./expenses').then((m) => m.Expenses) },
      { path: 'budgets', title: t('Budgets'), canActivate: [permissionGuard('ACCOUNTING_READ')], loadComponent: () => import('./budgets').then((m) => m.Budgets) },
      { path: 'ecritures', title: t('Écritures'), canActivate: [permissionGuard('ACCOUNTING_READ')], loadComponent: () => import('./ledger').then((m) => m.Ledger) },
      { path: 'rapprochement', title: t('Rapprochement'), canActivate: [permissionGuard('ACCOUNTING_READ')], loadComponent: () => import('./bank').then((m) => m.Bank) },
      { path: 'rapports', title: t('Rapports'), canActivate: [permissionGuard('ACCOUNTING_READ')], loadComponent: () => import('./reports').then((m) => m.Reports) },
    ],
  },
];
