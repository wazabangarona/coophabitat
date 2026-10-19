import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, switchMap } from 'rxjs';
import { Api, openBlob } from '../../../core/api';
import { DocumentItem, ExpenseResponse, FinancialAccount, PageResponseExpenseResponse } from '../../../core/api-types';
import { Auth } from '../../../core/auth';
import { Dialogs } from '../../../core/dialog';
import { Busy, Loader } from '../../../core/loader';
import { Toasts } from '../../../core/toast';
import { Badge, PageHeader, Pager, StateView } from '../../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe, formatMoney } from '../../../ui/pipes';

export const EXPENSE_CATEGORIES = ['FOURNITURES', 'DEPLACEMENT', 'HONORAIRES_ETUDES', 'FRAIS_ADMINISTRATIFS', 'FRAIS_BANCAIRES', 'ACQUISITION_TERRAIN', 'CONSTRUCTION', 'DIVERS'];
const STATUSES = ['BROUILLON', 'SOUMISE', 'APPROUVEE', 'REJETEE', 'PAYEE', 'ANNULEE'];

@Component({
  selector: 'app-expenses',
  imports: [FormsModule, PageHeader, StateView, Pager, Badge, LabelPipe, MoneyPipe, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Dépenses" subtitle="Saisie, justificatif, approbation par une autre personne, paiement par une troisième, écriture comptable automatique.">
      @if (auth.has('EXPENSE_CREATE')) { <button type="button" class="btn small primary" (click)="openForm(null)">Nouvelle dépense</button> }
    </app-page-header>
    @if (form()) {
      <section class="card"><h2>{{ editId ? 'Modifier la dépense' : 'Nouvelle dépense' }}</h2>
        <form class="grid-2" (ngSubmit)="save()" #f="ngForm">
          <label class="field span-2"><span>Objet</span><input name="l" [(ngModel)]="e.label" required /></label>
          <label class="field"><span>Catégorie</span><select name="c" [(ngModel)]="e.category">@for (c of categories; track c) { <option [value]="c">{{ c | label }}</option> }</select></label>
          <label class="field"><span>Fournisseur</span><input name="s" [(ngModel)]="e.supplier" /></label>
          <label class="field"><span>Montant (XOF)</span><input type="number" name="a" [(ngModel)]="e.amount" required min="1" step="1" /></label>
          <label class="field"><span>Date de la dépense</span><input type="date" name="d" [(ngModel)]="e.expenseDate" required /></label>
          <div class="span-2 row"><button type="button" class="btn" (click)="form.set(false)">Annuler</button><button class="btn primary" [disabled]="f.invalid || busy.on()">Enregistrer en brouillon</button></div>
        </form>
      </section>
    }
    <div class="filters">
      <label class="field inline"><span>Statut</span><select [(ngModel)]="status" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <label class="field inline"><span>Catégorie</span><select [(ngModel)]="category" (ngModelChange)="go(0)"><option value="">Toutes</option>
        @for (c of categories; track c) { <option [value]="c">{{ c | label }}</option> }</select></label>
    </div>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="Aucune dépense." (retry)="list.reload()">
      @for (x of list.data()?.content; track x.id) {
        <article class="card">
          <div class="row between"><h3>{{ x.label }}</h3><app-badge [value]="x.status" /></div>
          <p class="small muted">{{ x.reference }} · {{ x.categoryLabel || (x.category | label) }} · {{ x.expenseDate | day }}{{ x.supplier ? ' · ' + x.supplier : '' }}</p>
          <p><strong>{{ x.amount | money }}</strong> — validations {{ x.approvalsCount }} / {{ x.requiredApprovals }}
            @if (x.paidAt) { — payée le {{ x.paidAt | when }} depuis {{ x.paymentAccount }} }
            @if (x.cancelReason) { <br /><small>Annulée : {{ x.cancelReason }}</small> }</p>
          @if (x.approvals?.length) {
            <ul class="plain small">@for (a of x.approvals; track $index) { <li>{{ a.decidedAt | when }} — {{ a.approverName }} : <app-badge [value]="a.decision" />{{ a.comment ? ' « ' + a.comment + ' »' : '' }}</li> }</ul>
          }
          <div class="row">
            @if (x.justificationDocumentId) { <button type="button" class="btn small" (click)="doc(x.justificationDocumentId)">Justificatif</button> }
            @if (x.status === 'BROUILLON' && auth.has('EXPENSE_CREATE')) {
              <button type="button" class="btn small" (click)="openForm(x)">Modifier</button>
              <label class="btn small">Joindre un justificatif<input type="file" class="sr" accept="application/pdf,image/jpeg,image/png" (change)="attach(x, $event)" /></label>
              <button type="button" class="btn small primary" (click)="submit(x)" [disabled]="!x.justificationDocumentId">Soumettre</button>
            }
            @if (x.status === 'SOUMISE' && auth.has('EXPENSE_APPROVE')) {
              <button type="button" class="btn small primary" (click)="decide(x, 'APPROUVEE')">Approuver</button>
              <button type="button" class="btn small danger" (click)="decide(x, 'REJETEE')">Rejeter</button>
            }
            @if (x.status === 'APPROUVEE' && auth.has('EXPENSE_PAY')) {
              <select [(ngModel)]="payAccount" aria-label="Compte de paiement">@for (a of treasury(); track a.code) { <option [value]="a.code">{{ a.label }}</option> }</select>
              <button type="button" class="btn small primary" (click)="pay(x)">Enregistrer le paiement</button>
            }
            @if (['BROUILLON', 'SOUMISE', 'APPROUVEE', 'PAYEE'].includes(x.status ?? '') && auth.hasAny('EXPENSE_CREATE', 'ACCOUNTING_WRITE')) {
              <button type="button" class="btn small" (click)="cancel(x)">Annuler</button>
            }
          </div>
        </article>
      }
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class Expenses implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly list = new Loader<PageResponseExpenseResponse>();
  protected readonly treasury = signal<FinancialAccount[]>([]);
  protected readonly form = signal(false);
  protected readonly page = signal(0);
  protected readonly busy = new Busy();
  protected readonly categories = EXPENSE_CATEGORIES;
  protected readonly statuses = STATUSES;
  protected status = '';
  protected category = '';
  protected payAccount = '521100';
  protected editId: number | null = null;
  protected e = this.blank();

  ngOnInit(): void {
    this.go(0);
    if (this.auth.has('ACCOUNTING_READ')) this.api.get<FinancialAccount[]>('/finance/accounts').subscribe((a) => this.treasury.set(a.filter((x) => x.treasury && x.active !== false)));
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseExpenseResponse>('/finance/expenses', { status: this.status, category: this.category, page: p, size: 20 }));
  }

  openForm(x: ExpenseResponse | null): void {
    this.editId = x?.id ?? null;
    this.e = x ? { category: x.category ?? 'DIVERS', label: x.label ?? '', supplier: x.supplier ?? '', amount: x.amount ?? 0, expenseDate: x.expenseDate ?? '' } : this.blank();
    this.form.set(true);
  }

  private run(obs: Observable<unknown>, msg: string): void {
    this.busy.run(obs, () => { this.toasts.success(msg); this.list.reload(); }, (err) => this.toasts.error(err));
  }

  save(): void {
    const req = this.editId ? this.api.put(`/finance/expenses/${this.editId}`, this.e) : this.api.post('/finance/expenses', this.e);
    this.run(req, 'Dépense enregistrée en brouillon.');
    this.form.set(false);
  }

  attach(x: ExpenseResponse, ev: Event): void {
    const file = (ev.target as HTMLInputElement).files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    const upload = this.api.upload<DocumentItem>('/documents', fd, { title: `Justificatif ${x.reference}`, category: 'JUSTIFICATIF_DEPENSE', visibility: 'BUREAU', relatedType: 'Expense', relatedId: x.id });
    this.run(upload.pipe(switchMap((d) => this.api.put(`/finance/expenses/${x.id}/justification/${d.id}`))), 'Justificatif joint.');
  }

  async submit(x: ExpenseResponse): Promise<void> {
    if ((await this.dialogs.ask({ title: 'Soumettre la dépense ?', message: 'Elle ne pourra plus être modifiée. Elle devra être approuvée par une personne différente de vous.' })) === null) return;
    this.run(this.api.post(`/finance/expenses/${x.id}/submit`), 'Dépense soumise à approbation.');
  }

  async decide(x: ExpenseResponse, decision: 'APPROUVEE' | 'REJETEE'): Promise<void> {
    const comment = await this.dialogs.ask({ title: decision === 'APPROUVEE' ? `Approuver ${x.reference}` : `Rejeter ${x.reference}`, message: 'Vérifiez le justificatif avant de décider. Vous ne pouvez pas approuver une dépense que vous avez saisie.', reasonLabel: decision === 'REJETEE' ? 'Motif du rejet' : undefined, danger: decision === 'REJETEE' });
    if (comment === null) return;
    this.run(this.api.post(`/finance/expenses/${x.id}/decision`, { decision, comment: comment || null }), 'Décision enregistrée.');
  }

  async pay(x: ExpenseResponse): Promise<void> {
    if ((await this.dialogs.ask({ title: `Enregistrer le paiement de ${x.reference}`, message: `Le paiement réel (${formatMoney(x.amount)}) doit avoir été effectué. L’écriture comptable sera passée sur le compte ${this.payAccount}. Vous ne pouvez pas payer une dépense que vous avez approuvée.` })) === null) return;
    this.run(this.api.post(`/finance/expenses/${x.id}/pay`, { paymentAccount: this.payAccount, paymentDate: new Date().toISOString().slice(0, 10) }), 'Paiement enregistré.');
  }

  async cancel(x: ExpenseResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Annuler ${x.reference}`, message: x.status === 'PAYEE' ? 'L’écriture de paiement sera contre-passée ; l’historique est conservé.' : undefined, reasonLabel: 'Motif', danger: true });
    if (reason === null) return;
    this.run(this.api.post(`/finance/expenses/${x.id}/cancel`, { reason }), 'Dépense annulée.');
  }

  doc(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (err) => this.toasts.error(err) });
  }

  private blank() {
    return { category: 'FOURNITURES', label: '', supplier: '', amount: 0, expenseDate: new Date().toISOString().slice(0, 10) };
  }
}
