import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { Api, newIdempotencyKey } from '../../core/api';
import {
  IntentResponse, MemberFinancialSummary, MemberResponse, ObligationResponse, PageResponseTransactionResponse, PlanResponse,
  StatusHistoryItem,
} from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { label } from '../../core/labels';
import { Toasts } from '../../core/toast';
import { Badge, Kpi, PageHeader, SimBadge, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe, formatMoney } from '../../ui/pipes';
import { MEMBER_TYPES, STATUS_TARGETS } from './members';

interface Data {
  member: MemberResponse;
  summary: MemberFinancialSummary;
  obligations: ObligationResponse[];
  history: StatusHistoryItem[];
  tx: PageResponseTransactionResponse | null;
}

@Component({
  selector: 'app-member-detail',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Badge, Kpi, SimBadge, LabelPipe, MoneyPipe, DayPipe, WhenPipe],
  template: `
    <app-state [loading]="d.loading() && !d.data()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as x) {
        <app-page-header [title]="x.member.firstName + ' ' + x.member.lastName" [subtitle]="x.member.memberNumber + ' · ' + (x.member.memberType | label)"
          back="/bureau/membres" backLabel="Membres">
          @if (auth.has('MEMBER_STATUS_CHANGE') && targets(x.member.status).length) {
            <button type="button" class="btn small" (click)="st.status = targets(x.member.status)[0]; statusForm.set(!statusForm())">Changer le statut</button> }
          @if (auth.has('MEMBER_WRITE')) { <button type="button" class="btn small" (click)="editing.set(!editing())">Modifier</button> }
        </app-page-header>
        @if (x.member.demo) { <p class="alert" data-tone="demo">Membre fictif de démonstration.</p> }
        @if (x.member.status === 'EN_ATTENTE') {
          <div class="alert row between" data-tone="info" role="status">
            <span><strong>Demande d’adhésion en attente.</strong> Elle s’approuve (avec le choix du plan de cotisation) ou se refuse
              depuis la page des demandes.</span>
            <a class="btn small primary" routerLink="/bureau/adhesions">Traiter la demande</a>
          </div>
        }
        @if (x.member.status === 'REFUSE') {
          <p class="alert" data-tone="warn">Demande d’adhésion refusée : l’accès en ligne est fermé. Pour la réexaminer, remettez-la
            « en attente » avec « Changer le statut ».</p>
        }

        <div class="kpis">
          <app-kpi label="Statut" [value]="label(x.member.status)" />
          <app-kpi label="Échu impayé" [value]="money(x.summary.overdue)" [tone]="(x.summary.overdue ?? 0) > 0 ? 'bad' : 'ok'" [hint]="(x.summary.overdueCount ?? 0) + ' échéance(s)'" />
          <app-kpi label="Pénalités dues" [value]="money(x.summary.penaltiesOutstanding)" />
          <app-kpi label="Total versé" [value]="money(x.summary.totalPaid)" />
          <app-kpi label="Avance" [value]="money(x.summary.credit)" />
        </div>

        @if (statusForm()) {
          <section class="card"><h2>Changer le statut</h2>
            <form class="grid-2" (ngSubmit)="changeStatus(x.member)" #sf="ngForm">
              <label class="field"><span>Nouveau statut</span><select name="s" [(ngModel)]="st.status" required>
                @for (s of targets(x.member.status); track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
              <label class="field"><span>Motif et référence de la décision</span><input name="r" [(ngModel)]="st.reason" required minlength="5" /></label>
              <p class="span-2 muted small">Le changement est tracé dans l’historique et le journal d’audit ; il est notifié au membre.</p>
              <div class="span-2 row"><button type="button" class="btn" (click)="statusForm.set(false)">Annuler</button>
                <button class="btn primary" [disabled]="sf.invalid || st.status === x.member.status || busy.on()">Appliquer</button></div>
            </form>
          </section>
        }

        @if (editing()) {
          <section class="card"><h2>Modifier la fiche</h2>
            <form class="grid-2" (ngSubmit)="save()" #f="ngForm">
              <label class="field"><span>Prénom</span><input name="fn" [(ngModel)]="edit.firstName" required /></label>
              <label class="field"><span>Nom</span><input name="ln" [(ngModel)]="edit.lastName" required /></label>
              <label class="field"><span>Adresse électronique</span><input type="email" name="em" [(ngModel)]="edit.email" email /></label>
              <label class="field"><span>Téléphone</span><input name="ph" [(ngModel)]="edit.phone" /></label>
              <label class="field"><span>Adresse</span><input name="ad" [(ngModel)]="edit.address" /></label>
              <label class="field"><span>Ville</span><input name="ci" [(ngModel)]="edit.city" /></label>
              <label class="field"><span>Type</span><select name="ty" [(ngModel)]="edit.memberType">@for (t of types; track t) { <option [value]="t">{{ t | label }}</option> }</select></label>
              <label class="field span-2"><span>Notes internes</span><textarea name="no" [(ngModel)]="edit.notes"></textarea></label>
              <div class="span-2 row"><button type="button" class="btn" (click)="editing.set(false)">Annuler</button><button class="btn primary" [disabled]="f.invalid || busy.on()">Enregistrer</button></div>
            </form>
          </section>
        }

        <section class="card"><h2>Coordonnées</h2>
          <dl class="dl">
            <dt>Adresse électronique</dt><dd>{{ x.member.email || '—' }}</dd><dt>Téléphone</dt><dd>{{ x.member.phone || '—' }}</dd>
            <dt>Adresse</dt><dd>{{ x.member.address || '—' }} {{ x.member.city }}</dd><dt>Adhésion</dt><dd>{{ x.member.membershipDate | day }}</dd>
            <dt>Accès en ligne</dt><dd>{{ x.member.hasAccount ? 'Oui' : 'Non' }}</dd>
            <dt>Plan de cotisation</dt><dd>{{ x.summary.planName || 'Aucun' }}
              @if (auth.has('PLAN_WRITE') && x.member.status !== 'RADIE' && x.member.status !== 'REFUSE' && x.member.id !== auth.user()?.memberId) {
                @if (plans().length) {
                  <span class="row wrap">
                    <select [(ngModel)]="planId" aria-label="Plan à affecter" class="inline-select">
                      <option [ngValue]="null" disabled>Choisir un plan…</option>
                      @for (p of plans(); track p.id) { <option [ngValue]="p.id">{{ p.name }} — {{ p.amount | money }}</option> }</select>
                    <button type="button" class="btn small primary" [disabled]="!planId || planId === x.member.contributionPlanId"
                      (click)="assignPlan(x.member)">{{ x.member.contributionPlanId ? 'Changer de plan' : 'Affecter ce plan' }}</button>
                  </span>
                } @else { <br /><small class="muted">Aucun plan actif : créez-en un dans <a routerLink="/bureau/cotisations">Cotisations</a>.</small> }
              } @else if (!x.member.contributionPlanId && x.member.status === 'EN_ATTENTE') {
                <br /><small class="muted">Le plan est choisi lors de l’approbation, ou affecté à l’avance par la trésorerie.</small>
              }</dd>
            @if (x.member.notes) { <dt>Notes internes</dt><dd class="pre">{{ x.member.notes }}</dd> }
          </dl>
        </section>

        <section class="card"><div class="row between"><h2>Échéances</h2>
          @if (auth.has('PAYMENT_MANUAL_RECORD')) { <button type="button" class="btn small" (click)="manualKey = newKey(); manual.set(!manual())">Enregistrer un paiement reçu</button> }</div>
          @if (manual()) {
            <form class="grid-2 card" (ngSubmit)="recordManual(x.member)" #mf="ngForm">
              <p class="span-2 alert" data-tone="info">Le paiement restera « en vérification » et ne sera compté qu’après validation par un second
                responsable, différent de vous.</p>
              <label class="field"><span>Montant (XOF)</span><input type="number" name="am" [(ngModel)]="mp.amount" required min="1" step="1" /></label>
              <label class="field"><span>Moyen</span><select name="me" [(ngModel)]="mp.method" required>
                <option value="ESPECES">Espèces</option><option value="WAVE">Wave (vers le compte de la coopérative)</option>
                <option value="ORANGE_MONEY">Orange Money (vers le compte de la coopérative)</option><option value="VIREMENT">Virement bancaire</option><option value="CHEQUE">Chèque</option>
                </select></label>
              <label class="field"><span>Reçu le</span><input type="date" name="rd" [(ngModel)]="mp.receivedOn" required /></label>
              <label class="field"><span>Référence externe (bordereau, n° de chèque)</span><input name="er" [(ngModel)]="mp.externalReference" /></label>
              <label class="field"><span>Échéance visée (facultatif)</span><select name="ob" [(ngModel)]="mp.obligationId">
                <option [ngValue]="null">Imputation automatique (plus ancienne d’abord)</option>
                @for (o of open(x.obligations); track o.id) { <option [ngValue]="o.id">{{ o.periodLabel }} — {{ o.outstanding | money }}</option> }</select></label>
              <label class="field"><span>Commentaire</span><input name="co" [(ngModel)]="mp.comment" /></label>
              <div class="span-2"><button class="btn primary" [disabled]="mf.invalid || busy.on()">Enregistrer</button></div>
            </form>
          }
          <div class="table-wrap"><table>
            <thead><tr><th>Période</th><th>Nature</th><th>Échéance</th><th class="num">Dû</th><th class="num">Payé</th><th class="num">Reste</th><th>Statut</th><th></th></tr></thead>
            <tbody>@for (o of x.obligations; track o.id) {
              <tr [class.late]="o.overdue"><td data-label="Période">{{ o.periodLabel }}</td><td data-label="Nature">{{ o.kind | label }}</td>
                <td data-label="Échéance">{{ o.dueDate | day }}</td><td data-label="Dû" class="num">{{ o.amountDue | money }}</td>
                <td data-label="Payé" class="num">{{ o.amountPaid | money }}</td><td data-label="Reste" class="num">{{ o.outstanding | money }}</td>
                <td data-label="Statut"><app-badge [value]="o.status" />@if (o.cancelReason) { <br /><small>{{ o.cancelReason }}</small> }</td>
                <td>@if (auth.has('OBLIGATION_ADJUST') && o.status !== 'ANNULEE' && o.status !== 'PAYEE') {
                  <button type="button" class="btn small" (click)="adjust(o)">Ajuster</button> }</td></tr>
            }</tbody>
          </table></div>
        </section>

        @if (x.tx) {
          <section class="card"><h2>Paiements confirmés</h2>
            <ul class="plain">@for (t of x.tx.content; track t.id) {
              <li>{{ t.confirmedAt | when }} — <strong>{{ t.amount | money }}</strong> ({{ t.provider | label }}) @if (t.simulated) { <app-sim-badge /> }
                <app-badge [value]="t.status" /> <span class="muted small mono">{{ t.reference }}</span></li>
            } @empty { <li class="muted">Aucun paiement.</li> }</ul>
          </section>
        }

        <section class="card"><h2>Historique des statuts</h2>
          <ul class="plain">@for (h of x.history; track h.id) {
            <li>{{ h.changedAt | when }} : {{ h.fromStatus | label }} → <strong>{{ h.toStatus | label }}</strong> — {{ h.reason }}</li>
          }</ul>
        </section>
      }
    </app-state>
  `,
})
export class MemberDetail implements OnInit {
  readonly id = input.required<string>();
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly d = new Loader<Data>((x) => {
    const m = x.member;
    this.edit = { firstName: m.firstName ?? '', lastName: m.lastName ?? '', email: m.email ?? '', phone: m.phone ?? '', address: m.address ?? '',
      city: m.city ?? '', memberType: m.memberType ?? 'ORDINAIRE', notes: m.notes ?? '', version: m.version ?? 0 };
    this.planId = m.contributionPlanId ?? null;
  });
  protected readonly plans = signal<PlanResponse[]>([]);
  protected readonly editing = signal(false);
  protected readonly manual = signal(false);
  protected readonly busy = new Busy();
  protected readonly types = MEMBER_TYPES;
  protected readonly money = formatMoney;
  protected readonly label = label;
  protected targets(status?: string): string[] {
    return STATUS_TARGETS[status ?? ''] ?? [];
  }
  protected readonly statusForm = signal(false);
  protected st = { status: '', reason: '' };
  protected edit = { firstName: '', lastName: '', email: '', phone: '', address: '', city: '', memberType: 'ORDINAIRE', notes: '', version: 0 };
  protected planId: number | null = null;
  protected manualKey = newIdempotencyKey();
  protected readonly newKey = newIdempotencyKey;
  protected mp = { amount: 0, method: 'ESPECES', receivedOn: new Date().toISOString().slice(0, 10), externalReference: '', obligationId: null as number | null, comment: '' };

  ngOnInit(): void {
    this.load();
    if (this.auth.has('PLAN_READ')) this.api.get<PlanResponse[]>('/contributions/plans').subscribe((p) => this.plans.set(p.filter((x) => x.status === 'ACTIF')));
  }

  load(): void {
    const id = this.id();
    this.d.load(() => forkJoin({
      member: this.api.get<MemberResponse>(`/members/${id}`),
      summary: this.api.get<MemberFinancialSummary>(`/members/${id}/summary`),
      obligations: this.api.get<ObligationResponse[]>(`/members/${id}/obligations`),
      history: this.api.get<StatusHistoryItem[]>(`/members/${id}/history`),
      tx: this.auth.has('PAYMENT_READ_ALL')
        ? this.api.get<PageResponseTransactionResponse>(`/payments/members/${id}/transactions`, { size: 50 })
        : of(null),
    }));
  }

  open(list: ObligationResponse[]): ObligationResponse[] {
    return list.filter((o) => (o.outstanding ?? 0) > 0 && o.status !== 'ANNULEE');
  }

  save(): void {
    this.busy.run(this.api.put(`/members/${this.id()}`, this.edit), () => {
      this.toasts.success('Fiche mise à jour.');
      this.editing.set(false);
      this.load();
    }, (e) => this.toasts.error(e));
  }

  changeStatus(m: MemberResponse): void {
    this.busy.run(this.api.post(`/members/${m.id}/status`, this.st), () => {
      this.toasts.success('Statut modifié.');
      this.statusForm.set(false);
      this.st = { status: '', reason: '' };
      this.load();
    }, (e) => this.toasts.error(e));
  }

  async assignPlan(m: MemberResponse): Promise<void> {
    const plan = this.plans().find((p) => p.id === this.planId);
    if (!plan) return;
    const ok = await this.dialogs.ask({
      title: m.contributionPlanId ? `Changer le plan de ${m.firstName} ${m.lastName}` : `Affecter un plan à ${m.firstName} ${m.lastName}`,
      message: `Nouveau plan : ${plan.name} (${formatMoney(plan.amount)}). Les prochaines échéances suivront ce plan ; les échéances déjà générées ne changent pas.`,
      confirmLabel: m.contributionPlanId ? 'Changer de plan' : 'Affecter ce plan',
    });
    if (ok === null) return;
    this.busy.run(this.api.post(`/members/${m.id}/plan`, { planId: plan.id }), () => {
      this.toasts.success('Plan affecté. Les prochaines échéances suivront ce plan.');
      this.load();
    }, (e) => this.toasts.error(e));
  }

  async adjust(o: ObligationResponse): Promise<void> {
    const isPenalty = o.kind === 'PENALITE';
    const reason = await this.dialogs.ask({
      title: isPenalty ? `Remettre la pénalité « ${o.periodLabel} »` : `Annuler l’échéance « ${o.periodLabel} »`,
      message: 'L’ajustement est définitif, tracé, et n’efface pas l’historique : le montant d’origine reste consultable.',
      reasonLabel: 'Motif et référence de la décision (procès-verbal, délibération…)', confirmLabel: 'Ajuster', danger: true, reasonMinLength: 10,
    });
    if (reason === null) return;
    const body = { type: isPenalty ? 'REMISE_PENALITE' : 'ANNULATION', newAmount: o.amountPaid ?? 0, reason, decisionReference: null };
    this.api.post(`/contributions/obligations/${o.id}/adjust`, body).subscribe({
      next: () => { this.toasts.success('Ajustement enregistré.'); this.load(); },
      error: (e) => this.toasts.error(e),
    });
  }

  recordManual(m: MemberResponse): void {
    this.busy.run(this.api.postOnce<IntentResponse>('/payments/manual', { ...this.mp, memberId: m.id }, this.manualKey), (i) => {
      this.toasts.success(`Paiement ${i.reference} enregistré, en attente de vérification par un second responsable.`);
      this.manual.set(false);
      this.load();
    }, (e) => this.toasts.error(e));
  }
}
