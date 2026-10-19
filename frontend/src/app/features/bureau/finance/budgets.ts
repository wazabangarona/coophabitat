import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../../core/api';
import { BudgetResponse } from '../../../core/api-types';
import { Auth } from '../../../core/auth';
import { Dialogs } from '../../../core/dialog';
import { Busy, Loader } from '../../../core/loader';
import { Toasts } from '../../../core/toast';
import { Badge, PageHeader, StateView } from '../../../ui/components';
import { LabelPipe, MoneyPipe } from '../../../ui/pipes';
import { EXPENSE_CATEGORIES } from './expenses';

const REVENUE = ['COTISATIONS', 'PENALITES'];
interface Line { category: string; direction: string; plannedAmount: number }

@Component({
  selector: 'app-budgets',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, MoneyPipe],
  template: `
    <app-page-header title="Budgets" subtitle="Prévu, réalisé (comptabilité), engagé et reste, par catégorie.">
      @if (auth.has('BUDGET_WRITE')) { <button type="button" class="btn small primary" (click)="openForm(null)">Nouveau budget</button> }
    </app-page-header>
    @if (form()) {
      <section class="card"><h2>{{ editId ? 'Modifier le budget' : 'Nouveau budget' }}</h2>
        <form (ngSubmit)="save()" #f="ngForm">
          <div class="grid-2">
            <label class="field"><span>Exercice</span><input type="number" name="y" [(ngModel)]="b.fiscalYear" required min="2000" max="2200" /></label>
            <label class="field"><span>Libellé</span><input name="l" [(ngModel)]="b.label" required /></label>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Sens</th><th>Catégorie</th><th class="num">Montant prévu (XOF)</th><th></th></tr></thead>
            <tbody>@for (l of b.lines; track $index; let i = $index) {
              <tr><td data-label="Sens"><select [(ngModel)]="l.direction" [name]="'d' + i" (ngModelChange)="l.category = l.direction === 'RECETTE' ? 'COTISATIONS' : 'FOURNITURES'">
                  <option value="RECETTE">Recette</option><option value="DEPENSE">Dépense</option></select></td>
                <td data-label="Catégorie"><select [(ngModel)]="l.category" [name]="'c' + i">@for (c of (l.direction === 'RECETTE' ? revenue : expense); track c) { <option [value]="c">{{ c | label }}</option> }</select></td>
                <td data-label="Prévu" class="num"><input type="number" [(ngModel)]="l.plannedAmount" [name]="'a' + i" min="0" step="1" required /></td>
                <td><button type="button" class="btn small" (click)="b.lines.splice(i, 1)" aria-label="Supprimer la ligne">Retirer</button></td></tr>
            }</tbody>
          </table></div>
          <div class="row"><button type="button" class="btn small" (click)="b.lines.push({ category: 'FOURNITURES', direction: 'DEPENSE', plannedAmount: 0 })">Ajouter une ligne</button>
            <span class="grow"></span><button type="button" class="btn" (click)="form.set(false)">Annuler</button>
            <button class="btn primary" [disabled]="f.invalid || busy.on()">Enregistrer en brouillon</button></div>
        </form>
      </section>
    }
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length" emptyText="Aucun budget." (retry)="list.reload()">
      @for (x of list.data(); track x.id) {
        <section class="card">
          <div class="row between"><h2>{{ x.fiscalYear }} — {{ x.label }}</h2><app-badge [value]="x.status" /></div>
          @if (x.adoptedReference) { <p class="small muted">Adopté : {{ x.adoptedReference }}</p> }
          <p class="small">Recettes : {{ x.realizedRevenue | money }} réalisées / {{ x.plannedRevenue | money }} prévues ·
            Dépenses : {{ x.realizedExpense | money }} réalisées + {{ x.committedExpense | money }} engagées / {{ x.plannedExpense | money }} prévues</p>
          <div class="table-wrap"><table>
            <thead><tr><th>Ligne</th><th class="num">Prévu</th><th class="num">Réalisé</th><th class="num">Engagé</th><th class="num">Reste</th><th class="num">Exécution</th></tr></thead>
            <tbody>@for (l of x.lines; track $index) {
              <tr><td data-label="Ligne">{{ l.direction | label }} — {{ l.label || (l.category | label) }}</td><td data-label="Prévu" class="num">{{ l.planned | money }}</td>
                <td data-label="Réalisé" class="num">{{ l.realized | money }}</td><td data-label="Engagé" class="num">{{ l.committed | money }}</td>
                <td data-label="Reste" class="num">{{ l.remaining | money }}</td><td data-label="Exécution" class="num">{{ l.executionRatePercent }} %</td></tr>
            }</tbody>
          </table></div>
          @if (auth.has('BUDGET_WRITE')) {
            <div class="row">
              @if (x.status === 'BROUILLON') { <button type="button" class="btn small" (click)="openForm(x)">Modifier</button>
                <button type="button" class="btn small primary" (click)="adopt(x)">Marquer comme adopté</button> }
              @if (x.status === 'ADOPTE') { <button type="button" class="btn small" (click)="close(x)">Clôturer</button> }
            </div>
          }
        </section>
      }
    </app-state>
  `,
})
export class Budgets implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly list = new Loader<BudgetResponse[]>();
  protected readonly form = signal(false);
  protected readonly busy = new Busy();
  protected readonly revenue = REVENUE;
  protected readonly expense = EXPENSE_CATEGORIES;
  protected editId: number | null = null;
  protected b = this.blank();

  ngOnInit(): void {
    this.list.load(() => this.api.get<BudgetResponse[]>('/finance/budgets'));
  }

  openForm(x: BudgetResponse | null): void {
    this.editId = x?.id ?? null;
    this.b = x ? { fiscalYear: x.fiscalYear ?? new Date().getFullYear(), label: x.label ?? '',
      lines: (x.lines ?? []).map((l) => ({ category: l.category ?? '', direction: l.direction ?? 'DEPENSE', plannedAmount: l.planned ?? 0 })) } : this.blank();
    this.form.set(true);
  }

  save(): void {
    const req = this.editId ? this.api.put(`/finance/budgets/${this.editId}`, this.b) : this.api.post('/finance/budgets', this.b);
    this.busy.run(req, () => { this.toasts.success('Budget enregistré.'); this.form.set(false); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  async adopt(x: BudgetResponse): Promise<void> {
    const ref = await this.dialogs.ask({ title: `Adoption du budget ${x.fiscalYear}`, message: 'L’adoption relève de l’assemblée générale : indiquez la décision qui l’a votée. Le budget sera ensuite figé.', reasonLabel: 'Référence de la décision (résolution, date de l’AG)' });
    if (ref === null) return;
    this.busy.run(this.api.post(`/finance/budgets/${x.id}/adopt`, { adoptedReference: ref }), () => { this.toasts.success('Budget adopté.'); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  async close(x: BudgetResponse): Promise<void> {
    if ((await this.dialogs.ask({ title: `Clôturer le budget ${x.fiscalYear} ?` })) === null) return;
    this.busy.run(this.api.post(`/finance/budgets/${x.id}/close`), () => { this.toasts.success('Budget clôturé.'); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  private blank(): { fiscalYear: number; label: string; lines: Line[] } {
    return { fiscalYear: new Date().getFullYear() + 1, label: '', lines: [{ category: 'COTISATIONS', direction: 'RECETTE', plannedAmount: 0 }] };
  }
}
