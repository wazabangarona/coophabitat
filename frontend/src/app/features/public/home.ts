import { Component, OnInit, effect, inject, untracked } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { PublicProject, PublicStats } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Loader } from '../../core/loader';
import { PublicInfoStore } from '../../core/public-info';
import { Badge, Icon, Photo, StateView } from '../../ui/components';
import { LabelPipe, NumPipe } from '../../ui/pipes';

@Component({
  selector: 'app-home',
  imports: [RouterLink, StateView, Badge, Icon, Photo, NumPipe, LabelPipe, LowerCasePipe],
  template: `
    <section class="hero" aria-labelledby="hero-title">
      <div class="hero-copy">
        <p class="eyebrow">{{ info.name() }}@if (info.info()?.legalForm) { · {{ info.info()?.legalForm }} }</p>
        <h1 id="hero-title" class="display">Votre projet immobilier mérite une gestion transparente.</h1>
        <p class="lead">Suivez vos cotisations, consultez les informations de votre coopérative et avancez sereinement vers
          votre projet d’habitat.</p>
        <div class="hero-actions">
          @if (auth.loggedIn()) {
            <a class="btn primary large" [routerLink]="auth.homeUrl()">Accéder à mon espace<app-icon name="arrow-right" /></a>
          } @else {
            <a class="btn primary large" routerLink="/connexion">Accéder à mon espace<app-icon name="arrow-right" /></a>
          }
          <a class="btn ghost large" routerLink="/verification-recu">Vérifier un reçu</a>
        </div>
        @if (info.info()?.publicStatsAvailable && stats.data(); as s) {
          <div class="hero-meta" aria-label="Chiffres publiés par la coopérative">
            <div><strong>{{ s.activeMembers | num }}</strong><span>membres actifs</span></div>
            <div><strong>{{ s.projects | num }}</strong><span>projet(s)</span></div>
            @for (e of lotEntries(s).slice(0, 2); track e[0]) {
              <div><strong>{{ e[1] | num }}</strong><span>lot(s) {{ e[0] | label | lowercase }}</span></div>
            }
          </div>
        }
      </div>
      <div class="hero-visual">
        <app-photo slot="hero" [priority]="true" sizes="(min-width: 900px) 52vw, 100vw" />
        <div class="hero-note">
          <span class="hero-note-title"><app-icon name="shield-check" />Reçus vérifiables</span>
          <p>Chaque paiement confirmé donne lieu à un reçu dont l’authenticité se contrôle en ligne.</p>
        </div>
      </div>
    </section>

    <section class="home-section" aria-labelledby="f1">
      <div class="feature">
        <app-photo slot="contributions" />
        <div class="feature-copy">
          <p class="feature-index">Cotisations</p>
          <h2 id="f1" class="h-section">Chaque franc versé est suivi, imputé et justifié.</h2>
          <p>Échéances, paiements et pénalités sont enregistrés avec leur règle de calcul. Un paiement n’est compté que
            lorsqu’il est confirmé par l’opérateur ou vérifié par deux responsables.</p>
          <ul class="feature-points">
            <li><app-icon name="circle-check" />Situation personnelle à jour : reste à payer, prochaine échéance, avance disponible.</li>
            <li><app-icon name="circle-check" />Reçu PDF pour chaque paiement confirmé, vérifiable avec son numéro et son code.</li>
          </ul>
        </div>
      </div>
    </section>

    <section class="home-section" aria-labelledby="f2">
      <div class="feature reverse">
        <app-photo slot="projects" />
        <div class="feature-copy">
          <p class="feature-index">Projets immobiliers</p>
          <h2 id="f2" class="h-section">Des projets suivis avec la rigueur d’un chantier.</h2>
          <p>Terrains, villas et appartements sont inventoriés avec leur surface, leur localisation administrative et leur
            statut. Les coordonnées ne sont présentées comme fiables que lorsqu’un professionnel les a vérifiées.</p>
          <a class="text-link" routerLink="/projets">Consulter les projets<app-icon name="arrow-right" /></a>
        </div>
      </div>
    </section>

    <div class="band">
      <section class="band-inner" aria-labelledby="f3">
        <div class="split-head">
          <div>
            <p class="feature-index">Attributions</p>
            <h2 id="f3" class="h-section">Une attribution des lots publique et contrôlable.</h2>
          </div>
          <p>Le résultat d’un tirage peut être recalculé par tout membre à partir des empreintes publiées avant le tirage.</p>
        </div>
        <ol class="process">
          <li><strong>Critères publiés</strong><p>Les règles d’éligibilité sont rendues publiques avant l’ouverture des candidatures.</p></li>
          <li><strong>Liste figée et validée</strong><p>La liste des candidats éligibles est arrêtée puis validée par les responsables.</p></li>
          <li><strong>Tirage vérifiable</strong><p>Le tirage repose sur des empreintes publiées à l’avance et recalculables.</p></li>
          <li><strong>Procès-verbal</strong><p>Le PV et les éventuelles contestations sont tracés et consultables.</p></li>
        </ol>
      </section>
    </div>

    <section class="home-section" aria-labelledby="f4">
      <div class="pillars-layout">
        <app-photo slot="living" />
        <div>
          <p class="feature-index">Au quotidien</p>
          <h2 id="f4" class="h-section">Tout ce qu’un membre doit savoir, au même endroit.</h2>
          <div class="pillars">
            <div class="pillar">
              <span class="pillar-icon"><app-icon name="file-text" /></span>
              <div><h3>Documents accessibles</h3>
                <p>Statuts, règlement, convocations et procès-verbaux, conservés avec leur empreinte et jamais modifiés après publication.</p></div>
            </div>
            <div class="pillar">
              <span class="pillar-icon"><app-icon name="scale" /></span>
              <div><h3>Rapports et comptes</h3>
                <p>Trésorerie, dépenses approuvées et rapports publiés sont consultables par chaque membre, avec la définition de chaque indicateur.</p></div>
            </div>
            <div class="pillar">
              <span class="pillar-icon"><app-icon name="user" /></span>
              <div><h3>Un accès personnel</h3>
                <p>Chaque membre dispose de son espace : sa situation, ses reçus, ses candidatures et les assemblées qui le concernent.</p></div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="home-section tight" aria-labelledby="fonds">
      <div class="escrow">
        <span class="escrow-icon"><app-icon name="landmark" /></span>
        <div>
          <h2 id="fonds">Garde des fonds</h2>
          <p class="escrow-statement">{{ info.escrow() }}</p>
          <p class="escrow-note">Cette mention reflète la configuration réelle de la coopérative : aucun séquestre bancaire n’est
            affiché comme existant s’il n’a pas été mis en place et vérifié.</p>
        </div>
      </div>
    </section>

    <section class="home-section" aria-labelledby="projets">
      <div class="split-head">
        <div>
          <p class="feature-index">Projets publiés</p>
          <h2 id="projets" class="h-section">Les projets de la coopérative</h2>
        </div>
        <p><a class="text-link" routerLink="/projets">Tous les projets<app-icon name="arrow-right" /></a></p>
      </div>
      <app-state [loading]="projects.loading()" [error]="projects.error()" [empty]="!projects.data()?.length"
        emptyText="Aucun projet publié pour le moment." emptyIcon="building-2" (retry)="projects.reload()">
        <div class="project-grid">
          @for (p of (projects.data() ?? []).slice(0, 3); track p.code) {
            <article class="project-card">
              <div class="plan-art"><app-badge [value]="p.status" /><app-icon name="building-2" /></div>
              <div class="project-body">
                <h3>{{ p.name }}</h3>
                <p class="project-place"><app-icon name="map-pin" />{{ place(p) }}</p>
                <div class="project-foot"><span>Lots</span><strong>{{ p.lots | num }}</strong></div>
              </div>
            </article>
          }
        </div>
      </app-state>
      @if (info.info()?.publicStatsAvailable && stats.data()?.note) {
        <p class="chart-caption">{{ stats.data()?.note }}</p>
      }
    </section>

    <section class="cta" aria-labelledby="cta-title">
      <div class="cta-inner">
        <div>
          <h2 id="cta-title">Votre situation, vos reçus et vos documents vous attendent.</h2>
          <p>Connectez-vous avec l’adresse électronique communiquée à la coopérative.</p>
        </div>
        <div class="hero-actions">
          <a class="btn primary large" routerLink="/connexion">Se connecter</a>
          @if (info.registrationOpen()) {
            <a class="btn large" routerLink="/inscription">Demander mon adhésion</a>
          } @else {
            <a class="btn large" routerLink="/documents-publics">Documents publics</a>
          }
        </div>
      </div>
    </section>
  `,
})
export class Home implements OnInit {
  protected readonly info = inject(PublicInfoStore);
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  protected readonly projects = new Loader<PublicProject[]>();
  protected readonly stats = new Loader<PublicStats>();

  constructor() {
    // Les statistiques ne sont demandées que si le bureau a choisi de les publier (informations publiques chargées).
    let requested = false;
    effect(() => {
      if (!requested && this.info.info()?.publicStatsAvailable) {
        requested = true;
        untracked(() => this.stats.load(() => this.api.get<PublicStats>('/public/stats')));
      }
    });
  }

  ngOnInit(): void {
    this.projects.load(() => this.api.get<PublicProject[]>('/public/projects'));
  }

  protected lotEntries(s: PublicStats): [string, number][] {
    return Object.entries((s.lotsByStatus ?? {}) as Record<string, number>);
  }

  protected place(p: PublicProject): string {
    return [p.commune, p.departement, p.region].filter(Boolean).join(', ') || 'Localisation non publiée';
  }
}
