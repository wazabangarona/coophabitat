import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Api } from '../../../core/api';
import { DeclarationResponse, EntryResponse, ImportResult, PageResponseEntryResponse, ReconciliationAccount, StatementLineResponse } from '../../../core/api-types';
import { Auth } from '../../../core/auth';
import { Dialogs } from '../../../core/dialog';
import { Busy, Loader } from '../../../core/loader';
import { Toasts } from '../../../core/toast';
import { Badge, PageHeader, StateView } from '../../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe } from '../../../ui/pipes';

@Component({
  selector: 'app-bank',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, MoneyPipe, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Rapprochement bancaire" subtitle="Comparaison entre la comptabilité et les soldes réellement constatés (relevés, déclarations)." />
    <p class="alert" data-tone="info">L’application ne se connecte à aucune banque : les soldes « déclarés » sont saisis ou importés depuis un relevé par le trésorier.</p>
    <app-state [loading]="rec.loading()" [error]="rec.error()" (retry)="rec.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Compte</th><th class="num">Comptable</th><th class="num">Déclaré</th><th>Au</th><th class="num">Écart</th><th class="num">Lignes non rapprochées</th><th>État</th></tr></thead>
        <tbody>@for (r of rec.data(); track r.accountCode) {
          <tr [class.late]="r.status === 'ECART'"><td data-label="Compte"><button type="button" class="link" (click)="select(r.accountCode!)">{{ r.accountCode }} — {{ r.label }}</button></td>
            <td data-label="Comptable" class="num">{{ r.accountingBalance | money }}</td><td data-label="Déclaré" class="num">{{ r.declaredBalance | money }}</td>
            <td data-label="Au">{{ r.declaredAt | day }} @if (r.declaredSource) { <small class="muted">({{ r.declaredSource | label }})</small> }</td>
            <td data-label="Écart" class="num">{{ r.gap | money }}</td><td data-label="Non rapprochées" class="num">{{ r.unmatchedStatementLines }}</td>
            <td data-label="État"><app-badge [value]="r.status" /></td></tr>
        }</tbody>
      </table></div>
    </app-state>

    @if (account(); as acc) {
      <section class="card"><h2>Compte {{ acc }}</h2>
        @if (auth.has('BANK_RECONCILE')) {
          <div class="grid-2">
            <form (ngSubmit)="declare(acc)" #df="ngForm">
              <h3>Déclarer un solde constaté</h3>
              <label class="field"><span>Date du solde</span><input type="date" name="d" [(ngModel)]="decl.balanceDate" required /></label>
              <label class="field"><span>Solde (XOF)</span><input type="number" name="b" [(ngModel)]="decl.balance" required step="1" /></label>
              <label class="field"><span>Référence du relevé</span><input name="r" [(ngModel)]="decl.statementReference" /></label>
              <button class="btn" [disabled]="df.invalid || busy.on()">Enregistrer</button>
            </form>
            <div>
              <h3>Importer un relevé (CSV)</h3>
              <p class="small muted">Format : <code>date;libellé;montant;référence</code> (date AAAA-MM-JJ, montant signé en XOF). Les lignes déjà importées sont détectées et ignorées.</p>
              <input type="file" accept=".csv,text/csv" (change)="importCsv(acc, $event)" aria-label="Fichier de relevé CSV" />
              @if (imported(); as i) { <p class="alert" [attr.data-tone]="i.errors?.length ? 'warn' : 'ok'">{{ i.imported }} ligne(s) importée(s), {{ i.duplicates }} doublon(s).
                @for (er of i.errors; track $index) { <br />{{ er }} }</p> }
            </div>
          </div>
        }
        <h3>Lignes du relevé</h3>
        <app-state [loading]="lines.loading()" [error]="lines.error()" [empty]="!lines.data()?.length" emptyText="Aucune ligne importée." (retry)="lines.reload()">
          <div class="table-wrap"><table>
            <thead><tr><th>Date</th><th>Libellé</th><th class="num">Montant</th><th>Rapprochement</th><th></th></tr></thead>
            <tbody>@for (l of lines.data(); track l.id) {
              <tr><td data-label="Date">{{ l.operationDate | day }}</td><td data-label="Libellé">{{ l.label }}<br /><small class="muted">{{ l.bankReference }}</small></td>
                <td data-label="Montant" class="num">{{ l.amount | money }}</td>
                <td data-label="Rapprochement">@if (l.matchedEntryId) { {{ l.matchedEntryReference }} <small class="muted">({{ l.matchedAt | when }})</small> } @else { <span class="badge" data-tone="warn">Non rapprochée</span> }</td>
                <td>@if (auth.has('BANK_RECONCILE')) {
                  @if (l.matchedEntryId) { <button type="button" class="btn small" (click)="unmatch(l)">Défaire</button> }
                  @else { <select [(ngModel)]="matchChoice[l.id!]" aria-label="Écriture à rapprocher"><option [ngValue]="undefined">Écriture…</option>
                      @for (e of candidates(l); track e.id) { <option [ngValue]="e.id">{{ e.entryDate }} {{ e.reference }} — {{ e.label }}</option> }</select>
                    <button type="button" class="btn small" [disabled]="!matchChoice[l.id!]" (click)="match(l)">Rapprocher</button> } }</td></tr>
            }</tbody>
          </table></div>
        </app-state>
        <h3>Soldes déclarés</h3>
        <ul class="plain small">@for (d of declarations.data(); track d.id) { <li>{{ d.balanceDate | day }} : {{ d.balance | money }} ({{ d.source | label }}{{ d.statementReference ? ', ' + d.statementReference : '' }})</li> }
          @empty { <li class="muted">Aucun.</li> }</ul>
      </section>
    }
  `,
})
export class Bank implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly rec = new Loader<ReconciliationAccount[]>();
  protected readonly lines = new Loader<StatementLineResponse[]>();
  protected readonly declarations = new Loader<DeclarationResponse[]>();
  protected readonly account = signal<string | null>(null);
  protected readonly imported = signal<ImportResult | null>(null);
  protected readonly entries = signal<EntryResponse[]>([]);
  protected readonly busy = new Busy();
  protected matchChoice: Record<number, number | undefined> = {};
  protected decl = { balanceDate: new Date().toISOString().slice(0, 10), balance: 0, statementReference: '' };

  ngOnInit(): void {
    this.rec.load(() => this.api.get<ReconciliationAccount[]>('/finance/bank/reconciliation'));
  }

  select(code: string): void {
    this.account.set(code);
    this.imported.set(null);
    this.loadAccount(code);
    this.api.get<PageResponseEntryResponse>('/finance/entries', { simulated: false, size: 100 }).subscribe((p) => this.entries.set(p.content ?? []));
  }

  private loadAccount(code: string): void {
    this.lines.load(() => this.api.get<StatementLineResponse[]>(`/finance/bank/${code}/statement`));
    this.declarations.load(() => this.api.get<DeclarationResponse[]>(`/finance/bank/${code}/declarations`));
  }

  /** Écritures touchant ce compte pour le même montant net (le serveur revérifie la correspondance). */
  candidates(l: StatementLineResponse): EntryResponse[] {
    return this.entries().filter((e) => (e.lines ?? []).some((x) => x.accountCode === l.accountCode && (x.debit ?? 0) - (x.credit ?? 0) === l.amount));
  }

  private run(obs: Observable<unknown>, msg: string): void {
    const code = this.account();
    this.busy.run(obs, () => { this.toasts.success(msg); this.rec.reload(); if (code) this.loadAccount(code); }, (e) => this.toasts.error(e));
  }

  declare(code: string): void {
    this.run(this.api.post('/finance/bank/declarations', { ...this.decl, accountCode: code }), 'Solde déclaré.');
  }

  importCsv(code: string, ev: Event): void {
    const f = (ev.target as HTMLInputElement).files?.[0];
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f);
    this.busy.run(this.api.upload<ImportResult>(`/finance/bank/${code}/statement`, fd), (r) => { this.imported.set(r); this.rec.reload(); this.loadAccount(code); }, (e) => this.toasts.error(e));
  }

  match(l: StatementLineResponse): void {
    this.run(this.api.post(`/finance/bank/statement-lines/${l.id}/match`, { entryId: this.matchChoice[l.id!] }), 'Ligne rapprochée.');
  }

  async unmatch(l: StatementLineResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: 'Défaire le rapprochement', reasonLabel: 'Motif' });
    if (reason !== null) this.run(this.api.post(`/finance/bank/statement-lines/${l.id}/unmatch`, { reason }), 'Rapprochement défait.');
  }
}
