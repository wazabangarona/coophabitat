import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable } from 'rxjs';
import { Api, openBlob } from '../../core/api';
import {
  ApprovalResponse, CampaignResponse, CandidateResponse, CommissionMember, ContestResponse, DrawVerification, LotResponse, PvResponse,
  ResultResponse,
} from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe } from '../../ui/pipes';

const STEPS = ['BROUILLON', 'CRITERES_PUBLIES', 'CANDIDATURES_OUVERTES', 'CANDIDATURES_CLOSES', 'LISTE_PUBLIEE', 'VALIDEE', 'EXECUTEE', 'PV_VALIDE', 'RESULTATS_PUBLIES', 'ARCHIVEE'];

interface Data {
  c: CampaignResponse;
  candidates: CandidateResponse[];
  results: ResultResponse[];
  approvals: ApprovalResponse[];
  minutes: PvResponse[];
  contests: ContestResponse[];
  commission: CommissionMember[];
}
interface CommissionCandidate { userId: number; displayName: string; roles: string[] }

@Component({
  selector: 'app-allocation-detail',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, DayPipe, WhenPipe, MoneyPipe],
  template: `
    <app-state [loading]="d.loading() && !d.data()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as x) {
        <app-page-header [title]="x.c.title ?? ''" [subtitle]="x.c.reference + ' · ' + (x.c.method | label)" back="/bureau/attributions" backLabel="Attributions" />
        @if (x.c.demo) { <p class="alert" data-tone="demo">Procédure fictive de démonstration.</p> }
        @if (x.c.status === 'ANNULEE') { <p class="alert" data-tone="bad">Procédure annulée : {{ x.c.cancelReason }}</p> }
        <ol class="steps" aria-label="Étapes de la procédure">
          @for (s of steps; track s; let i = $index) {
            <li [class.done]="i < stepIndex()" [class.current]="i === stepIndex()" [attr.aria-current]="i === stepIndex() ? 'step' : null">{{ s | label }}</li>
          }
        </ol>

        <!-- Action attendue à l'étape courante -->
        <section class="card next-step" aria-labelledby="next">
          <h2 id="next">Étape suivante</h2>
          @switch (x.c.status) {
            @case ('BROUILLON') {
              <p>Choisissez les lots et la commission, puis publiez les critères aux membres. Après publication, les critères ne sont plus modifiables.</p>
              @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn primary" (click)="act('publish-criteria', 'Publier les critères ?', 'Les membres actifs seront notifiés. Les critères et les lots seront figés.')">Publier les critères</button> }
            }
            @case ('CRITERES_PUBLIES') {
              @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn primary" (click)="act('open', 'Ouvrir les candidatures ?')">Ouvrir les candidatures</button> }
            }
            @case ('CANDIDATURES_OUVERTES') {
              <p>{{ x.c.candidateCount }} candidature(s). Clôture prévue : {{ x.c.applicationClosesAt | when }}.</p>
              @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn primary" (click)="act('close', 'Clôturer les candidatures ?', 'Plus aucune candidature ne pourra être déposée ou retirée.')">Clôturer les candidatures</button> }
            }
            @case ('CANDIDATURES_CLOSES') {
              <p>Vérifiez l’éligibilité de chaque candidat selon les critères publiés, puis publiez la liste : elle sera figée et son empreinte publiée.</p>
              @if (can('ALLOCATION_MANAGE')) {
                <div class="row"><button type="button" class="btn" (click)="act('check-eligibility', 'Évaluer l’éligibilité ?', 'Chaque candidat est évalué automatiquement à la date de référence ; le motif est enregistré.')">Évaluer l’éligibilité</button>
                  <button type="button" class="btn primary" (click)="act('publish-list', 'Publier et figer la liste ?', 'La liste des éligibles, l’empreinte des lots et l’engagement sur la graine du tirage seront publiés et ne pourront plus changer.')">Publier la liste</button></div>
              }
            }
            @case ('LISTE_PUBLIEE') {
              <p>La liste doit être validée par {{ x.c.requiredApprovals }} responsables distincts, non candidats.
                Validations : {{ approvedCount(x, 'VALIDATION_LISTE') }} / {{ x.c.requiredApprovals }}.</p>
              @if (can('ALLOCATION_APPROVE')) { <div class="row"><button type="button" class="btn primary" (click)="approve('VALIDATION_LISTE', 'APPROUVE')">Valider la liste</button>
                <button type="button" class="btn danger" (click)="approve('VALIDATION_LISTE', 'REFUSE')">Refuser</button></div> }
            }
            @case ('VALIDEE') {
              @if (x.c.method === 'TIRAGE_AU_SORT') {
                <p>Le tirage est exécuté une seule fois. Vous pouvez y mêler un aléa public annoncé à l’avance (par exemple les numéros d’un tirage
                  public), qui sera publié avec le résultat.</p>
                @if (can('ALLOCATION_EXECUTE')) {
                  <label class="field"><span>Aléa public (facultatif)</span><input [(ngModel)]="entropy" maxlength="200" /></label>
                  <button type="button" class="btn primary" (click)="execute()">Exécuter le tirage</button>
                }
              } @else {
                <p>Chaque membre de la commission enregistre des décisions motivées ; elles sont ensuite validées par {{ x.c.requiredApprovals }} membres de la commission.</p>
                <form class="grid-2" (ngSubmit)="decide()" #df="ngForm">
                  <label class="field"><span>Candidat éligible</span><select name="ca" [(ngModel)]="decision.candidateId" required>
                    @for (c of eligible(); track c.id) { <option [ngValue]="c.id">{{ c.memberNumber }} — {{ c.memberName }}</option> }</select></label>
                  <label class="field"><span>Lot attribué</span><select name="lo" [(ngModel)]="decision.lotId">
                    <option [ngValue]="null">Liste d’attente</option>
                    @for (l of campaignLots(); track l.id) { <option [ngValue]="l.id">{{ l.reference }}</option> }</select></label>
                  <label class="field span-2"><span>Justification (publiée dans le PV)</span><textarea name="ju" [(ngModel)]="decision.justification" required minlength="20"></textarea></label>
                  <div class="span-2 row"><button class="btn" [disabled]="df.invalid || busy.on()">Enregistrer la décision</button>
                    <button type="button" class="btn primary" (click)="approve('DECISION_COMMISSION', 'APPROUVE')">Valider l’ensemble des décisions</button></div>
                </form>
              }
            }
            @case ('EXECUTEE') {
              <p>Le procès-verbal provisoire est généré. Il doit être validé par {{ x.c.requiredApprovals }} responsables :
                {{ approvedCount(x, 'VALIDATION_PV') }} / {{ x.c.requiredApprovals }}.</p>
              <div class="row">
                @if (can('ALLOCATION_APPROVE')) { <button type="button" class="btn primary" (click)="approve('VALIDATION_PV', 'APPROUVE', latestMinutes(x)?.id)">Valider le PV</button>
                  <button type="button" class="btn danger" (click)="approve('VALIDATION_PV', 'REFUSE', latestMinutes(x)?.id)">Refuser</button> }
                @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn" (click)="regenerate()">Régénérer le PV</button> }
              </div>
            }
            @case ('PV_VALIDE') {
              @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn primary" (click)="act('publish-results', 'Publier les résultats ?', 'Les résultats (numéros de membre et lots) seront visibles de tous les membres, qui seront notifiés.')">Publier les résultats</button> }
            }
            @case ('RESULTATS_PUBLIES') {
              <p>Les résultats sont publiés. Les contestations restent possibles et sont traitées ci-dessous.</p>
              @if (can('ALLOCATION_MANAGE')) { <button type="button" class="btn" (click)="act('archive', 'Archiver la procédure ?')">Archiver</button> }
            }
            @default { <p class="muted">Aucune action attendue.</p> }
          }
          @if (can('ALLOCATION_MANAGE') && !['ARCHIVEE', 'ANNULEE', 'RESULTATS_PUBLIES'].includes(x.c.status ?? '')) {
            <p><button type="button" class="link" (click)="cancel()">Annuler la procédure…</button></p>
          }
        </section>

        <div class="grid-2">
          <section class="card"><h2>Critères publiés</h2>
            <dl class="dl small">
              <dt>Date de référence</dt><dd>{{ x.c.referenceDate | day }}</dd>
              <dt>Ancienneté minimale</dt><dd>{{ x.c.minSeniorityMonths }} mois</dd>
              <dt>À jour exigé</dt><dd>{{ x.c.requireUpToDate ? 'Oui' : 'Non' }} (arriérés tolérés : {{ x.c.maxArrearsAmount | money }})</dd>
              <dt>Types admis</dt><dd>@for (t of x.c.allowedMemberTypes; track t) { <span class="chip">{{ t | label }}</span> }</dd>
            </dl>
            <p class="pre small">{{ x.c.eligibilityText }}</p>
            <p class="pre small muted">{{ x.c.rulesText }}</p>
          </section>
          <section class="card"><h2>Intégrité du tirage</h2>
            @if (x.c.seedCommitment) {
              <dl class="dl mono small">
                <dt>Algorithme</dt><dd>{{ x.c.algorithmVersion }}</dd><dt>Engagement</dt><dd>{{ x.c.seedCommitment }}</dd>
                <dt>Participants</dt><dd>{{ x.c.participantsDigest }}</dd><dt>Lots</dt><dd>{{ x.c.lotsDigest }}</dd>
                @if (x.c.revealedSeed) { <dt>Graine révélée</dt><dd>{{ x.c.revealedSeed }}</dd> }
                @if (x.c.publicEntropy) { <dt>Aléa public</dt><dd>{{ x.c.publicEntropy }}</dd> }
              </dl>
              @if (x.c.executedAt) { <button type="button" class="btn small" (click)="verify()">Recalculer et comparer</button> }
              @if (verification(); as v) { <p class="alert" [attr.data-tone]="v.valid ? 'ok' : 'bad'">{{ v.message }}</p> }
            } @else { <p class="muted">Les empreintes seront calculées à la publication de la liste.</p> }
          </section>
        </div>

        <section class="card"><div class="row between"><h2>Lots de la procédure</h2></div>
          @if (x.c.status === 'BROUILLON' && can('ALLOCATION_MANAGE')) {
            <p class="small muted">Seuls les lots disponibles peuvent être inclus.</p>
            <ul class="plain">@for (l of availableLots(); track l.id) {
              <li><label class="check"><input type="checkbox" [checked]="selectedLots().includes(l.id!)" (change)="toggleLot(l.id!)" /> {{ l.reference }} — {{ l.projectName }}, {{ l.commune }}</label></li>
            } @empty { <li class="muted">Aucun lot disponible.</li> }</ul>
            <button type="button" class="btn small" (click)="saveLots()">Enregistrer la sélection</button>
          } @else {
            <p>@for (l of campaignLots(); track l.id) { <span class="chip">{{ l.reference }}</span> } @empty { <span class="muted">Aucun lot.</span> }</p>
          }
        </section>

        <section class="card"><h2>Commission</h2>
          <ul class="plain">@for (m of x.commission; track m.userId) {
            <li>{{ nameOf(m.userId) }} — {{ m.roleInCommission || 'membre' }}@if (m.conflictDeclared) { <span class="badge" data-tone="bad">Conflit déclaré</span> {{ m.conflictDetails }} }</li>
          } @empty { <li class="muted">Aucun membre désigné.</li> }</ul>
          @if (x.c.status === 'BROUILLON' && can('ALLOCATION_MANAGE')) {
            <form class="grid-2" (ngSubmit)="addCommission()" #cf="ngForm">
              <label class="field"><span>Personne</span><select name="u" [(ngModel)]="cm.userId" required>
                @for (u of people(); track u.userId) { <option [ngValue]="u.userId">{{ u.displayName }}</option> }</select></label>
              <label class="field"><span>Rôle dans la commission</span><input name="r" [(ngModel)]="cm.role" placeholder="Président de séance, rapporteur…" /></label>
              <label class="check"><input type="checkbox" name="c" [(ngModel)]="cm.conflict" /> Conflit d’intérêts déclaré</label>
              <label class="field"><span>Précisions sur le conflit</span><input name="cd" [(ngModel)]="cm.details" /></label>
              <div class="span-2"><button class="btn small" [disabled]="cf.invalid">Ajouter à la commission</button></div>
            </form>
          }
        </section>

        <section class="card"><h2>Candidats ({{ x.candidates.length }})</h2>
          <div class="table-wrap"><table>
            <thead><tr><th>Membre</th><th>Candidature</th><th>Éligibilité</th><th>Motif</th></tr></thead>
            <tbody>@for (c of x.candidates; track c.id) {
              <tr><td data-label="Membre">{{ c.memberNumber }} — {{ c.memberName }}</td><td data-label="Candidature">{{ c.appliedAt | when }}@if (c.withdrawnAt) { <br /><small>retirée</small> }</td>
                <td data-label="Éligibilité"><app-badge [value]="c.eligibilityStatus" /></td><td data-label="Motif">{{ c.eligibilityReason || '—' }}</td></tr>
            }</tbody>
          </table></div>
        </section>

        @if (x.results.length) {
          <section class="card"><h2>Résultats</h2>
            <div class="table-wrap"><table>
              <thead><tr><th>Rang</th><th>Membre</th><th>Lot</th><th>Statut</th><th>Justification</th><th></th></tr></thead>
              <tbody>@for (r of x.results; track r.id) {
                <tr><td data-label="Rang">{{ r.drawRank ?? '—' }}</td><td data-label="Membre">{{ r.memberNumber }} — {{ r.memberName }}</td><td data-label="Lot">{{ r.lotReference || 'Liste d’attente' }}</td>
                  <td data-label="Statut"><app-badge [value]="r.status" />@if (r.cancelReason) { <br /><small>{{ r.cancelReason }}</small> }</td>
                  <td data-label="Justification">{{ r.justification || '—' }}</td>
                  <td>@if (r.status === 'DEFINITIF' && can('ALLOCATION_APPROVE')) { <button type="button" class="btn small danger" (click)="cancelResult(r)">Demander l’annulation</button> }</td></tr>
              }</tbody>
            </table></div>
          </section>
        }

        <section class="card"><h2>Validations enregistrées</h2>
          <ul class="plain small">@for (a of x.approvals; track a.id) {
            <li>{{ a.decidedAt | when }} — {{ a.step | label }} : <app-badge [value]="a.decision" /> par {{ a.approverName }}@if (a.comment) { — « {{ a.comment }} » }</li>
          } @empty { <li class="muted">Aucune.</li> }</ul>
        </section>

        @if (x.minutes.length) {
          <section class="card"><h2>Procès-verbaux</h2>
            <ul class="plain">@for (m of x.minutes; track m.id) {
              <li class="row between"><span>Version {{ m.versionNo }} — <app-badge [value]="m.status" /> générée le {{ m.generatedAt | when }}</span>
                <button type="button" class="btn small" (click)="openDoc(m.documentId!)">PDF</button></li>
            }</ul>
          </section>
        }

        <section class="card"><h2>Contestations</h2>
          <ul class="plain">@for (k of x.contests; track k.id) {
            <li class="card"><div class="row between"><strong>{{ k.reference }}</strong><app-badge [value]="k.status" /></div>
              <p class="small">Membre {{ k.memberNumber }}, déposée le {{ k.createdAt | when }}</p><p class="pre">{{ k.reason }}</p>
              @if (k.decision) { <p class="small"><strong>Décision :</strong> {{ k.decision }}</p> }
              @if ((k.status === 'DEPOSEE' || k.status === 'EN_EXAMEN') && can('ALLOCATION_CONTEST_DECIDE')) {
                <div class="row"><button type="button" class="btn small" (click)="decideContest(k, true)">Accepter</button>
                  <button type="button" class="btn small danger" (click)="decideContest(k, false)">Rejeter</button></div>
              }</li>
          } @empty { <li class="muted">Aucune contestation.</li> }</ul>
        </section>
      }
    </app-state>
  `,
})
export class AllocationDetail implements OnInit {
  readonly id = input.required<string>();
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly steps = STEPS;
  protected readonly busy = new Busy();
  protected readonly d = new Loader<Data>((x) => this.selectedLots.set([...(x.c.lotIds ?? [])]));
  protected readonly lots = signal<LotResponse[]>([]);
  protected readonly people = signal<CommissionCandidate[]>([]);
  protected readonly selectedLots = signal<number[]>([]);
  protected readonly verification = signal<DrawVerification | null>(null);
  protected readonly stepIndex = computed(() => STEPS.indexOf(this.d.data()?.c.status ?? ''));
  protected readonly eligible = computed(() => (this.d.data()?.candidates ?? []).filter((c) => c.eligibilityStatus === 'ELIGIBLE' && !c.withdrawnAt));
  protected readonly campaignLots = computed(() => this.lots().filter((l) => (this.d.data()?.c.lotIds ?? []).includes(l.id!)));
  protected readonly availableLots = computed(() => this.lots().filter((l) => l.status === 'DISPONIBLE' || this.selectedLots().includes(l.id!)));
  protected entropy = '';
  protected decision = { candidateId: null as number | null, lotId: null as number | null, justification: '' };
  protected cm = { userId: null as number | null, role: '', conflict: false, details: '' };

  ngOnInit(): void {
    this.load();
    this.api.getAll<LotResponse>('/property/lots').subscribe((l) => this.lots.set(l));
    if (this.can('ALLOCATION_MANAGE')) this.api.get<CommissionCandidate[]>('/allocations/commission-candidates').subscribe((p) => this.people.set(p));
  }

  can(p: string): boolean {
    return this.auth.has(p);
  }

  load(): void {
    const id = this.id();
    this.d.load(() => forkJoin({
      c: this.api.get<CampaignResponse>(`/allocations/${id}`),
      candidates: this.api.get<CandidateResponse[]>(`/allocations/${id}/candidates`),
      results: this.api.get<ResultResponse[]>(`/allocations/${id}/results`),
      approvals: this.api.get<ApprovalResponse[]>(`/allocations/${id}/approvals`),
      minutes: this.api.get<PvResponse[]>(`/allocations/${id}/minutes`),
      contests: this.api.get<ContestResponse[]>(`/allocations/${id}/contests`),
      commission: this.api.get<CommissionMember[]>(`/allocations/${id}/commission`),
    }));
  }

  approvedCount(x: Data, step: string): number {
    const latest = step === 'VALIDATION_PV' ? this.latestMinutes(x)?.id : undefined;
    return x.approvals.filter((a) => a.step === step && a.decision === 'APPROUVE' && (latest === undefined || a.targetId === latest)).length;
  }

  latestMinutes(x: Data): PvResponse | undefined {
    return [...x.minutes].sort((a, b) => (b.versionNo ?? 0) - (a.versionNo ?? 0))[0];
  }

  nameOf(userId?: number): string {
    return this.people().find((p) => p.userId === userId)?.displayName ?? `Utilisateur n° ${userId}`;
  }

  private run(obs: Observable<unknown>, success: string): void {
    this.busy.run(obs, () => { this.toasts.success(success); this.load(); }, (e) => this.toasts.error(e));
  }

  async act(path: string, title: string, message?: string): Promise<void> {
    if ((await this.dialogs.ask({ title, message })) === null) return;
    this.run(this.api.post(`/allocations/${this.id()}/${path}`), 'Étape enregistrée.');
  }

  async approve(step: string, decision: 'APPROUVE' | 'REFUSE', targetId?: number): Promise<void> {
    const comment = await this.dialogs.ask({
      title: decision === 'APPROUVE' ? 'Confirmer votre validation' : 'Confirmer votre refus',
      message: 'Votre décision est nominative et enregistrée dans le journal. Vous ne pouvez pas valider si vous êtes candidat ou en conflit d’intérêts.',
      reasonLabel: decision === 'REFUSE' ? 'Motif du refus' : undefined, danger: decision === 'REFUSE', confirmLabel: decision === 'APPROUVE' ? 'Valider' : 'Refuser',
    });
    if (comment === null) return;
    this.run(this.api.post(`/allocations/${this.id()}/approvals`, { step, decision, comment: comment || null, targetId: targetId ?? null }), 'Décision enregistrée.');
  }

  async execute(): Promise<void> {
    const ok = await this.dialogs.ask({ title: 'Exécuter le tirage ?', message: 'Le tirage ne peut être exécuté qu’une seule fois. Toute correction ultérieure exigera une annulation formelle et tracée.', confirmLabel: 'Exécuter maintenant' });
    if (ok === null) return;
    this.run(this.api.post(`/allocations/${this.id()}/execute`, { publicEntropy: this.entropy.trim() || null }), 'Tirage exécuté, PV provisoire généré.');
  }

  decide(): void {
    this.run(this.api.post(`/allocations/${this.id()}/decisions`, this.decision), 'Décision enregistrée (provisoire).');
    this.decision = { candidateId: null, lotId: null, justification: '' };
  }

  regenerate(): void {
    this.run(this.api.post(`/allocations/${this.id()}/minutes`), 'Nouvelle version du PV générée ; les validations repartent de zéro.');
  }

  toggleLot(id: number): void {
    this.selectedLots.update((l) => (l.includes(id) ? l.filter((x) => x !== id) : [...l, id]));
  }

  saveLots(): void {
    this.run(this.api.put(`/allocations/${this.id()}/lots`, { lotIds: this.selectedLots() }), 'Lots enregistrés.');
  }

  addCommission(): void {
    const current = (this.d.data()?.commission ?? []).map((m) => ({ userId: m.userId, role: m.roleInCommission, conflictDeclared: m.conflictDeclared, conflictDetails: m.conflictDetails }));
    const members = [...current.filter((m) => m.userId !== this.cm.userId),
      { userId: this.cm.userId, role: this.cm.role || null, conflictDeclared: this.cm.conflict, conflictDetails: this.cm.details || null }];
    this.run(this.api.put(`/allocations/${this.id()}/commission`, { members }), 'Commission mise à jour.');
    this.cm = { userId: null, role: '', conflict: false, details: '' };
  }

  verify(): void {
    this.api.get<DrawVerification>(`/allocations/${this.id()}/verify`).subscribe({ next: (v) => this.verification.set(v), error: (e) => this.toasts.error(e) });
  }

  async cancel(): Promise<void> {
    const reason = await this.dialogs.ask({ title: 'Annuler la procédure', message: 'Les lots réservés redeviennent disponibles. La procédure annulée reste consultable.', reasonLabel: 'Motif et décision', danger: true, confirmLabel: 'Annuler la procédure', reasonMinLength: 10 });
    if (reason === null) return;
    this.run(this.api.post(`/allocations/${this.id()}/cancel`, { reason }), 'Procédure annulée.');
  }

  async cancelResult(r: ResultResponse): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Annuler l’attribution du lot ${r.lotReference}`, message: 'L’annulation exige plusieurs validations distinctes. Le résultat d’origine reste dans l’historique.', reasonLabel: 'Motif et référence de la décision', danger: true, reasonMinLength: 10 });
    if (reason === null) return;
    this.run(this.api.post(`/allocations/results/${r.id}/cancel`, { reason }), 'Demande d’annulation enregistrée.');
  }

  async decideContest(k: ContestResponse, accept: boolean): Promise<void> {
    const decision = await this.dialogs.ask({ title: accept ? 'Accepter la contestation' : 'Rejeter la contestation', message: accept ? 'Le lot concerné passera au statut « litigieux ».' : undefined, reasonLabel: 'Décision motivée (communiquée au membre)', reasonMinLength: 10 });
    if (decision === null) return;
    this.run(this.api.post(`/allocations/contests/${k.id}/decision`, { accept, decision }), 'Décision enregistrée.');
  }

  openDoc(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
