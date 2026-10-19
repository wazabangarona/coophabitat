import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Api } from '../../core/api';
import { Verification } from '../../core/api-types';
import { errorMessage } from '../../core/errors';
import { SIMULATION_NOTICE } from '../../core/public-info';
import { PageHeader } from '../../ui/components';
import { MoneyPipe, WhenPipe } from '../../ui/pipes';

@Component({
  selector: 'app-receipt-verify',
  imports: [FormsModule, PageHeader, MoneyPipe, WhenPipe],
  template: `
    <app-page-header title="Vérifier un reçu"
      subtitle="Saisissez le numéro et le code de vérification imprimés sur le reçu." />
    <form class="card narrow" (ngSubmit)="verify()" #f="ngForm">
      <label class="field"><span>Numéro du reçu</span>
        <input name="number" [(ngModel)]="number" required autocomplete="off" placeholder="REC-2026-000001" /></label>
      <label class="field"><span>Code de vérification</span>
        <input name="code" [(ngModel)]="code" required autocomplete="off" /></label>
      <button class="btn primary" [disabled]="f.invalid || busy()">Vérifier</button>
    </form>
    @if (error()) { <p class="alert narrow" data-tone="bad" role="alert">{{ error() }}</p> }
    @if (result(); as r) {
      <section class="card narrow" role="status" aria-live="polite">
        @if (r.valid) {
          <p class="alert" [attr.data-tone]="r.simulated ? 'sim' : 'ok'">
            {{ r.simulated ? notice + ' — ce reçu provient d’un paiement simulé et n’a aucune valeur.' : 'Reçu authentique : il a bien été émis par la coopérative.' }}
          </p>
          <dl class="dl">
            <dt>Numéro</dt><dd>{{ r.receiptNumber }}</dd>
            <dt>Émis le</dt><dd>{{ r.issuedAt | when }}</dd>
            <dt>Montant</dt><dd>{{ r.amount | money }}</dd>
            <dt>État du paiement</dt><dd>{{ r.transactionStatus }}</dd>
          </dl>
          <p class="muted small">La vérification confirme l’émission du reçu par ce système. Elle ne constitue pas une signature
            électronique : le PDF n’est pas signé cryptographiquement.</p>
        } @else {
          <p class="alert" data-tone="bad">{{ r.message || 'Aucun reçu ne correspond à ces informations.' }}</p>
        }
      </section>
    }
  `,
})
export class ReceiptVerify implements OnInit {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  protected number = '';
  protected code = '';
  protected readonly notice = SIMULATION_NOTICE;
  protected readonly busy = signal(false);
  protected readonly result = signal<Verification | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const q = this.route.snapshot.queryParamMap;
    this.number = q.get('numero') ?? '';
    this.code = q.get('code') ?? '';
    if (this.number && this.code) this.verify();
  }

  protected verify(): void {
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    this.api.get<Verification>('/public/receipts/verify', { number: this.number.trim(), code: this.code.trim() }).subscribe({
      next: (r) => {
        this.result.set(r);
        this.busy.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
