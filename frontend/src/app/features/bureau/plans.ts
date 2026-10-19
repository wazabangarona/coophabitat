import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api';
import { GenerationResult, PenaltySimulationResponse, PlanResponse, ReminderRun } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe } from '../../ui/pipes';

const PERIODS = ['MENSUELLE', 'TRIMESTRIELLE', 'SEMESTRIELLE', 'ANNUELLE'];
const PENALTIES = ['AUCUNE', 'FORFAIT', 'POURCENTAGE_MENSUEL'];
const ROUNDINGS = ['HALF_UP', 'UP', 'DOWN', 'HALF_EVEN'];
const today = () => new Date().toISOString().slice(0, 10);

@Component({
  selector: 'app-plans',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, MoneyPipe, DayPipe],
  template: `
    <app-page-header title="Cotisations" subtitle="Plans de cotisation, génération des échéances et simulation des pénalités.">
      @if (auth.has('PLAN_WRITE')) { <button type="button" class="btn small primary" (click)="openForm(null)">Nouveau plan</button> }
    </app-page-header>

    @if (form()) {
      <section class="card"><h2>{{ editId ? 'Modifier le plan (brouillon)' : 'Nouveau plan de cotisation' }}</h2>
        <form class="grid-2" (ngSubmit)="savePlan()" #f="ngForm">
          <label class="field"><span>Code</span><input name="code" [(ngModel)]="p.code" required pattern="[A-Z0-9_-]{2,30}" /></label>
          <label class="field"><span>Libellé</span><input name="name" [(ngModel)]="p.name" required /></label>
          <label class="field"><span>Montant par échéance (XOF)</span><input type="number" name="amount" [(ngModel)]="p.amount" required min="1" step="1" /></label>
          <label class="field"><span>Périodicité</span><select name="per" [(ngModel)]="p.periodicity">@for (x of periods; track x) { <option [value]="x">{{ x | label }}</option> }</select></label>
          <label class="field"><span>Jour d’échéance dans le mois</span><input type="number" name="dd" [(ngModel)]="p.dueDayOfMonth" min="1" max="31" /></label>
          <label class="field"><span>Délai de grâce (jours)</span><input type="number" name="gr" [(ngModel)]="p.graceDays" min="0" max="90" /></label>
          <label class="field"><span>Valable à partir du</span><input type="date" name="vf" [(ngModel)]="p.validFrom" required /></label>
          <label class="field"><span>Méthode de pénalité</span><select name="pm" [(ngModel)]="p.penaltyMethod">@for (x of penalties; track x) { <option [value]="x">{{ x | label }}</option> }</select></label>
          <label class="field"><span>{{ p.penaltyMethod === 'POURCENTAGE_MENSUEL' ? 'Taux mensuel (%)' : 'Montant de la pénalité (XOF)' }}</span>
            <input type="number" name="pv" [(ngModel)]="p.penaltyValue" min="0" step="any" [disabled]="p.penaltyMethod === 'AUCUNE'" /></label>
          <label class="field"><span>Plafond cumulé des pénalités (XOF, facultatif)</span><input type="number" name="pc" [(ngModel)]="p.penaltyCap" min="0" step="1" /></label>
          <label class="field"><span>Arrondi</span><select name="ro" [(ngModel)]="p.roundingMode">@for (x of roundings; track x) { <option [value]="x">{{ x | label }}</option> }</select></label>
          <label class="field"><span>Référence de la décision d’adoption</span><input name="ar" [(ngModel)]="p.adoptedReference" placeholder="Résolution n°…, AG du…" /></label>
          <label class="field span-2"><span>Description</span><textarea name="de" [(ngModel)]="p.description"></textarea></label>
          <div class="span-2 row"><button type="button" class="btn" (click)="form.set(false)">Annuler</button>
            <button class="btn primary" [disabled]="f.invalid || busy.on()">Enregistrer en brouillon</button></div>
        </form>
        <p class="muted small">Un plan actif ne peut plus être modifié : pour changer les règles, créez un nouveau plan et archivez l’ancien.</p>
      </section>
    }

    <app-state [loading]="plans.loading()" [error]="plans.error()" [empty]="!plans.data()?.length" emptyText="Aucun plan défini." (retry)="plans.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Plan</th><th class="num">Montant</th><th>Périodicité</th><th>Pénalité</th><th>Validité</th><th class="num">Membres</th><th>Statut</th><th></th></tr></thead>
        <tbody>@for (x of plans.data(); track x.id) {
          <tr><td data-label="Plan"><strong>{{ x.name }}</strong><br /><small class="muted">{{ x.code }}</small></td>
            <td data-label="Montant" class="num">{{ x.amount | money }}</td>
            <td data-label="Périodicité">{{ x.periodicity | label }}, le {{ x.dueDayOfMonth }}</td>
            <td data-label="Pénalité">{{ x.penaltyMethod | label }}@if (x.penaltyMethod === 'FORFAIT') { : {{ x.penaltyValue | money }} }
              @if (x.penaltyMethod === 'POURCENTAGE_MENSUEL') { : {{ x.penaltyValue }} % } — grâce {{ x.graceDays }} j
              @if (x.penaltyCap) { , plafond {{ x.penaltyCap | money }} }</td>
            <td data-label="Validité">{{ x.validFrom | day }} → {{ x.validTo ? (x.validTo | day) : '…' }}</td>
            <td data-label="Membres" class="num">{{ x.memberCount }}</td>
            <td data-label="Statut"><app-badge [value]="x.status" /></td>
            <td>@if (auth.has('PLAN_WRITE')) {
              @if (x.status === 'BROUILLON') { <button type="button" class="btn small" (click)="openForm(x)">Modifier</button>
                <button type="button" class="btn small primary" (click)="activate(x)">Activer</button> }
              @if (x.status === 'ACTIF') { <button type="button" class="btn small" (click)="archive(x)">Archiver</button> } }</td></tr>
        }</tbody>
      </table></div>
    </app-state>

    <div class="grid-2">
      @if (auth.has('OBLIGATION_GENERATE')) {
        <section class="card"><h2>Générer les échéances</h2>
          <p class="small">Crée les échéances dues jusqu’à la date choisie et les pénalités applicables. L’opération est idempotente :
            la relancer ne crée pas de doublon.</p>
          <label class="field"><span>Jusqu’au</span><input type="date" [(ngModel)]="asOf" /></label>
          <button type="button" class="btn primary" [disabled]="busy.on()" (click)="generate()">Générer</button>
          @if (gen(); as g) { <p class="alert" data-tone="ok">{{ g.obligationsCreated }} échéance(s) et {{ g.penaltiesCreated }} pénalité(s) créées pour {{ g.membersProcessed }} membre(s).</p> }
        </section>
      }
      @if (auth.has('NOTIFICATION_MANAGE')) {
        <section class="card"><h2>Rappels J-3</h2>
          <p class="small">Le rappel est envoyé automatiquement chaque jour 3 jours avant l’échéance. Vous pouvez relancer le calcul
            pour une date : un même rappel n’est jamais envoyé deux fois.</p>
          <label class="field"><span>Date d’exécution</span><input type="date" [(ngModel)]="reminderDate" /></label>
          <button type="button" class="btn" [disabled]="busy.on()" (click)="reminders()">Lancer les rappels</button>
          @if (rem(); as r) { <p class="alert" data-tone="ok">{{ r.obligations }} échéance(s) au {{ r.dueDate | day }} ; {{ r.notificationsCreated }} notification(s) créée(s).</p> }
        </section>
      }
      <section class="card"><h2>Simulateur de pénalités</h2>
        <form (ngSubmit)="simulate()" #sf="ngForm" class="grid-2">
          <label class="field"><span>Échéance</span><input type="date" name="d" [(ngModel)]="sim.dueDate" required /></label>
          <label class="field"><span>Principal dû (XOF)</span><input type="number" name="pr" [(ngModel)]="sim.principal" required min="1" /></label>
          <label class="field"><span>Grâce (jours)</span><input type="number" name="g" [(ngModel)]="sim.graceDays" min="0" /></label>
          <label class="field"><span>Méthode</span><select name="m" [(ngModel)]="sim.method">@for (x of penalties; track x) { <option [value]="x">{{ x | label }}</option> }</select></label>
          <label class="field"><span>Valeur</span><input type="number" name="v" [(ngModel)]="sim.value" min="0" step="any" /></label>
          <label class="field"><span>Plafond</span><input type="number" name="c" [(ngModel)]="sim.cap" min="0" /></label>
          <label class="field"><span>Au</span><input type="date" name="a" [(ngModel)]="sim.asOf" required /></label>
          <div class="span-2"><button class="btn" [disabled]="sf.invalid">Simuler</button></div>
        </form>
        @if (simResult(); as r) {
          <p><strong>Total : {{ r.total | money }}</strong></p>
          <ul class="plain small">@for (a of r.assessments; track a.sequence) { <li>N° {{ a.sequence }} au {{ a.assessmentDate | day }} : {{ a.amount | money }} (base {{ a.base | money }})</li> }</ul>
          <p class="muted small">Simulation sans principal remboursé entre-temps ; aucun montant n’est enregistré.</p>
        }
      </section>
    </div>
  `,
})
export class Plans implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly plans = new Loader<PlanResponse[]>();
  protected readonly form = signal(false);
  protected readonly gen = signal<GenerationResult | null>(null);
  protected readonly rem = signal<ReminderRun | null>(null);
  protected readonly simResult = signal<PenaltySimulationResponse | null>(null);
  protected readonly busy = new Busy();
  protected readonly periods = PERIODS;
  protected readonly penalties = PENALTIES;
  protected readonly roundings = ROUNDINGS;
  protected editId: number | null = null;
  protected p = this.blank();
  protected asOf = today();
  protected reminderDate = today();
  protected sim = { dueDate: today(), principal: 25000, graceDays: 5, method: 'FORFAIT', value: 1000, cap: null as number | null, rounding: 'HALF_UP', asOf: today() };

  ngOnInit(): void {
    this.plans.load(() => this.api.get<PlanResponse[]>('/contributions/plans'));
  }

  openForm(x: PlanResponse | null): void {
    this.editId = x?.id ?? null;
    this.p = x ? { code: x.code ?? '', name: x.name ?? '', description: x.description ?? '', amount: x.amount ?? 0, periodicity: x.periodicity ?? 'MENSUELLE',
      dueDayOfMonth: x.dueDayOfMonth ?? 5, validFrom: x.validFrom ?? today(), graceDays: x.graceDays ?? 0, penaltyMethod: x.penaltyMethod ?? 'AUCUNE',
      penaltyValue: x.penaltyValue ?? 0, penaltyCap: x.penaltyCap ?? null, roundingMode: x.roundingMode ?? 'HALF_UP', adoptedReference: x.adoptedReference ?? '' } : this.blank();
    this.form.set(true);
  }

  savePlan(): void {
    const req = this.editId ? this.api.put(`/contributions/plans/${this.editId}`, this.p) : this.api.post('/contributions/plans', this.p);
    this.busy.run(req, () => { this.toasts.success('Plan enregistré en brouillon.'); this.form.set(false); this.plans.reload(); }, (e) => this.toasts.error(e));
  }

  async activate(x: PlanResponse): Promise<void> {
    const ok = await this.dialogs.ask({ title: `Activer le plan « ${x.name} » ?`, message: 'Une fois actif, le plan est figé : ses montants et règles de pénalité ne pourront plus être modifiés.', confirmLabel: 'Activer' });
    if (ok === null) return;
    this.api.post(`/contributions/plans/${x.id}/activate`).subscribe({ next: () => { this.toasts.success('Plan activé.'); this.plans.reload(); }, error: (e) => this.toasts.error(e) });
  }

  async archive(x: PlanResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Archiver le plan « ${x.name} »`, message: 'Le plan cessera de produire des échéances après aujourd’hui. Les échéances existantes restent dues.', reasonLabel: 'Motif et décision', confirmLabel: 'Archiver', danger: true });
    if (reason === null) return;
    this.api.post(`/contributions/plans/${x.id}/archive`, { validTo: today(), reason }).subscribe({ next: () => { this.toasts.success('Plan archivé.'); this.plans.reload(); }, error: (e) => this.toasts.error(e) });
  }

  generate(): void {
    this.busy.run(this.api.post<GenerationResult>('/contributions/generate', {}, { asOf: this.asOf }), (g) => this.gen.set(g), (e) => this.toasts.error(e));
  }

  reminders(): void {
    this.busy.run(this.api.post<ReminderRun>('/notifications/reminders/run', {}, { date: this.reminderDate }), (r) => this.rem.set(r), (e) => this.toasts.error(e));
  }

  simulate(): void {
    this.api.post<PenaltySimulationResponse>('/contributions/penalties/simulate', this.sim).subscribe({ next: (r) => this.simResult.set(r), error: (e) => this.toasts.error(e) });
  }

  private blank() {
    return { code: '', name: '', description: '', amount: 0, periodicity: 'MENSUELLE', dueDayOfMonth: 5, validFrom: today(), graceDays: 5,
      penaltyMethod: 'AUCUNE', penaltyValue: 0, penaltyCap: null as number | null, roundingMode: 'HALF_UP', adoptedReference: '' };
  }
}
