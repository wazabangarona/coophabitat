import { Component, OnInit, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Api, saveBlob } from '../../core/api';
import { AllocationResponse, PageResponseTransactionResponse, ReceiptItem, TransactionResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, Pager, SimBadge, StateView } from '../../ui/components';
import { LabelPipe, MoneyPipe, WhenPipe } from '../../ui/pipes';

interface Data {
  tx: PageResponseTransactionResponse;
  receipts: ReceiptItem[];
}

@Component({
  selector: 'app-my-payments',
  imports: [PageHeader, StateView, Pager, Badge, SimBadge, MoneyPipe, WhenPipe, LabelPipe],
  template: `
    <app-page-header title="Paiements et reçus"
      subtitle="Seuls les paiements confirmés par l’opérateur ou vérifiés par deux responsables apparaissent ici." />
    <app-state [loading]="data.loading()" [error]="data.error()" [empty]="!data.data()?.tx?.content?.length"
      emptyText="Aucun paiement confirmé pour le moment." (retry)="data.reload()">
      <div class="table-wrap">
        <table>
          <thead><tr><th>Date</th><th>Référence</th><th>Moyen</th><th class="num">Montant</th><th>Statut</th><th>Reçu</th></tr></thead>
          <tbody>
            @for (t of data.data()?.tx?.content; track t.id) {
              <tr>
                <td data-label="Date">{{ t.confirmedAt | when }}</td>
                <td data-label="Référence" class="mono">
                  <button type="button" class="link" (click)="toggle(t)" [attr.aria-expanded]="open() === t.id">{{ t.reference }}</button>
                </td>
                <td data-label="Moyen">{{ t.provider | label }} @if (t.simulated) { <app-sim-badge /> }</td>
                <td data-label="Montant" class="num">{{ t.amount | money }}</td>
                <td data-label="Statut"><app-badge [value]="t.status" />
                  @if (t.refundReason) { <br /><small class="muted">{{ t.refundReason }}</small> }</td>
                <td data-label="Reçu">
                  @if (t.receiptId) {
                    <button type="button" class="btn small" (click)="receipt(t)">Reçu {{ t.receiptNumber }}</button>
                  } @else { — }
                </td>
              </tr>
              @if (open() === t.id) {
                <tr class="sub"><td colspan="6">
                  <strong>Imputation :</strong>
                  @for (a of allocations(); track $index) {
                    <span class="chip">{{ a.periodLabel }} : {{ a.amount | money }}@if (a.reversal) { (contre-passation) }</span>
                  } @empty { <span class="muted">Montant conservé en avance sur les prochaines échéances.</span> }
                </td></tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pager [page]="page()" [totalPages]="data.data()?.tx?.totalPages ?? 0" [total]="data.data()?.tx?.totalElements ?? 0" (go)="go($event)" />
      <p class="muted small">Les reçus sont des documents PDF générés par la coopérative et vérifiables en ligne avec leur numéro et
        leur code. Ils ne portent pas de signature électronique.</p>
    </app-state>
  `,
})
export class MyPayments implements OnInit {
  private readonly api = inject(Api);
  private readonly auth = inject(Auth);
  private readonly toasts = inject(Toasts);
  protected readonly data = new Loader<Data>();
  protected readonly page = signal(0);
  protected readonly open = signal<number | null>(null);
  protected readonly allocations = signal<AllocationResponse[]>([]);
  private readonly memberId = this.auth.user()?.memberId;

  ngOnInit(): void {
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.data.load(() =>
      forkJoin({
        tx: this.api.get<PageResponseTransactionResponse>(`/payments/members/${this.memberId}/transactions`, { page: p, size: 20 }),
        receipts: this.api.get<ReceiptItem[]>(`/members/${this.memberId}/receipts`),
      }),
    );
  }

  toggle(t: TransactionResponse): void {
    if (this.open() === t.id) {
      this.open.set(null);
      return;
    }
    this.open.set(t.id ?? null);
    this.allocations.set([]);
    this.api.get<AllocationResponse[]>(`/payments/transactions/${t.id}/allocations`).subscribe((a) => this.allocations.set(a));
  }

  receipt(t: TransactionResponse): void {
    this.api.download(`/receipts/${t.receiptId}/pdf`).subscribe({
      next: (r) => saveBlob(r.blob, `${t.receiptNumber}.pdf`),
      error: (e) => this.toasts.error(e),
    });
  }
}
