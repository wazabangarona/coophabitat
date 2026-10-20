import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { Api } from '../../core/api';
import { ApplicationItem, ApplicationQueue, PlanResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, MoneyPipe, WhenPipe } from '../../ui/pipes';

/**
 * File des demandes d'adhésion : une seule page pour examiner chaque dossier en attente, puis l'approuver
 * (plan de cotisation + activation en une décision) ou le refuser avec un motif. La personne est prévenue.
 */
@Component({
  selector: 'app-applications',
  imports: [FormsModule, RouterLink, PageHeader, StateView, LabelPipe, DayPipe, WhenPipe, MoneyPipe],
  template: `
    <app-page-header title="Demandes d’adhésion" subtitle="Examiner, approuver ou refuser les dossiers en attente" back="/bureau/membres" backLabel="Membres" />

    <section class="card">
      <ol class="howto small">
        <li><strong>La personne dépose sa demande</strong> (bouton « Adhérer » du site) ou le bureau saisit son dossier.</li>
        <li><strong>Le bureau vérifie</strong> son identité et ses coordonnées.</li>
        <li><strong>La présidence ou le secrétariat approuve</strong> en choisissant le plan de cotisation : l’adhésion est activée
          immédiatement. La trésorerie peut préparer le dossier en affectant le plan à l’avance.</li>
        <li><strong>La personne est prévenue</strong> (espace membre et courriel) ; un refus est toujours motivé.</li>
      </ol>
      <p class="muted small">Chaque décision est enregistrée dans l’historique du membre et dans le journal d’audit.</p>
    </section>

    @if (queue.data(); as qd) {
      @if (qd.bootstrap) {
        <div class="alert" data-tone="info" role="status">
          <strong>Mise en route du bureau.</strong> Aucun autre responsable habilité à valider les adhésions n’est encore en place :
          vous pouvez exceptionnellement valider votre propre adhésion (ce cas est signalé dans le journal d’audit). Ensuite,
          l’adhésion d’un responsable est toujours validée par un autre.
        </div>
      }
      @if (qd.canDecide && !plans().length && plansLoaded()) {
        <div class="alert" data-tone="warn" role="alert">
          <strong>Aucun plan de cotisation actif.</strong> Une adhésion ne peut être activée sans plan : la trésorerie doit d’abord créer
          et activer un plan dans <a routerLink="/bureau/cotisations">Cotisations</a>.
        </div>
      }
      @if (!qd.canDecide) {
        <p class="alert" data-tone="info">La validation revient à la présidence ou au secrétariat.
          @if (canAssignPlan()) { Vous pouvez préparer chaque dossier en lui affectant son plan de cotisation. }</p>
      }
    }

    <app-state [loading]="queue.loading() && !queue.data()" [error]="queue.error()" [empty]="!queue.data()?.items?.length"
      emptyText="Aucune demande en attente. Les nouvelles demandes apparaîtront ici." (retry)="queue.reload()">
      @for (a of queue.data()?.items; track a.id) {
        <article class="card">
          <div class="row between">
            <div>
              <h2 class="h3">{{ a.firstName }} {{ a.lastName }}</h2>
              <p class="muted small">{{ a.memberNumber }} · {{ a.memberType | label }} · demande du {{ a.createdAt | when }}</p>
            </div>
            <div class="row">
              <span class="badge" data-tone="warn">En attente</span>
              <span class="badge">{{ a.online ? 'Demande en ligne' : 'Saisie par le bureau' }}</span>
              @if (a.self) { <span class="badge" data-tone="info">Votre demande</span> }
            </div>
          </div>
          <dl class="dl">
            <dt>Adresse électronique</dt><dd>{{ a.email || '—' }}</dd>
            <dt>Téléphone</dt><dd>{{ a.phone || '—' }}</dd>
            <dt>Ville</dt><dd>{{ a.city || '—' }}</dd>
            <dt>Date d’adhésion prévue</dt><dd>{{ a.membershipDate | day }}</dd>
            <dt>Accès en ligne</dt><dd>{{ a.hasAccount ? 'Oui, la personne peut déjà se connecter' : 'Non' }}</dd>
            <dt>Plan de cotisation</dt><dd>{{ a.planName || 'Pas encore choisi' }}</dd>
          </dl>

          @if (queue.data()?.canDecide && (!a.self || queue.data()?.bootstrap)) {
            <form class="row wrap approve" (ngSubmit)="approve(a)">
              <label class="field inline"><span>Plan de cotisation</span>
                <select [name]="'plan' + a.id" [(ngModel)]="choice[a.id!]" required>
                  <option [ngValue]="null" disabled>Choisir…</option>
                  @for (p of plans(); track p.id) { <option [ngValue]="p.id">{{ p.name }} — {{ p.amount | money }}</option> }
                </select></label>
              <button class="btn primary" [disabled]="!choice[a.id!] || busy.on()">Approuver et activer</button>
              <button type="button" class="btn danger" [disabled]="busy.on()" (click)="reject(a)">Refuser…</button>
              <a class="btn" [routerLink]="['/bureau/membres', a.id]">Voir la fiche</a>
            </form>
          } @else if (a.self) {
            <p class="muted small">Votre adhésion doit être validée par un autre membre du bureau.</p>
          } @else if (canAssignPlan()) {
            <form class="row wrap" (ngSubmit)="assignPlan(a)">
              <label class="field inline"><span>Plan de cotisation</span>
                <select [name]="'plan' + a.id" [(ngModel)]="choice[a.id!]">
                  <option [ngValue]="null" disabled>Choisir…</option>
                  @for (p of plans(); track p.id) { <option [ngValue]="p.id">{{ p.name }} — {{ p.amount | money }}</option> }
                </select></label>
              <button class="btn" [disabled]="!choice[a.id!] || choice[a.id!] === a.contributionPlanId || busy.on()">Affecter ce plan</button>
              <a class="btn" [routerLink]="['/bureau/membres', a.id]">Voir la fiche</a>
            </form>
          } @else {
            <a class="btn small" [routerLink]="['/bureau/membres', a.id]">Voir la fiche</a>
          }
        </article>
      }
    </app-state>
  `,
  styles: `
    .howto { margin: 0 0 .5rem; padding-left: 1.25rem; display: grid; gap: .35rem; }
    .h3 { margin: 0; font-size: 1.1rem; }
    .wrap { flex-wrap: wrap; align-items: flex-end; }
  `,
})
export class Applications implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly queue = new Loader<ApplicationQueue>((q) => {
    // Pré-sélection : le plan déjà affecté, ou le seul plan actif s'il n'y en a qu'un.
    for (const a of q.items ?? []) {
      if (this.choice[a.id!] === undefined) this.choice[a.id!] = a.contributionPlanId ?? this.single();
    }
  });
  protected readonly plans = signal<PlanResponse[]>([]);
  protected readonly plansLoaded = signal(false);
  protected readonly canAssignPlan = computed(() => this.auth.has('PLAN_WRITE'));
  protected readonly busy = new Busy();
  protected choice: Record<number, number | null> = {};

  ngOnInit(): void {
    this.load();
    if (this.auth.has('PLAN_READ')) {
      this.api.get<PlanResponse[]>('/contributions/plans').subscribe((p) => {
        this.plans.set(p.filter((x) => x.status === 'ACTIF'));
        this.plansLoaded.set(true);
        for (const a of this.queue.data()?.items ?? []) {
          if (!this.choice[a.id!]) this.choice[a.id!] = a.contributionPlanId ?? this.single();
        }
      });
    }
  }

  load(): void {
    this.queue.load(() => this.api.get<ApplicationQueue>('/members/applications'));
  }

  private single(): number | null {
    return this.plans().length === 1 ? (this.plans()[0].id ?? null) : null;
  }

  private planName(id: number | null | undefined): string {
    return this.plans().find((p) => p.id === id)?.name ?? '';
  }

  private run(obs: Observable<unknown>, msg: string): void {
    this.busy.run(obs, () => { this.toasts.success(msg); this.load(); }, (e) => this.toasts.error(e));
  }

  async approve(a: ApplicationItem): Promise<void> {
    const planId = this.choice[a.id!];
    if (!planId) return;
    const ok = await this.dialogs.ask({
      title: `Approuver l’adhésion de ${a.firstName} ${a.lastName}`,
      message: `Plan de cotisation : ${this.planName(planId)}. L’adhésion sera activée immédiatement et la personne sera prévenue.`,
      confirmLabel: 'Approuver et activer',
    });
    if (ok === null) return;
    this.run(this.api.post(`/members/${a.id}/approve`, { planId }), `Adhésion de ${a.firstName} ${a.lastName} approuvée et activée.`);
  }

  async reject(a: ApplicationItem): Promise<void> {
    const reason = await this.dialogs.ask({
      title: `Refuser la demande de ${a.firstName} ${a.lastName}`,
      message: 'Le motif est communiqué à la personne. Son accès en ligne est fermé ; la demande pourra être réexaminée depuis sa fiche.',
      reasonLabel: 'Motif du refus', reasonMinLength: 5, confirmLabel: 'Refuser la demande', danger: true,
    });
    if (reason === null) return;
    this.run(this.api.post(`/members/${a.id}/reject`, { reason }), 'Demande refusée ; la personne est prévenue.');
  }

  assignPlan(a: ApplicationItem): void {
    const planId = this.choice[a.id!];
    if (!planId) return;
    this.run(this.api.post(`/members/${a.id}/plan`, { planId }), 'Plan affecté : le dossier est prêt à être validé.');
  }
}
