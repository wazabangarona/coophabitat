import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, saveBlob } from '../../core/api';
import { IntentResponse, PageResponseIntentResponse, PaymentInstructions } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, Pager, SimBadge, StateView } from '../../ui/components';
import { LabelPipe, MoneyPipe, WhenPipe } from '../../ui/pipes';

const STATUSES = ['INITIE', 'EN_ATTENTE', 'EN_VERIFICATION', 'CONFIRME', 'ECHOUE', 'EXPIRE', 'REMBOURSE'];
const PROVIDERS = ['WAVE', 'SENDFLASH', 'MANUEL', 'MOCK'];

@Component({
  selector: 'app-bureau-payments',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Pager, Badge, SimBadge, LabelPipe, MoneyPipe, WhenPipe],
  template: `
    <app-page-header title="Paiements" subtitle="Demandes de paiement et leur confirmation. Une demande n’est jamais un paiement tant qu’elle n’est pas confirmée.">
      <button type="button" class="btn small" (click)="export(false)">Exporter les paiements réels</button>
      <button type="button" class="btn small" (click)="export(true)">Exporter les simulations</button>
    </app-page-header>
    @if (auth.has('PAYMENT_MANUAL_VERIFY')) {
      <p class="alert" data-tone="info">Les paiements « en vérification » (déclarés par un membre ou saisis par la trésorerie) doivent être validés par une
        personne différente de celle qui les a saisis, après avoir retrouvé l’argent sur le compte de la coopérative.
        <button type="button" class="link" (click)="status = 'EN_VERIFICATION'; go(0)">Afficher ceux à vérifier</button></p>
    }
    @if (auth.hasAny('PAYMENT_MANUAL_RECORD', 'SETTINGS_ADMIN')) {
      <details class="card" [open]="instructionsMissing()">
        <summary>Consignes de paiement affichées aux membres</summary>
        <p class="small muted">Indiquez où payer la coopérative : numéro marchand Wave ou Orange Money, compte bancaire (RIB), heures de permanence pour les espèces.
          Les membres les voient au moment de payer, puis déclarent leur paiement avec sa référence.</p>
        <form (ngSubmit)="saveInstructions()">
          <label class="field"><span>Consignes</span>
            <textarea name="instr" rows="5" maxlength="2000" [(ngModel)]="instructions"
              placeholder="Exemple : Wave au 77 000 00 00 (Coopérative Teranga Habitat) ; virement sur le compte SN000 00000 000000000000 00."></textarea></label>
          <button class="btn primary small">Enregistrer les consignes</button>
        </form>
      </details>
    }
    <div class="filters">
      <label class="field inline"><span>Statut</span><select [(ngModel)]="status" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <label class="field inline"><span>Moyen</span><select [(ngModel)]="provider" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of providers; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <label class="field inline"><span>Nature</span><select [(ngModel)]="simulated" (ngModelChange)="go(0)">
        <option value="">Réels et simulés</option><option value="false">Réels uniquement</option><option value="true">Simulés uniquement</option></select></label>
    </div>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="Aucune demande." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Créée</th><th>Référence</th><th>Membre</th><th>Moyen</th><th class="num">Montant</th><th>Statut</th><th></th></tr></thead>
        <tbody>@for (i of list.data()?.content; track i.id) {
          <tr><td data-label="Créée">{{ i.createdAt | when }}</td>
            <td data-label="Référence" class="mono">{{ i.reference }}@if (i.externalReference) { <br /><small class="muted">{{ i.externalReference }}</small> }</td>
            <td data-label="Membre">@if (auth.has('MEMBER_READ_ALL')) { <a [routerLink]="['/bureau/membres', i.memberId]">Fiche n° {{ i.memberId }}</a> } @else { n° {{ i.memberId }} }</td>
            <td data-label="Moyen">{{ i.provider | label }}@if (i.channel && i.channel !== i.provider) { · {{ i.channel | label }} } @if (i.simulated) { <app-sim-badge /> }</td>
            <td data-label="Montant" class="num">{{ i.amount | money }}</td>
            <td data-label="Statut"><app-badge [value]="i.status" />@if (i.failureReason) { <br /><small>{{ i.failureReason }}</small> }
              @if (i.receiptNumber) { <br /><small>Reçu {{ i.receiptNumber }}</small> }</td>
            <td>
              @if (i.status === 'EN_VERIFICATION' && auth.has('PAYMENT_MANUAL_VERIFY')) {
                <button type="button" class="btn small primary" (click)="verify(i, true)">Valider</button>
                <button type="button" class="btn small danger" (click)="verify(i, false)">Rejeter</button>
              }
              @if (i.status === 'CONFIRME' && i.transactionId && auth.has('PAYMENT_REFUND')) {
                <button type="button" class="btn small" (click)="refund(i)">Rembourser</button>
              }
              @if ((i.status === 'EN_ATTENTE' || i.status === 'INITIE') && i.provider !== 'MANUEL') {
                <button type="button" class="btn small" (click)="check(i)">Interroger l’opérateur</button>
              }
            </td></tr>
        }</tbody>
      </table></div>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class BureauPayments implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly list = new Loader<PageResponseIntentResponse>();
  protected readonly page = signal(0);
  protected readonly statuses = STATUSES;
  protected readonly providers = PROVIDERS;
  protected status = '';
  protected provider = '';
  protected simulated = '';
  protected instructions = '';
  /** Ouvert d'office tant que la trésorerie n'a pas indiqué où payer (fixé au chargement, pas pendant la saisie). */
  protected readonly instructionsMissing = signal(false);

  ngOnInit(): void {
    this.go(0);
    if (this.auth.hasAny('PAYMENT_MANUAL_RECORD', 'SETTINGS_ADMIN')) {
      this.api.get<PaymentInstructions>('/payments/instructions').subscribe((r) => {
        this.instructions = r.instructions ?? '';
        this.instructionsMissing.set(!r.instructions);
      });
    }
  }

  saveInstructions(): void {
    this.api.put<PaymentInstructions>('/payments/instructions', { instructions: this.instructions }).subscribe({
      next: (r) => { this.instructions = r.instructions ?? ''; this.toasts.success('Consignes de paiement enregistrées.'); },
      error: (e) => this.toasts.error(e),
    });
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseIntentResponse>('/payments/intents', { status: this.status, provider: this.provider, simulated: this.simulated, page: p, size: 25 }));
  }

  async verify(i: IntentResponse, approve: boolean): Promise<void> {
    const comment = await this.dialogs.ask({
      title: approve ? `Valider le paiement ${i.reference}` : `Rejeter le paiement ${i.reference}`,
      message: approve ? `Confirmez avoir constaté l’encaissement${i.externalReference ? ' (référence ' + i.externalReference + ' retrouvée sur le relevé Wave, Orange Money ou bancaire)' : ' (espèces comptées)'}. Le paiement sera imputé et un reçu émis.` : 'Le paiement ne sera pas compté ; indiquez au membre pourquoi.',
      reasonLabel: 'Constat ou motif', confirmLabel: approve ? 'Valider' : 'Rejeter', danger: !approve,
    });
    if (comment === null) return;
    this.api.post(`/payments/manual/${i.id}/verify`, { approve, comment }).subscribe({
      next: () => { this.toasts.success(approve ? 'Paiement validé, reçu émis.' : 'Paiement rejeté.'); this.list.reload(); },
      error: (e) => this.toasts.error(e),
    });
  }

  async refund(i: IntentResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Rembourser ${i.reference}`, message: 'Le remboursement contre-passe les écritures et les imputations ; l’historique d’origine est conservé. Le versement effectif au membre se fait hors application.', reasonLabel: 'Motif du remboursement', confirmLabel: 'Enregistrer le remboursement', danger: true });
    if (reason === null) return;
    this.api.post(`/payments/transactions/${i.transactionId}/refund`, { reason }).subscribe({
      next: () => { this.toasts.success('Remboursement enregistré.'); this.list.reload(); },
      error: (e) => this.toasts.error(e),
    });
  }

  check(i: IntentResponse): void {
    this.api.post<IntentResponse>(`/payments/intents/${i.id}/check`).subscribe({
      next: (r) => { this.toasts.info(`Statut : ${r.status}`); this.list.reload(); },
      error: (e) => this.toasts.error(e),
    });
  }

  export(simulated: boolean): void {
    this.api.download('/payments/export', { simulated }).subscribe({
      next: (r) => saveBlob(r.blob, r.filename === 'document' ? `paiements${simulated ? '-simules' : ''}.csv` : r.filename),
      error: (e) => this.toasts.error(e),
    });
  }
}
