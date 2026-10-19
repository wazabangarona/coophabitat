import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { MemberCampaignView, MemberFinancialSummary, ObligationResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Loader } from '../../core/loader';
import { OfflineSnapshot, Snapshot } from '../../core/offline';
import { Badge, Kpi, PageHeader, StateView } from '../../ui/components';
import { DayPipe, MoneyPipe, WhenPipe, formatMoney } from '../../ui/pipes';

interface HomeData {
  summary: MemberFinancialSummary;
  obligations: ObligationResponse[];
  campaigns: MemberCampaignView[];
}

@Component({
  selector: 'app-member-home',
  imports: [RouterLink, PageHeader, StateView, Kpi, Badge, MoneyPipe, DayPipe, WhenPipe],
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
        <div class="kpis">
          <app-kpi label="Reste à payer (échu)" [value]="money(s.overdue)" [tone]="(s.overdue ?? 0) > 0 ? 'bad' : 'ok'"
            [hint]="(s.overdueCount ?? 0) + ' échéance(s) en retard'" />
          <app-kpi label="Pénalités dues" [value]="money(s.penaltiesOutstanding)" [tone]="(s.penaltiesOutstanding ?? 0) > 0 ? 'warn' : 'neutral'" />
          <app-kpi label="Total versé" [value]="money(s.totalPaid)" hint="Paiements confirmés uniquement" />
          <app-kpi label="Avance disponible" [value]="money(s.credit)" hint="Versements non encore imputés" />
        </div>
        <p>
          @if (s.upToDate) { <span class="badge" data-tone="ok">À jour de vos cotisations</span> }
          @else { <span class="badge" data-tone="bad">En retard depuis le {{ s.oldestOverdueDueDate | day }}</span> }
          <span class="muted small"> — situation au {{ s.asOf | day }}, plan « {{ s.planName || 'aucun' }} »
            @if (s.planAmount) { ({{ s.planAmount | money }} par échéance) }</span>
        </p>

        <section class="card">
          <div class="row between"><h2>Prochaines échéances</h2><a routerLink="/espace/cotisations">Toutes mes cotisations</a></div>
          @if (!s.upcoming?.length) { <p class="muted">Aucune échéance à venir.</p> }
          <ul class="plain">
            @for (o of s.upcoming; track o.id) {
              <li class="row between">
                <span><strong>{{ o.periodLabel }}</strong> — échéance le {{ o.dueDate | day }}</span>
                <span>{{ o.outstanding | money }} <app-badge [value]="o.status" /></span>
              </li>
            }
          </ul>
          @if (!stale() && (s.outstanding ?? 0) > 0) {
            <a class="btn primary" routerLink="/espace/cotisations">Payer une échéance</a>
          }
        </section>
      }

      @if (!stale()) {
        <section class="card">
          <div class="row between"><h2>Attributions en cours</h2><a routerLink="/espace/attributions">Voir le détail</a></div>
          @for (c of openCampaigns(); track c.id) {
            <p><strong>{{ c.title }}</strong> — <app-badge [value]="c.status" />
              @if (c.applied) { <span class="badge" data-tone="info">Candidature déposée</span> }</p>
          } @empty { <p class="muted">Aucune procédure en cours.</p> }
        </section>
      }
    </app-state>
  `,
})
export class MemberHome implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
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
  }
}
