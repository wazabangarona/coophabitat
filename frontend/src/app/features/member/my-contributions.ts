import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, newIdempotencyKey } from '../../core/api';
import { IntentResponse, ObligationResponse, ProviderInfo } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Busy, Loader } from '../../core/loader';
import { OfflineSnapshot } from '../../core/offline';
import { SIMULATION_NOTICE } from '../../core/public-info';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe } from '../../ui/pipes';

@Component({
  selector: 'app-my-contributions',
  imports: [FormsModule, PageHeader, StateView, Badge, MoneyPipe, DayPipe, LabelPipe],
  template: `
    <app-page-header title="Mes cotisations" subtitle="Échéances, pénalités et montants restant dus, calculés par le serveur." />

    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucune échéance n’a encore été générée pour vous." (retry)="list.reload()">
      <div class="table-wrap">
        <table>
          <thead><tr><th>Période</th><th>Nature</th><th>Échéance</th><th class="num">Dû</th><th class="num">Payé</th>
            <th class="num">Reste</th><th>Statut</th><th><span class="sr">Action</span></th></tr></thead>
          <tbody>
            @for (o of list.data(); track o.id) {
              <tr [class.late]="o.overdue">
                <td data-label="Période">{{ o.periodLabel }}</td>
                <td data-label="Nature">{{ o.kind | label }}@if (o.penaltySequence) { n° {{ o.penaltySequence }} }</td>
                <td data-label="Échéance">{{ o.dueDate | day }}@if (o.overdue) { <br /><small class="bad">{{ o.daysLate }} j de retard</small> }</td>
                <td data-label="Dû" class="num">{{ o.amountDue | money }}</td>
                <td data-label="Payé" class="num">{{ o.amountPaid | money }}</td>
                <td data-label="Reste" class="num"><strong>{{ o.outstanding | money }}</strong></td>
                <td data-label="Statut"><app-badge [value]="o.status" /></td>
                <td>
                  @if ((o.outstanding ?? 0) > 0 && o.status !== 'ANNULEE') {
                    <button type="button" class="btn small primary" (click)="select(o)" [disabled]="!offline.online()">Payer</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </app-state>

    @if (selected(); as o) {
      <section class="card" aria-labelledby="pay-title">
        <h2 id="pay-title">Payer « {{ o.periodLabel }} »</h2>
        <form (ngSubmit)="pay(o)" #f="ngForm">
          <label class="field"><span>Montant (francs CFA)</span>
            <input type="number" name="amount" [(ngModel)]="amount" required min="1" [max]="o.outstanding ?? null" step="1" inputmode="numeric" />
            <small class="muted">Reste dû : {{ o.outstanding | money }}. Un paiement partiel est possible.</small></label>
          <fieldset class="field">
            <legend>Moyen de paiement</legend>
            @for (p of providers.data(); track p.code) {
              <label class="check" [class.disabled]="!p.available">
                <input type="radio" name="provider" [value]="p.code" [checked]="provider === p.code" (change)="provider = p.code ?? ''" [disabled]="!p.available" />
                <span><strong>{{ p.code | label }}</strong>
                  @if (p.simulated) { <span class="badge" data-tone="sim">Simulation</span> }
                  <br /><small class="muted">{{ p.message }}</small></span>
              </label>
            }
            <p class="muted small">Pour un paiement en espèces ou par virement, remettez-le au trésorier : il sera enregistré
              puis vérifié par un second responsable avant d’être compté.</p>
          </fieldset>
          @if (provider === 'MOCK') { <p class="alert" data-tone="sim">{{ notice }}</p> }
          <div class="row">
            <button type="button" class="btn" (click)="selected.set(null)">Annuler</button>
            <button class="btn primary" [disabled]="f.invalid || !provider || busy.on()">Continuer</button>
          </div>
        </form>
      </section>
    }
  `,
})
export class MyContributions implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly auth = inject(Auth);
  protected readonly offline = inject(OfflineSnapshot);
  protected readonly list = new Loader<ObligationResponse[]>();
  protected readonly providers = new Loader<ProviderInfo[]>((ps) => {
    const first = ps.find((p) => p.available && !p.simulated) ?? ps.find((p) => p.available);
    this.provider = first?.code ?? '';
  });
  protected readonly selected = signal<ObligationResponse | null>(null);
  protected readonly busy = new Busy();
  protected readonly notice = SIMULATION_NOTICE;
  protected amount = 0;
  protected provider = '';
  /** Une clé par tentative : un double clic ou une nouvelle soumission après coupure ne crée pas deux paiements. */
  private key = newIdempotencyKey();
  protected readonly payable = computed(() => (this.list.data() ?? []).filter((o) => (o.outstanding ?? 0) > 0));

  ngOnInit(): void {
    this.list.load(() => this.api.get<ObligationResponse[]>('/me/obligations'));
    this.providers.load(() => this.api.get<ProviderInfo[]>('/payments/providers'));
    void this.auth;
  }

  select(o: ObligationResponse): void {
    this.selected.set(o);
    this.amount = o.outstanding ?? 0;
    this.key = newIdempotencyKey();
  }

  pay(o: ObligationResponse): void {
    this.busy.run(
      this.api.postOnce<IntentResponse>('/payments/intents', { amount: this.amount, obligationId: o.id, provider: this.provider }, this.key),
      (intent) => {
        if (intent.provider === 'MOCK') {
          void this.router.navigate(['/espace/paiements/simulation', intent.reference]);
        } else if (intent.launchUrl && /^https:\/\//.test(intent.launchUrl)) {
          // Paiement réel : redirection vers la page de l'opérateur ; le résultat sera confirmé par son webhook.
          window.location.assign(intent.launchUrl);
        } else {
          void this.router.navigate(['/espace/paiements/suivi', intent.reference]);
        }
      },
      (e) => {
        this.toasts.error(e);
        // Refus explicite du serveur : la prochaine tentative est une nouvelle demande.
        if ((e as { status?: number }).status) this.key = newIdempotencyKey();
      },
    );
  }
}
