import { Component, OnInit, inject } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { PublicProject, PublicStats } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { PublicInfoStore } from '../../core/public-info';
import { Badge, StateView } from '../../ui/components';
import { LabelPipe, NumPipe } from '../../ui/pipes';

@Component({
  selector: 'app-home',
  imports: [RouterLink, StateView, Badge, NumPipe, LabelPipe, LowerCasePipe],
  template: `
    <section class="hero">
      <p class="eyebrow">Coopérative d’habitat</p>
      <h1>{{ info.name() }}</h1>
      @if (info.info()?.legalForm) {
        <p class="lead">{{ info.info()?.legalForm }}</p>
      }
      <p class="lead">
        Cotisations suivies au franc près, reçus vérifiables, attribution des lots par une procédure publique
        et contrôlable, comptes consultables par chaque membre.
      </p>
      <div class="row">
        <a class="btn primary" routerLink="/connexion">Accéder à mon espace</a>
        <a class="btn" routerLink="/verification-recu">Vérifier un reçu</a>
      </div>
    </section>

    <section class="grid-3">
      <article class="card">
        <h2>Cotisations et reçus</h2>
        <p>Chaque échéance, chaque paiement et chaque pénalité est enregistré avec sa règle de calcul. Un paiement
          n’est compté que lorsqu’il est confirmé par l’opérateur ou vérifié par deux responsables.</p>
      </article>
      <article class="card">
        <h2>Attribution des lots</h2>
        <p>Critères publiés à l’avance, liste figée et validée, tirage dont le résultat peut être recalculé par
          tout membre à partir des empreintes publiées, procès-verbal et contestations tracés.</p>
      </article>
      <article class="card">
        <h2>Transparence</h2>
        <p>Les membres consultent la trésorerie, les dépenses approuvées et les rapports publiés. Le journal
          d’audit est chaîné : toute modification de l’historique est détectable.</p>
      </article>
    </section>

    <section class="card" aria-labelledby="fonds">
      <h2 id="fonds">Garde des fonds</h2>
      <p class="alert" data-tone="info">{{ info.escrow() }}</p>
      <p class="muted small">Cette mention reflète la configuration réelle de la coopérative : aucun séquestre bancaire n’est
        affiché comme existant s’il n’a pas été mis en place et vérifié.</p>
    </section>

    <section aria-labelledby="projets">
      <div class="row between"><h2 id="projets">Projets</h2><a routerLink="/projets">Tous les projets</a></div>
      <app-state [loading]="projects.loading()" [error]="projects.error()" [empty]="!projects.data()?.length"
        emptyText="Aucun projet publié pour le moment." (retry)="projects.reload()">
        <div class="grid-3">
          @for (p of (projects.data() ?? []).slice(0, 3); track p.code) {
            <article class="card">
              <h3>{{ p.name }}</h3>
              <p class="muted">{{ p.commune || '—' }}{{ p.departement ? ', ' + p.departement : '' }}{{ p.region ? ' (' + p.region + ')' : '' }}</p>
              <p><app-badge [value]="p.status" /> {{ p.lots | num }} lot(s)</p>
            </article>
          }
        </div>
      </app-state>
    </section>

    @if (info.info()?.publicStatsAvailable && stats.data(); as s) {
      <section class="card" aria-labelledby="stats">
        <h2 id="stats">Chiffres de la coopérative</h2>
        <ul class="inline-stats">
          <li><strong>{{ s.activeMembers | num }}</strong> membres actifs</li>
          <li><strong>{{ s.projects | num }}</strong> projet(s)</li>
          @for (e of lotEntries(s); track e[0]) {
            <li><strong>{{ e[1] | num }}</strong> lot(s) {{ e[0] | label | lowercase }}</li>
          }
        </ul>
        @if (s.note) { <p class="muted small">{{ s.note }}</p> }
      </section>
    }
  `,
})
export class Home implements OnInit {
  protected readonly info = inject(PublicInfoStore);
  private readonly api = inject(Api);
  protected readonly projects = new Loader<PublicProject[]>();
  protected readonly stats = new Loader<PublicStats>();

  ngOnInit(): void {
    this.projects.load(() => this.api.get<PublicProject[]>('/public/projects'));
    this.stats.load(() => this.api.get<PublicStats>('/public/stats'));
  }

  protected lotEntries(s: PublicStats): [string, number][] {
    return Object.entries((s.lotsByStatus ?? {}) as Record<string, number>);
  }
}
