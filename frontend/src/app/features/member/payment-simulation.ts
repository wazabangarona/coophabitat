import { Component, OnInit, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { IntentResponse } from '../../core/api-types';
import { Busy, Loader } from '../../core/loader';
import { SIMULATION_NOTICE } from '../../core/public-info';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { MoneyPipe, WhenPipe } from '../../ui/pipes';

/** Page du fournisseur fictif : elle n'existe que lorsque le simulateur est activé hors production. */
@Component({
  selector: 'app-payment-simulation',
  imports: [RouterLink, PageHeader, StateView, Badge, MoneyPipe, WhenPipe],
  template: `
    <app-page-header title="Simulateur de paiement" back="/espace/cotisations" backLabel="Mes cotisations" />
    <div class="alert sim-big" data-tone="sim" role="alert"><strong>{{ notice }}</strong><br />
      Cette page imite un opérateur de paiement pour tester l’application. Le reçu produit portera la mention
      SIMULATION et le montant est comptabilisé à part, hors trésorerie réelle.</div>
    <app-state [loading]="intent.loading()" [error]="intent.error()" (retry)="intent.reload()">
      @if (intent.data(); as i) {
        <section class="card narrow">
          <dl class="dl">
            <dt>Référence</dt><dd class="mono">{{ i.reference }}</dd>
            <dt>Montant</dt><dd>{{ i.amount | money }}</dd>
            <dt>Statut</dt><dd><app-badge [value]="i.status" /></dd>
            <dt>Expire</dt><dd>{{ i.expiresAt | when }}</dd>
          </dl>
          @if (i.status === 'EN_ATTENTE' || i.status === 'INITIE') {
            <div class="row">
              <button type="button" class="btn primary" [disabled]="busy.on()" (click)="answer(true)">Simuler un paiement réussi</button>
              <button type="button" class="btn danger" [disabled]="busy.on()" (click)="answer(false)">Simuler un échec</button>
            </div>
          } @else {
            <a class="btn primary" [routerLink]="['/espace/paiements/suivi', i.reference]">Voir le suivi</a>
          }
        </section>
      }
    </app-state>
  `,
})
export class PaymentSimulation implements OnInit {
  readonly reference = input.required<string>();
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly intent = new Loader<IntentResponse>();
  protected readonly busy = new Busy();
  protected readonly notice = SIMULATION_NOTICE;
  protected readonly done = signal(false);

  ngOnInit(): void {
    this.intent.load(() => this.api.get<IntentResponse>(`/payments/intents/by-reference/${encodeURIComponent(this.reference())}`));
  }

  answer(success: boolean): void {
    this.busy.run(this.api.post<IntentResponse>(`/payments/simulation/${encodeURIComponent(this.reference())}`, { success }), () => {
      void this.router.navigate(['/espace/paiements/suivi', this.reference()]);
    }, (e) => this.toasts.error(e));
  }
}
