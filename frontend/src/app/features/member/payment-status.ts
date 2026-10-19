import { Component, OnDestroy, OnInit, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { IntentResponse } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { SIMULATION_NOTICE } from '../../core/public-info';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { MoneyPipe, WhenPipe } from '../../ui/pipes';

const FINAL = ['CONFIRME', 'ECHOUE', 'EXPIRE', 'REMBOURSE'];

/**
 * Suivi d'un paiement : une demande de paiement n'est jamais présentée comme réussie tant que le serveur ne l'a
 * pas confirmée (webhook signé de l'opérateur ou vérification manuelle à deux personnes).
 */
@Component({
  selector: 'app-payment-status',
  imports: [RouterLink, PageHeader, StateView, Badge, MoneyPipe, WhenPipe],
  template: `
    <app-page-header title="Suivi du paiement" back="/espace/paiements" backLabel="Paiements et reçus" />
    <app-state [loading]="intent.loading() && !intent.data()" [error]="intent.error()" (retry)="intent.reload()">
      @if (intent.data(); as i) {
        <section class="card narrow" aria-live="polite">
          @if (i.simulated) { <p class="alert" data-tone="sim">{{ notice }}</p> }
          @switch (i.status) {
            @case ('CONFIRME') {
              <p class="alert" data-tone="ok"><strong>Paiement confirmé</strong> le {{ i.confirmedAt | when }}.
                @if (i.receiptNumber) { Reçu n° {{ i.receiptNumber }} disponible dans « Paiements et reçus ». }</p>
            }
            @case ('ECHOUE') { <p class="alert" data-tone="bad"><strong>Paiement non abouti.</strong> {{ i.failureReason }} Aucun montant n’a été imputé.</p> }
            @case ('EXPIRE') { <p class="alert" data-tone="bad"><strong>Demande expirée.</strong> Aucun montant n’a été imputé ; vous pouvez recommencer.</p> }
            @default {
              <p class="alert" data-tone="warn"><strong>En attente de confirmation de l’opérateur.</strong>
                Tant que la confirmation n’est pas reçue, rien n’est imputé sur vos cotisations. Cette page se met à jour automatiquement.</p>
            }
          }
          <dl class="dl">
            <dt>Référence</dt><dd class="mono">{{ i.reference }}</dd>
            <dt>Montant</dt><dd>{{ i.amount | money }}</dd>
            <dt>Statut</dt><dd><app-badge [value]="i.status" /></dd>
            <dt>Créée le</dt><dd>{{ i.createdAt | when }}</dd>
          </dl>
          <div class="row">
            @if (!final()) { <button type="button" class="btn" (click)="check()">Vérifier maintenant</button> }
            <a class="btn primary" routerLink="/espace/cotisations">Mes cotisations</a>
          </div>
        </section>
      }
    </app-state>
  `,
})
export class PaymentStatus implements OnInit, OnDestroy {
  readonly reference = input.required<string>();
  private readonly api = inject(Api);
  protected readonly intent = new Loader<IntentResponse>();
  protected readonly notice = SIMULATION_NOTICE;
  protected readonly final = computed(() => FINAL.includes(this.intent.data()?.status ?? ''));
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.intent.load(() => this.api.get<IntentResponse>(`/payments/intents/by-reference/${encodeURIComponent(this.reference())}`));
    this.timer = setInterval(() => {
      if (this.final()) clearInterval(this.timer);
      else if (navigator.onLine) this.intent.reload();
    }, 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  check(): void {
    const id = this.intent.data()?.id;
    if (id) this.intent.load(() => this.api.post<IntentResponse>(`/payments/intents/${id}/check`));
  }
}
