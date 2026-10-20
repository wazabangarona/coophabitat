import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, saveBlob } from '../../core/api';
import { BulkPlanResult, CreatedMember, MemberListItem, PageResponseMemberListItem, PlanResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, Pager, StateView } from '../../ui/components';
import { DayPipe, LabelPipe } from '../../ui/pipes';

export const MEMBER_STATUSES = ['EN_ATTENTE', 'ACTIF', 'SUSPENDU', 'RADIE', 'REFUSE'];

/** Transitions de statut proposées sur la fiche (mêmes règles que le serveur ; une demande en attente se traite dans la file). */
export const STATUS_TARGETS: Record<string, string[]> = {
  EN_ATTENTE: [], ACTIF: ['SUSPENDU', 'RADIE'], SUSPENDU: ['ACTIF', 'RADIE'], REFUSE: ['EN_ATTENTE'], RADIE: [],
};
export const MEMBER_TYPES = ['FONDATEUR', 'ORDINAIRE', 'BENEFICIAIRE'];

@Component({
  selector: 'app-members',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Pager, Badge, LabelPipe, DayPipe],
  template: `
    <app-page-header title="Membres">
      @if (auth.has('MEMBER_EXPORT')) { <button type="button" class="btn small" (click)="export()">Exporter (CSV)</button> }
      @if (auth.has('MEMBER_WRITE')) { <button type="button" class="btn small primary" (click)="creating.set(!creating())">Nouveau membre</button> }
    </app-page-header>

    @if (creating()) {
      <section class="card">
        <h2>Nouvelle adhésion</h2>
        <form class="grid-2" (ngSubmit)="create()" #f="ngForm">
          <label class="field"><span>Prénom</span><input name="fn" [(ngModel)]="m.firstName" required /></label>
          <label class="field"><span>Nom</span><input name="ln" [(ngModel)]="m.lastName" required /></label>
          <label class="field"><span>Adresse électronique</span><input type="email" name="em" [(ngModel)]="m.email" email /></label>
          <label class="field"><span>Téléphone</span><input type="tel" name="ph" [(ngModel)]="m.phone" /></label>
          <label class="field"><span>Adresse</span><input name="ad" [(ngModel)]="m.address" /></label>
          <label class="field"><span>Ville</span><input name="ci" [(ngModel)]="m.city" /></label>
          <label class="field"><span>Date d’adhésion</span><input type="date" name="md" [(ngModel)]="m.membershipDate" required /></label>
          <label class="field"><span>Type d’adhésion</span>
            <select name="mt" [(ngModel)]="m.memberType" required>@for (t of types; track t) { <option [value]="t">{{ t | label }}</option> }</select></label>
          <label class="field"><span>Plan de cotisation</span>
            <select name="pl" [(ngModel)]="m.contributionPlanId">
              <option [ngValue]="null">Aucun pour le moment</option>
              @for (p of plans(); track p.id) { <option [ngValue]="p.id">{{ p.name }} ({{ p.status | label }})</option> }
            </select></label>
          <label class="field span-2"><span>Notes internes (non visibles du membre)</span><textarea name="no" [(ngModel)]="m.notes"></textarea></label>
          <label class="check span-2"><input type="checkbox" name="ca" [(ngModel)]="m.createAccount" [disabled]="!m.email" />
            Créer un accès à l’espace membre et envoyer une invitation par courriel</label>
          <div class="span-2 row"><button type="button" class="btn" (click)="creating.set(false)">Annuler</button>
            <button class="btn primary" [disabled]="f.invalid || busy.on()">Enregistrer l’adhésion</button></div>
        </form>
        <p class="muted small">Le membre est créé « en attente » : il rejoint les <a routerLink="/bureau/adhesions">demandes d’adhésion</a>,
          où son activation est une décision tracée.</p>
      </section>
    }

    @if (pending() > 0) {
      <div class="alert row between" data-tone="info" role="status">
        <span><strong>{{ pending() }} demande(s) d’adhésion en attente</strong> (déposées en ligne ou saisies par le bureau).
          Elles se traitent sur une seule page : vérification, choix du plan, puis approbation ou refus motivé.</span>
        <a class="btn small primary" routerLink="/bureau/adhesions">Examiner les demandes</a>
      </div>
    }

    <form class="filters" (ngSubmit)="go(0)">
      <label class="field inline"><span>Recherche</span><input type="search" name="q" [(ngModel)]="q" placeholder="Nom, numéro, ville…" /></label>
      <label class="field inline"><span>Statut</span>
        <select name="st" [(ngModel)]="status" (ngModelChange)="go(0)"><option value="">Tous</option>
          @for (s of statuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <label class="field inline"><span>Type</span>
        <select name="ty" [(ngModel)]="type" (ngModelChange)="go(0)"><option value="">Tous</option>
          @for (t of types; track t) { <option [value]="t">{{ t | label }}</option> }</select></label>
      <label class="check inline"><input type="checkbox" name="wp" [(ngModel)]="withoutPlan" (ngModelChange)="go(0)" /> Sans plan de cotisation</label>
      <button class="btn">Rechercher</button>
    </form>

    @if (canPlan()) {
      <div class="card row wrap bulk" role="group" aria-label="Affectation groupée d’un plan de cotisation">
        <strong>{{ selected().size }} membre(s) sélectionné(s)</strong>
        <label class="field inline"><span>Plan de cotisation</span>
          <select name="bp" [(ngModel)]="bulkPlanId">
            <option [ngValue]="null" disabled>Choisir…</option>
            @for (p of activePlans(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }
          </select></label>
        <button type="button" class="btn primary" [disabled]="!selected().size || !bulkPlanId || busy.on()" (click)="assignBulk()">Affecter ce plan</button>
        @if (selected().size) { <button type="button" class="btn" (click)="clearSelection()">Vider la sélection</button> }
        @if (!activePlans().length) { <span class="muted small">Aucun plan actif : créez-en un dans <a routerLink="/bureau/cotisations">Cotisations</a>.</span> }
        @else if (!selected().size) { <span class="muted small">Cochez des membres dans la liste (astuce : filtre « Sans plan de cotisation »).</span> }
      </div>
    }

    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="Aucun membre trouvé." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr>
          @if (canPlan()) { <th><input type="checkbox" aria-label="Sélectionner toute la page" [checked]="allSelected()" (change)="toggleAll()" /></th> }
          <th>Numéro</th><th>Nom</th><th>Ville</th><th>Type</th><th>Adhésion</th><th>Plan</th><th>Statut</th></tr></thead>
        <tbody>@for (x of list.data()?.content; track x.id) {
          <tr>@if (canPlan()) { <td><input type="checkbox" [attr.aria-label]="'Sélectionner ' + x.fullName" [disabled]="!selectable(x)"
                [checked]="selected().has(x.id!)" (change)="toggle(x.id!)" /></td> }
            <td data-label="Numéro"><a [routerLink]="['/bureau/membres', x.id]">{{ x.memberNumber }}</a></td>
            <td data-label="Nom">{{ x.fullName }}</td><td data-label="Ville">{{ x.city || '—' }}</td>
            <td data-label="Type">{{ x.memberType | label }}</td><td data-label="Adhésion">{{ x.membershipDate | day }}</td>
            <td data-label="Plan">{{ planName(x.contributionPlanId) }}</td>
            <td data-label="Statut"><app-badge [value]="x.status" /></td></tr>
        }</tbody>
      </table></div>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
  styles: `.bulk { flex-wrap: wrap; align-items: flex-end; gap: .75rem; }`,
})
export class Members implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<PageResponseMemberListItem>();
  protected readonly plans = signal<PlanResponse[]>([]);
  protected readonly creating = signal(false);
  protected readonly page = signal(0);
  /** Dossiers « en attente » (créés par le bureau ou demandés en ligne) à examiner. */
  protected readonly pending = signal(0);
  protected readonly busy = new Busy();
  private readonly dialogs = inject(Dialogs);
  protected readonly canPlan = computed(() => this.auth.has('PLAN_WRITE'));
  protected readonly activePlans = computed(() => this.plans().filter((p) => p.status === 'ACTIF'));
  protected readonly selected = signal(new Set<number>());
  protected bulkPlanId: number | null = null;
  protected withoutPlan = false;
  protected readonly statuses = MEMBER_STATUSES;
  protected readonly types = MEMBER_TYPES;
  protected q = '';
  protected status = '';
  protected type = '';
  protected m = this.blank();

  ngOnInit(): void {
    this.go(0);
    this.api.get<PageResponseMemberListItem>('/members', { status: 'EN_ATTENTE', page: 0, size: 1 })
      .subscribe({ next: (r) => this.pending.set(r.totalElements ?? 0), error: () => undefined });
    if (this.auth.has('PLAN_READ')) this.api.get<PlanResponse[]>('/contributions/plans').subscribe((p) => this.plans.set(p.filter((x) => x.status !== 'ARCHIVE')));
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseMemberListItem>('/members', { q: this.q.trim(), status: this.status, type: this.type,
      withoutPlan: this.withoutPlan ? 'true' : '', page: p, size: 25 }));
  }

  clearSelection(): void {
    this.selected.set(new Set());
  }

  planName(id?: number): string {
    if (!id) return 'Aucun';
    return this.plans().find((p) => p.id === id)?.name ?? '—';
  }

  selectable(x: MemberListItem): boolean {
    return x.status !== 'RADIE' && x.status !== 'REFUSE' && x.id !== this.auth.user()?.memberId;
  }

  toggle(id: number): void {
    const s = new Set(this.selected());
    if (s.has(id)) s.delete(id);
    else s.add(id);
    this.selected.set(s);
  }

  allSelected(): boolean {
    const rows = (this.list.data()?.content ?? []).filter((x) => this.selectable(x));
    return rows.length > 0 && rows.every((x) => this.selected().has(x.id!));
  }

  toggleAll(): void {
    const rows = (this.list.data()?.content ?? []).filter((x) => this.selectable(x));
    const s = new Set(this.selected());
    if (this.allSelected()) rows.forEach((x) => s.delete(x.id!));
    else rows.forEach((x) => s.add(x.id!));
    this.selected.set(s);
  }

  async assignBulk(): Promise<void> {
    const n = this.selected().size;
    const plan = this.plans().find((p) => p.id === this.bulkPlanId);
    if (!n || !plan) return;
    const ok = await this.dialogs.ask({
      title: `Affecter « ${plan.name} » à ${n} membre(s)`,
      message: 'Les prochaines échéances de ces membres suivront ce plan. Chaque affectation est enregistrée dans le journal d’audit.',
      confirmLabel: 'Affecter le plan',
    });
    if (ok === null) return;
    this.busy.run(this.api.post<BulkPlanResult>('/members/plan', { planId: plan.id, memberIds: [...this.selected()] }), (r) => {
      const extra = [r.unchanged ? `déjà sur ce plan : ${r.unchanged}` : '', r.excluded ? `exclus (radiés, refusés ou votre propre dossier) : ${r.excluded}` : '']
        .filter(Boolean).join(', ');
      this.toasts.success(`Plan affecté à ${r.updated} membre(s)${extra ? ' ; ' + extra : ''}.`);
      this.selected.set(new Set());
      this.list.reload();
    }, (e) => this.toasts.error(e));
  }

  create(): void {
    this.busy.run(this.api.post<CreatedMember>('/members', { ...this.m, email: this.m.email || null }), (r) => {
      this.toasts.success(`Membre ${r.member?.memberNumber} enregistré${r.invitationSent ? ', invitation envoyée' : ''}.`);
      this.creating.set(false);
      this.m = this.blank();
      void this.router.navigate(['/bureau/membres', r.member?.id]);
    }, (e) => this.toasts.error(e));
  }

  export(): void {
    this.api.download('/members/export', { status: this.status }).subscribe({
      next: (r) => saveBlob(r.blob, r.filename === 'document' ? 'membres.csv' : r.filename),
      error: (e) => this.toasts.error(e),
    });
  }

  private blank() {
    return { firstName: '', lastName: '', email: '', phone: '', address: '', city: '', membershipDate: new Date().toISOString().slice(0, 10),
      memberType: 'ORDINAIRE', contributionPlanId: null as number | null, notes: '', createAccount: false };
  }
}
