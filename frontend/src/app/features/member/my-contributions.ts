import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, newIdempotencyKey } from '../../core/api';
import { IntentResponse, ObligationResponse, PaymentInstructions, PlanResponse, ProviderInfo } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Busy, Loader } from '../../core/loader';
import { OfflineSnapshot } from '../../core/offline';
import { SIMULATION_NOTICE } from '../../core/public-info';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe } from '../../ui/pipes';

@Component({
  selector: 'app-my-contributions',
  imports: [FormsModule, LowerCasePipe, PageHeader, StateView, Badge, MoneyPipe, DayPipe, LabelPipe],
  template: `
    <app-page-header title="Mes cotisations" subtitle="Échéances, pénalités et montants restant dus, calculés par le serveur." />

    @if (plan.data(); as p) {
      <details class="card">
        <summary>Règles de calcul de mon plan « {{ p.name }} »</summary>
        <dl class="dl">
          <dt>Montant</dt><dd>{{ p.amount | money }} par échéance ({{ p.periodicity | label | lowercase }})</dd>
          <dt>Échéance</dt><dd>Le {{ p.dueDayOfMonth }} du mois</dd>
          <dt>Délai de grâce</dt><dd>{{ p.graceDays }} jour(s) après l’échéance</dd>
          <dt>Pénalité de retard</dt>
          <dd>
            @switch (p.penaltyMethod) {
              @case ('FORFAIT') { {{ p.penaltyValue | money }}, appliquée une seule fois le lendemain du délai de grâce si l’échéance n’est pas soldée. }
              @case ('POURCENTAGE_MENSUEL') { {{ p.penaltyValue }} % du principal restant dû, appliqué le lendemain du délai de grâce puis chaque mois tant que l’échéance n’est pas soldée. }
              @default { Aucune pénalité. }
            }
            @if (p.penaltyCap && p.penaltyMethod !== 'AUCUNE') { <br />Plafond : {{ p.penaltyCap | money }} de pénalités par échéance. }
          </dd>
          @if (p.adoptedReference) { <dt>Adopté par</dt><dd>{{ p.adoptedReference }}</dd> }
        </dl>
      </details>
    }

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
        @if (online().length && mode() === 'online') {
          <form (ngSubmit)="pay(o)" #f="ngForm">
            <label class="field"><span>Montant (francs CFA)</span>
              <input type="number" name="amount" [(ngModel)]="amount" required min="1" [max]="o.outstanding ?? null" step="1" inputmode="numeric" />
              <small class="muted">Reste dû : {{ o.outstanding | money }}. Un paiement partiel est possible.</small></label>
            <fieldset class="field">
              <legend>Moyen de paiement</legend>
              @for (p of online(); track p.code) {
                <label class="check">
                  <input type="radio" name="provider" [value]="p.code" [checked]="provider === p.code" (change)="provider = p.code ?? ''" />
                  <span><strong>{{ p.code | label }}</strong>
                    @if (p.simulated) { <span class="badge" data-tone="sim">Simulation</span> }
                    <br /><small class="muted">{{ p.message }}</small></span>
                </label>
              }
            </fieldset>
            @if (provider === 'MOCK') { <p class="alert" data-tone="sim">{{ notice }}</p> }
            <div class="row">
              <button type="button" class="btn" (click)="selected.set(null)">Annuler</button>
              <button type="button" class="btn" (click)="mode.set('declare')">J’ai déjà payé autrement</button>
              <button class="btn primary" [disabled]="f.invalid || !provider || busy.on()">Continuer</button>
            </div>
          </form>
        } @else {
          <div class="alert" data-tone="info">
            <strong>Comment payer</strong>
            @if (instructions.data()?.instructions; as text) { <p class="pre">{{ text }}</p> }
            @else { <p>Le bureau n’a pas encore indiqué le numéro ou le compte de la coopérative : renseignez-vous auprès du trésorier.</p> }
            <small>Après avoir payé, déclarez votre paiement ci-dessous avec la référence reçue (SMS Wave ou Orange Money, avis de virement).
              Il sera compté dès que le bureau l’aura retrouvé sur le compte de la coopérative. En espèces, remettez l’argent au trésorier contre reçu.</small>
          </div>
          <form (ngSubmit)="declare(o)" #d="ngForm" class="grid-2">
            <label class="field"><span>Montant payé (francs CFA)</span>
              <input type="number" name="amount" [(ngModel)]="amount" required min="1" step="1" inputmode="numeric" />
              <small class="muted">Reste dû : {{ o.outstanding | money }}.</small></label>
            <label class="field"><span>Moyen utilisé</span>
              <select name="method" [(ngModel)]="decl.method" required>
                @for (m of methods(); track m) { <option [value]="m">{{ m | label }}</option> }
              </select></label>
            <label class="field"><span>Référence de la transaction</span>
              <input name="ref" [(ngModel)]="decl.reference" required minlength="4" maxlength="200" autocomplete="off" />
              <small class="muted">Identifiant figurant sur le SMS de confirmation ou l’avis de virement.</small></label>
            <label class="field"><span>Date du paiement</span>
              <input type="date" name="paidOn" [(ngModel)]="decl.paidOn" required [max]="today" /></label>
            <label class="field span-2"><span>Commentaire (facultatif)</span>
              <input name="comment" [(ngModel)]="decl.comment" maxlength="1000" /></label>
            <div class="row span-2">
              <button type="button" class="btn" (click)="selected.set(null)">Annuler</button>
              @if (online().length) { <button type="button" class="btn" (click)="mode.set('online')">Payer en ligne</button> }
              <button class="btn primary" [disabled]="d.invalid || busy.on()">Déclarer mon paiement</button>
            </div>
          </form>
        }
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
  /** Règles du plan du membre (absent si aucun plan ne lui est rattaché). */
  protected readonly plan = new Loader<PlanResponse | null>();
  protected readonly providers = new Loader<ProviderInfo[]>((ps) => {
    const first = ps.find((p) => p.available && !p.simulated) ?? ps.find((p) => p.available);
    this.provider = first?.code ?? '';
  });
  protected readonly instructions = new Loader<PaymentInstructions>();
  /** Moyens de paiement en ligne réellement ouverts ; sans eux, le membre déclare le paiement qu'il a fait lui-même. */
  protected readonly online = computed(() => (this.providers.data() ?? []).filter((p) => p.available));
  protected readonly methods = computed(() => this.instructions.data()?.declarationMethods ?? ['WAVE', 'ORANGE_MONEY', 'VIREMENT', 'CHEQUE']);
  protected readonly mode = signal<'online' | 'declare'>('online');
  protected readonly selected = signal<ObligationResponse | null>(null);
  protected readonly today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Africa/Dakar' });
  protected decl = { method: 'WAVE', reference: '', paidOn: this.today, comment: '' };
  protected readonly busy = new Busy();
  protected readonly notice = SIMULATION_NOTICE;
  protected amount = 0;
  protected provider = '';
  /** Une clé par tentative : un double clic ou une nouvelle soumission après coupure ne crée pas deux paiements. */
  private key = newIdempotencyKey();
  protected readonly payable = computed(() => (this.list.data() ?? []).filter((o) => (o.outstanding ?? 0) > 0));

  ngOnInit(): void {
    this.list.load(() => this.api.get<ObligationResponse[]>('/me/obligations'));
    this.plan.load(() => this.api.get<PlanResponse | null>('/me/plan'));
    this.providers.load(() => this.api.get<ProviderInfo[]>('/payments/providers'));
    this.instructions.load(() => this.api.get<PaymentInstructions>('/payments/instructions'));
    void this.auth;
  }

  select(o: ObligationResponse): void {
    this.selected.set(o);
    this.amount = o.outstanding ?? 0;
    this.key = newIdempotencyKey();
    this.mode.set('online');
    this.decl = { method: this.decl.method, reference: '', paidOn: this.today, comment: '' };
  }

  declare(o: ObligationResponse): void {
    const body = { amount: this.amount, obligationId: o.id, method: this.decl.method, externalReference: this.decl.reference.trim(),
      paidOn: this.decl.paidOn, comment: this.decl.comment.trim() || null };
    this.busy.run(
      this.api.postOnce<IntentResponse>('/payments/declarations', body, this.key),
      () => {
        this.toasts.success('Paiement déclaré. Il sera compté dès que le bureau l’aura vérifié.');
        void this.router.navigate(['/espace/paiements']);
      },
      (e) => {
        this.toasts.error(e);
        if ((e as { status?: number }).status) this.key = newIdempotencyKey();
      },
    );
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
