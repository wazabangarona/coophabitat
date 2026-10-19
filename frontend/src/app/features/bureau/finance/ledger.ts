import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, saveBlob } from '../../../core/api';
import { AccountBalance, EntryResponse, FinancialAccount, PageResponseEntryResponse } from '../../../core/api-types';
import { Auth } from '../../../core/auth';
import { Dialogs } from '../../../core/dialog';
import { Busy, Loader } from '../../../core/loader';
import { Toasts } from '../../../core/toast';
import { PageHeader, Pager, SimBadge, StateView } from '../../../ui/components';
import { DayPipe, MoneyPipe } from '../../../ui/pipes';

const yearStart = () => `${new Date().getFullYear()}-01-01`;
const today = () => new Date().toISOString().slice(0, 10);

@Component({
  selector: 'app-ledger',
  imports: [FormsModule, PageHeader, StateView, Pager, SimBadge, MoneyPipe, DayPipe],
  template: `
    <app-page-header title="Écritures et balance" subtitle="Journal en partie double. Aucune écriture n’est modifiable : une erreur se corrige par contre-passation.">
      <button type="button" class="btn small" (click)="export()">Exporter (Excel)</button>
      @if (auth.has('ACCOUNTING_WRITE')) { <button type="button" class="btn small primary" (click)="form.set(!form())">Écriture manuelle</button> }
    </app-page-header>
    <div class="filters">
      <label class="field inline"><span>Du</span><input type="date" [(ngModel)]="from" /></label>
      <label class="field inline"><span>Au</span><input type="date" [(ngModel)]="to" /></label>
      <label class="check"><input type="checkbox" [(ngModel)]="withSim" /> Inclure les écritures simulées</label>
      <button type="button" class="btn" (click)="refresh()">Afficher</button>
    </div>

    @if (form()) {
      <section class="card"><h2>Écriture manuelle</h2>
        <p class="small muted">Réservée aux opérations sans pièce automatique (frais bancaires, intérêts…). Le compte de simulation est interdit.</p>
        <form (ngSubmit)="saveEntry()" #f="ngForm">
          <div class="grid-2">
            <label class="field"><span>Date</span><input type="date" name="d" [(ngModel)]="m.entryDate" required /></label>
            <label class="field"><span>Libellé</span><input name="l" [(ngModel)]="m.label" required /></label>
            <label class="field span-2"><span>Justification (pièce, relevé…)</span><input name="j" [(ngModel)]="m.justification" required minlength="5" /></label>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Compte</th><th class="num">Débit</th><th class="num">Crédit</th><th></th></tr></thead>
            <tbody>@for (l of m.lines; track $index; let i = $index) {
              <tr><td data-label="Compte"><select [(ngModel)]="l.accountCode" [name]="'a' + i" required>@for (a of accounts(); track a.code) { <option [value]="a.code">{{ a.code }} — {{ a.label }}</option> }</select></td>
                <td data-label="Débit" class="num"><input type="number" [(ngModel)]="l.debit" [name]="'d' + i" min="0" step="1" /></td>
                <td data-label="Crédit" class="num"><input type="number" [(ngModel)]="l.credit" [name]="'c' + i" min="0" step="1" /></td>
                <td><button type="button" class="btn small" (click)="m.lines.splice(i, 1)">Retirer</button></td></tr>
            }</tbody>
          </table></div>
          <p [class.bad]="!balanced()">Total débit {{ totals().debit | money }} — total crédit {{ totals().credit | money }} {{ balanced() ? '(équilibrée)' : '(non équilibrée)' }}</p>
          <div class="row"><button type="button" class="btn small" (click)="m.lines.push({ accountCode: '', debit: 0, credit: 0 })">Ajouter une ligne</button>
            <span class="grow"></span><button class="btn primary" [disabled]="f.invalid || !balanced() || busy.on()">Passer l’écriture</button></div>
        </form>
      </section>
    }

    <section class="card"><h2>Balance des comptes</h2>
      <app-state [loading]="balance.loading()" [error]="balance.error()" [empty]="!balance.data()?.length" emptyText="Aucun mouvement sur la période." (retry)="balance.reload()">
        <div class="table-wrap"><table>
          <thead><tr><th>Compte</th><th class="num">Débit</th><th class="num">Crédit</th><th class="num">Solde</th></tr></thead>
          <tbody>@for (b of balance.data(); track b.code) {
            <tr><td data-label="Compte">{{ b.code }} — {{ b.label }}</td><td data-label="Débit" class="num">{{ b.debit | money }}</td>
              <td data-label="Crédit" class="num">{{ b.credit | money }}</td><td data-label="Solde" class="num">{{ b.balance | money }}</td></tr>
          }</tbody>
        </table></div>
      </app-state>
    </section>

    <section class="card"><h2>Journal</h2>
      <app-state [loading]="entries.loading()" [error]="entries.error()" [empty]="!entries.data()?.content?.length" emptyText="Aucune écriture." (retry)="entries.reload()">
        @for (e of entries.data()?.content; track e.id) {
          <details class="entry">
            <summary>{{ e.entryDate | day }} — {{ e.reference }} — {{ e.label }} @if (e.simulated) { <app-sim-badge /> }
              @if (e.reversalOfId) { <span class="badge" data-tone="warn">Contre-passation</span> } @if (e.reversedById) { <span class="badge" data-tone="neutral">Contre-passée</span> }</summary>
            <table><tbody>@for (l of e.lines; track $index) {
              <tr><td data-label="Compte">{{ l.accountCode }} — {{ l.accountLabel }}</td><td data-label="Débit" class="num">{{ l.debit | money }}</td><td data-label="Crédit" class="num">{{ l.credit | money }}</td></tr>
            }</tbody></table>
            <p class="small muted">Origine : {{ e.sourceType }} n° {{ e.sourceId }}</p>
            @if (e.sourceType === 'MANUELLE' && !e.reversedById && !e.reversalOfId && auth.has('ACCOUNTING_WRITE')) {
              <button type="button" class="btn small" (click)="reverse(e)">Contre-passer</button>
            }
          </details>
        }
        <app-pager [page]="page()" [totalPages]="entries.data()?.totalPages ?? 0" [total]="entries.data()?.totalElements ?? 0" (go)="go($event)" />
      </app-state>
    </section>
  `,
})
export class Ledger implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly entries = new Loader<PageResponseEntryResponse>();
  protected readonly balance = new Loader<AccountBalance[]>();
  protected readonly accounts = signal<FinancialAccount[]>([]);
  protected readonly form = signal(false);
  protected readonly page = signal(0);
  protected readonly busy = new Busy();
  protected from = yearStart();
  protected to = today();
  protected withSim = false;
  protected m = { entryDate: today(), label: '', justification: '', lines: [{ accountCode: '', debit: 0, credit: 0 }, { accountCode: '', debit: 0, credit: 0 }] };
  protected totals(): { debit: number; credit: number } {
    return this.m.lines.reduce((t, l) => ({ debit: t.debit + (Number(l.debit) || 0), credit: t.credit + (Number(l.credit) || 0) }), { debit: 0, credit: 0 });
  }

  ngOnInit(): void {
    this.api.get<FinancialAccount[]>('/finance/accounts').subscribe((a) => this.accounts.set(a.filter((x) => x.code !== '559900' && x.active !== false)));
    this.refresh();
  }

  /** Contrôle d'équilibre indicatif : le serveur refuse de toute façon une écriture déséquilibrée. */
  balanced(): boolean {
    const t = this.totals();
    return t.debit > 0 && t.debit === t.credit;
  }

  refresh(): void {
    this.go(0);
    this.balance.load(() => this.api.get<AccountBalance[]>('/finance/trial-balance', { from: this.from, to: this.to, simulated: this.withSim }));
  }

  go(p: number): void {
    this.page.set(p);
    this.entries.load(() => this.api.get<PageResponseEntryResponse>('/finance/entries', { from: this.from, to: this.to, simulated: this.withSim ? null : false, page: p, size: 30 }));
  }

  saveEntry(): void {
    this.busy.run(this.api.post('/finance/entries', this.m), () => {
      this.toasts.success('Écriture passée.');
      this.form.set(false);
      this.m = { entryDate: today(), label: '', justification: '', lines: [{ accountCode: '', debit: 0, credit: 0 }, { accountCode: '', debit: 0, credit: 0 }] };
      this.refresh();
    }, (e) => this.toasts.error(e));
  }

  async reverse(e: EntryResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Contre-passer ${e.reference}`, message: 'Une écriture inverse sera passée ; l’écriture d’origine reste visible.', reasonLabel: 'Motif', danger: true });
    if (reason === null) return;
    this.busy.run(this.api.post(`/finance/entries/${e.id}/reverse`, { reason }), () => { this.toasts.success('Écriture contre-passée.'); this.refresh(); }, (err) => this.toasts.error(err));
  }

  export(): void {
    this.api.download('/finance/export', { from: this.from, to: this.to }).subscribe({
      next: (r) => saveBlob(r.blob, r.filename === 'document' ? `comptabilite-${this.from}-${this.to}.xlsx` : r.filename),
      error: (e) => this.toasts.error(e),
    });
  }
}
