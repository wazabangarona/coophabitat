import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api, saveBlob } from '../../core/api';
import { MemberCampaignView, MemberFinancialSummary, ObligationResponse, PageResponseTransactionResponse, TransactionResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Loader } from '../../core/loader';
import { OfflineSnapshot, Snapshot } from '../../core/offline';
import { Toasts } from '../../core/toast';
import { Amount, Badge, Icon, Kpi, PageHeader, SimBadge, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe, formatMoney } from '../../ui/pipes';

interface HomeData {
  summary: MemberFinancialSummary;
  obligations: ObligationResponse[];
  campaigns: MemberCampaignView[];
}

@Component({
  selector: 'app-member-home',
  imports: [RouterLink, PageHeader, StateView, Kpi, Badge, SimBadge, Amount, Icon, MoneyPipe, DayPipe, WhenPipe, LabelPipe],
  template: `
    <app-page-header [title]="'Bonjour ' + (auth.user()?.displayName ?? '')" subtitle="Votre situation auprès de la coopérative." />

    @if (stale(); as s) {
      <div class="alert" data-tone="offline" role="status">
        <strong>Copie locale du {{ s.savedAt | when }}.</strong> Vous êtes hors ligne ou le serveur est injoignable :
        ces chiffres peuvent avoir changé depuis et ne constituent pas une situation financière à jour.
      </div>
    }

    <app-state [loading]="data.loading() && !stale()" [error]="stale() ? null : data.error()" (retry)="load()">
      @if (summary(); as s) {
        <section class="situation" aria-labelledby="situation-title">
          <div class="situation-main">
            <h2 id="situation-title" class="sr">Situation de cotisation</h2>
            <p class="situation-status">
              @if (s.upToDate) { <span class="badge" data-tone="ok">À jour de vos cotisations</span> }
              @else { <span class="badge" data-tone="bad">En retard depuis le {{ s.oldestOverdueDueDate | day }}</span> }
            </p>
            <p class="situation-label">Reste à payer (échu)</p>
            <p class="situation-amount" [class.bad]="(s.overdue ?? 0) > 0"><app-amount [value]="s.overdue" /></p>
            <p class="situation-meta">{{ s.overdueCount ?? 0 }} échéance(s) en retard · situation au {{ s.asOf | day }}<br />
              Plan « {{ s.planName || 'aucun' }} »@if (s.planAmount) { · {{ s.planAmount | money }} par échéance }</p>
            @if (!stale() && (s.outstanding ?? 0) > 0) {
              <div class="row">
                <a class="btn primary" routerLink="/espace/cotisations"><app-icon name="credit-card" />Payer une échéance</a>
                <a class="btn ghost" routerLink="/espace/paiements">Mes reçus</a>
              </div>
            }
          </div>
          <div class="situation-next">
            <p class="eyebrow">Prochaine échéance</p>
            @if (s.upcoming?.[0]; as o) {
              <p class="next-period">{{ o.periodLabel }}</p>
              <p class="next-amount"><app-amount [value]="o.outstanding" /></p>
              <p class="muted small">À régler le {{ o.dueDate | day }} <app-badge [value]="o.status" /></p>
            } @else {
              <p class="muted small">Aucune échéance à venir.</p>
            }
          </div>
        </section>

        <div class="kpis">
          <app-kpi label="Total versé" [value]="money(s.totalPaid)" hint="Paiements confirmés uniquement" />
          <app-kpi label="Pénalités dues" [value]="money(s.penaltiesOutstanding)" [tone]="(s.penaltiesOutstanding ?? 0) > 0 ? 'warn' : 'neutral'" />
          <app-kpi label="Avance disponible" [value]="money(s.credit)" hint="Versements non encore imputés" />
          <app-kpi label="Reste dû au total" [value]="money(s.outstanding)" hint="Échu et à venir" />
        </div>

        <div class="split">
          <section class="card">
            <div class="card-head"><h2>Prochaines échéances</h2><a routerLink="/espace/cotisations">Toutes mes cotisations<app-icon name="arrow-right" /></a></div>
            <ul class="list-rows">
              @for (o of s.upcoming; track o.id) {
                <li>
                  <span><strong>{{ o.periodLabel }}</strong><br /><small class="muted">Échéance le {{ o.dueDate | day }}</small></span>
                  <span class="row"><strong class="num">{{ o.outstanding | money }}</strong> <app-badge [value]="o.status" /></span>
                </li>
              } @empty { <li><span class="muted">Aucune échéance à venir.</span></li> }
            </ul>
          </section>

          @if (!stale()) {
            <section class="card">
              <div class="card-head"><h2>Derniers paiements</h2><a routerLink="/espace/paiements">Paiements et reçus<app-icon name="arrow-right" /></a></div>
              @if (payments.loading()) { <p class="loading-label"><span class="spinner" aria-hidden="true"></span> Chargement…</p> }
              @else if (payments.error()) { <p class="muted small">Les derniers paiements ne peuvent pas être affichés pour le moment.</p> }
              @else {
                <ul class="list-rows">
                  @for (t of payments.data()?.content; track t.id) {
                    <li>
                      <span><strong class="num">{{ t.amount | money }}</strong> @if (t.simulated) { <app-sim-badge /> }<br />
                        <small class="muted">{{ t.confirmedAt | when }} · {{ t.provider | label }}</small></span>
                      @if (t.receiptId) {
                        <button type="button" class="btn small" (click)="receipt(t)"><app-icon name="download" />Reçu</button>
                      } @else { <app-badge [value]="t.status" /> }
                    </li>
                  } @empty { <li><span class="muted">Aucun paiement confirmé pour le moment.</span></li> }
                </ul>
              }
            </section>
          }
        </div>
      }

      @if (!stale()) {
        <div class="split even">
          <section class="card">
            <div class="card-head"><h2>Attributions en cours</h2><a routerLink="/espace/attributions">Voir le détail<app-icon name="arrow-right" /></a></div>
            <ul class="list-rows">
              @for (c of openCampaigns(); track c.id) {
                <li><span><strong>{{ c.title }}</strong>
                  @if (c.applied) { <br /><small class="ok">Candidature déposée</small> }</span><app-badge [value]="c.status" /></li>
              } @empty { <li><span class="muted">Aucune procédure en cours.</span></li> }
            </ul>
          </section>
          <section class="card">
            <div class="card-head"><h2>Accès utiles</h2></div>
            <ul class="list-rows shortcuts">
              <li><a routerLink="/espace/documents"><app-icon name="file-text" />Documents officiels</a><app-icon name="chevron-right" /></li>
              <li><a routerLink="/espace/transparence"><app-icon name="scale" />Comptes de la coopérative</a><app-icon name="chevron-right" /></li>
              <li><a routerLink="/espace/assemblees"><app-icon name="vote" />Assemblées générales</a><app-icon name="chevron-right" /></li>
            </ul>
          </section>
        </div>
      }
    </app-state>
  `,
})
export class MemberHome implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly offline = inject(OfflineSnapshot);
  protected readonly stale = signal<Snapshot | null>(null);
  protected readonly data = new Loader<HomeData>(
    (d) => {
      this.stale.set(null);
      this.offline.save(this.auth.user()!.id!, d.summary, d.obligations);
    },
    // En cas d'échec, on affiche la copie locale si le membre l'a autorisée — toujours signalée comme ancienne.
    () => this.stale.set(this.offline.load(this.auth.user()!.id!)),
  );
  /** Chargés séparément : leur indisponibilité ne doit pas masquer la situation de cotisation. */
  protected readonly payments = new Loader<PageResponseTransactionResponse>();
  protected readonly summary = computed(() => this.data.data()?.summary ?? this.stale()?.summary ?? null);
  protected readonly openCampaigns = computed(() =>
    (this.data.data()?.campaigns ?? []).filter((c) => !['ARCHIVEE', 'ANNULEE', 'BROUILLON'].includes(c.status ?? '')),
  );
  protected readonly money = formatMoney;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.data.load(() =>
      forkJoin({
        summary: this.api.get<MemberFinancialSummary>('/me/summary'),
        obligations: this.api.get<ObligationResponse[]>('/me/obligations'),
        campaigns: this.api.get<MemberCampaignView[]>('/allocations/me'),
      }),
    );
    const memberId = this.auth.user()?.memberId;
    if (memberId) {
      this.payments.load(() =>
        this.api.get<PageResponseTransactionResponse>(`/payments/members/${memberId}/transactions`, { page: 0, size: 4 }),
      );
    }
  }

  receipt(t: TransactionResponse): void {
    this.api.download(`/receipts/${t.receiptId}/pdf`).subscribe({
      next: (r) => saveBlob(r.blob, `${t.receiptNumber}.pdf`),
      error: (e) => this.toasts.error(e),
    });
  }
}
