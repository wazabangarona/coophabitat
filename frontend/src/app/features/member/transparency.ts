import { Component, OnInit, inject } from '@angular/core';
import { Api, openBlob } from '../../core/api';
import { ReportResponse, TransparencySummary } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, Kpi, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe, formatMoney } from '../../ui/pipes';

/** Synthèse financière consultable par les membres et rapports publiés. */
@Component({
  selector: 'app-transparency',
  imports: [PageHeader, StateView, Kpi, Badge, MoneyPipe, DayPipe, WhenPipe, LabelPipe],
  template: `
    <app-page-header title="Transparence financière"
      subtitle="Chiffres issus de la comptabilité de la coopérative, avec la définition de chacun." />
    <app-state [loading]="summary.loading()" [error]="summary.error()" (retry)="summary.reload()">
      @if (summary.data(); as s) {
        <p class="alert" [attr.data-tone]="s.escrowIntegrated ? 'ok' : 'info'">{{ s.escrowStatement }}</p>
        <div class="kpis">
          <app-kpi label="Trésorerie comptable" [value]="money(s.accountingTreasuryTotal)" hint="Banque, caisse et monnaie électronique réelles" />
          <app-kpi label="Soldes déclarés" [value]="money(s.declaredTreasuryTotal)" hint="Derniers relevés saisis par le trésorier" />
          <app-kpi label="Paiements confirmés" [value]="money(s.confirmedPaymentsTotal)" [hint]="(s.confirmedPaymentsCount ?? 0) + ' paiement(s)'" />
          <app-kpi label="Dépenses payées" [value]="money(s.expensesPaidTotal)" />
          <app-kpi label="Dépenses engagées" [value]="money(s.expensesCommittedTotal)" hint="Approuvées, non encore payées" />
          <app-kpi label="Fonds des membres détenus" [value]="money(s.memberFundsHeld)" />
        </div>
        @if ((s.simulatedPaymentsCount ?? 0) > 0) {
          <p class="alert" data-tone="sim">Paiements simulés exclus des totaux : {{ s.simulatedPaymentsTotal | money }}
            ({{ s.simulatedPaymentsCount }}). Aucun argent réel n’a été transféré.</p>
        }
        <section class="card">
          <h2>Comptes de trésorerie</h2>
          <div class="table-wrap"><table>
            <thead><tr><th>Compte</th><th class="num">Solde comptable</th><th class="num">Solde déclaré</th><th>Au</th><th class="num">Écart</th><th>État</th></tr></thead>
            <tbody>@for (t of s.treasury; track t.accountCode) {
              <tr><td data-label="Compte">{{ t.label }}</td><td data-label="Comptable" class="num">{{ t.accountingBalance | money }}</td>
                <td data-label="Déclaré" class="num">{{ t.declaredBalance | money }}</td><td data-label="Au">{{ t.declaredAt | day }}</td>
                <td data-label="Écart" class="num">{{ t.gap | money }}</td><td data-label="État"><app-badge [value]="t.status" /></td></tr>
            }</tbody>
          </table></div>
          <p class="muted small">Situation au {{ s.asOf | day }}. Dernière écriture : {{ s.lastEntryAt | when }}.</p>
        </section>
        <details class="card"><summary>Définitions des indicateurs</summary>
          <dl class="dl">@for (d of defs(s); track d[0]) { <dt>{{ d[0] }}</dt><dd>{{ d[1] }}</dd> }</dl>
        </details>
      }
    </app-state>

    <section class="card">
      <h2>Rapports financiers publiés</h2>
      <app-state [loading]="reports.loading()" [error]="reports.error()" [empty]="!reports.data()?.length"
        emptyText="Aucun rapport publié." (retry)="reports.reload()">
        <ul class="plain">@for (r of reports.data(); track r.id) {
          <li class="row between"><span><strong>{{ r.reference }}</strong> — {{ r.type | label }} du {{ r.periodStart | day }} au {{ r.periodEnd | day }}
            <small class="muted">(publié le {{ r.publishedAt | when }})</small></span>
            @if (r.documentId) { <button type="button" class="btn small" (click)="open(r.documentId)">PDF</button> }</li>
        }</ul>
      </app-state>
    </section>
  `,
})
export class Transparency implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly auth = inject(Auth);
  protected readonly summary = new Loader<TransparencySummary>();
  protected readonly reports = new Loader<ReportResponse[]>();
  protected readonly money = formatMoney;

  ngOnInit(): void {
    this.summary.load(() => this.api.get<TransparencySummary>('/finance/summary'));
    this.reports.load(() => this.api.get<ReportResponse[]>('/finance/reports'));
  }

  defs(s: TransparencySummary): [string, string][] {
    return Object.entries((s.definitions ?? {}) as Record<string, string>);
  }

  open(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
