import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { LotHistoryItem, LotResponse, ProjectResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, Icon, Kpi, PageHeader, Pager, StateView } from '../../ui/components';
import { LabelPipe, NumPipe, WhenPipe } from '../../ui/pipes';

export const LOT_STATUSES = ['EN_PREPARATION', 'DISPONIBLE', 'RESERVE', 'ATTRIBUE', 'LITIGIEUX', 'INDISPONIBLE'];
const LOT_TYPES = ['TERRAIN', 'VILLA', 'APPARTEMENT', 'DUPLEX', 'LOCAL'];
const PROJECT_STATUSES = ['ETUDE', 'EN_COURS', 'LIVRE', 'SUSPENDU'];

const VIEW_KEY = 'coophabitat.lots.vue';

function readView(): 'grid' | 'table' {
  try {
    return localStorage.getItem(VIEW_KEY) === 'table' ? 'table' : 'grid';
  } catch {
    return 'grid';
  }
}

interface Page<T> { content?: T[]; totalPages?: number; totalElements?: number }

@Component({
  selector: 'app-lots',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Pager, Badge, Icon, LabelPipe, NumPipe],
  template: `
    <app-page-header title="Projets et lots" subtitle="Inventaire du patrimoine. Les statuts « réservé » et « attribué » ne sont posés que par une procédure d’attribution.">
      @if (auth.has('LOT_WRITE')) {
        <button type="button" class="btn small" (click)="projectForm.set(!projectForm())">Nouveau projet</button>
        <button type="button" class="btn small primary" (click)="lotForm.set(!lotForm())"><app-icon name="plus" />Nouveau lot</button>
      }
    </app-page-header>

    @if (projectForm()) {
      <section class="card"><h2>Nouveau projet</h2>
        <form class="grid-2" (ngSubmit)="saveProject()" #pf="ngForm">
          <label class="field"><span>Code</span><input name="c" [(ngModel)]="pr.code" required /></label>
          <label class="field"><span>Nom</span><input name="n" [(ngModel)]="pr.name" required /></label>
          <label class="field"><span>Région</span><input name="r" [(ngModel)]="pr.region" /></label>
          <label class="field"><span>Département</span><input name="d" [(ngModel)]="pr.departement" /></label>
          <label class="field"><span>Commune</span><input name="co" [(ngModel)]="pr.commune" /></label>
          <label class="field"><span>Statut</span><select name="s" [(ngModel)]="pr.status">@for (s of projectStatuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
          <label class="field span-2"><span>Description</span><textarea name="de" [(ngModel)]="pr.description"></textarea></label>
          <div class="span-2 form-actions"><button class="btn primary" [disabled]="pf.invalid || busy.on()">Créer le projet</button></div>
        </form>
      </section>
    }

    @if (lotForm()) {
      <section class="card"><h2>Nouveau lot</h2>
        <form class="grid-2" (ngSubmit)="saveLot()" #lf="ngForm">
          <label class="field"><span>Référence</span><input name="ref" [(ngModel)]="lot.reference" required /></label>
          <label class="field"><span>Projet</span><select name="p" [(ngModel)]="lot.projectId" required>
            @for (p of projects.data(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }</select></label>
          <label class="field"><span>Type</span><select name="t" [(ngModel)]="lot.lotType">@for (t of lotTypes; track t) { <option [value]="t">{{ t | label }}</option> }</select></label>
          <label class="field"><span>Surface (m²)</span><input type="number" name="a" [(ngModel)]="lot.areaM2" required min="1" step="0.01" /></label>
          <label class="field"><span>Région</span><input name="r" [(ngModel)]="lot.region" required /></label>
          <label class="field"><span>Département</span><input name="d" [(ngModel)]="lot.departement" required /></label>
          <label class="field"><span>Commune</span><input name="c" [(ngModel)]="lot.commune" required /></label>
          <label class="field"><span>Localité / quartier</span><input name="l" [(ngModel)]="lot.locality" /></label>
          <label class="field"><span>Latitude</span><input type="number" name="la" [(ngModel)]="lot.latitude" step="any" min="-90" max="90" /></label>
          <label class="field"><span>Longitude</span><input type="number" name="lo" [(ngModel)]="lot.longitude" step="any" min="-180" max="180" /></label>
          <label class="field"><span>Source des coordonnées</span><input name="cs" [(ngModel)]="lot.coordinatesSource" placeholder="Relevé du géomètre, plan cadastral…" /></label>
          <label class="check"><input type="checkbox" name="cv" [(ngModel)]="lot.coordinatesVerified" /> Coordonnées vérifiées par un professionnel</label>
          <label class="field span-2"><span>Description</span><textarea name="de" [(ngModel)]="lot.description"></textarea></label>
          <div class="span-2 form-actions"><button class="btn primary" [disabled]="lf.invalid || busy.on()">Créer le lot (en préparation)</button></div>
        </form>
      </section>
    }

    <section class="card"><div class="card-head"><h2>Projets</h2></div>
      <app-state [loading]="projects.loading()" [error]="projects.error()" [empty]="!projects.data()?.length" emptyText="Aucun projet." (retry)="projects.reload()">
        <ul class="list-rows">@for (p of projects.data(); track p.id) {
          <li><span><strong>{{ p.name }}</strong><br /><small class="muted">{{ p.code }} · {{ p.commune || '—' }}</small></span>
            <span class="row gap-lg"><span class="num">{{ p.lotCount | num }} lot(s)</span><app-badge [value]="p.status" /></span></li>
        }</ul>
      </app-state>
    </section>

    <form class="filters" (ngSubmit)="go(0)">
      <label class="field inline"><span>Recherche</span><input type="search" name="q" [(ngModel)]="q" placeholder="Référence, commune…" /></label>
      <label class="field inline"><span>Projet</span><select name="p" [(ngModel)]="projectId" (ngModelChange)="go(0)"><option [ngValue]="null">Tous</option>
        @for (p of projects.data(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }</select></label>
      <label class="field inline"><span>Statut</span><select name="s" [(ngModel)]="status" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <button class="btn">Rechercher</button>
    </form>
    <div class="row between list-toolbar">
      <p class="muted small">{{ lots.data()?.totalElements ?? 0 }} lot(s)</p>
      <div class="view-toggle" role="group" aria-label="Affichage des lots">
        <button type="button" [attr.aria-pressed]="view() === 'grid'" (click)="setView('grid')"><app-icon name="layout-grid" />Grille</button>
        <button type="button" [attr.aria-pressed]="view() === 'table'" (click)="setView('table')"><app-icon name="list" />Tableau</button>
      </div>
    </div>
    <app-state [loading]="lots.loading()" [error]="lots.error()" [empty]="!lots.data()?.content?.length" emptyText="Aucun lot."
      emptyHint="Modifiez les filtres ou créez un lot pour l’inventaire." emptyIcon="map-pin" (retry)="lots.reload()">
      @if (view() === 'grid') {
        <div class="lot-grid">
          @for (l of lots.data()?.content; track l.id) {
            <a class="lot-card" [routerLink]="['/bureau/lots', l.id]">
              <div class="plan-art"><app-badge [value]="l.status" /><app-icon [name]="l.lotType === 'TERRAIN' ? 'map' : 'building-2'" /></div>
              <div class="lot-body">
                <span class="lot-ref">{{ l.reference }}</span>
                <span class="lot-meta"><span>{{ l.lotType | label }} · {{ l.projectName }}</span></span>
                <span class="lot-meta"><span><app-icon name="map-pin" />{{ l.commune }}{{ l.locality ? ', ' + l.locality : '' }}</span></span>
                <span class="lot-figure">{{ l.areaM2 | num }} <small>m²</small></span>
              </div>
            </a>
          }
        </div>
        <p class="chart-caption">Motif graphique non représentatif : il ne s’agit ni d’une photographie ni d’un plan cadastral.</p>
      } @else {
        <div class="table-wrap"><table>
          <thead><tr><th>Référence</th><th>Projet</th><th>Type</th><th class="num">Surface</th><th>Localisation</th><th>Statut</th></tr></thead>
          <tbody>@for (l of lots.data()?.content; track l.id) {
            <tr><td data-label="Référence"><a [routerLink]="['/bureau/lots', l.id]">{{ l.reference }}</a></td><td data-label="Projet">{{ l.projectName }}</td>
              <td data-label="Type">{{ l.lotType | label }}</td><td data-label="Surface" class="num">{{ l.areaM2 | num }} m²</td>
              <td data-label="Localisation">{{ l.commune }}{{ l.locality ? ', ' + l.locality : '' }}</td><td data-label="Statut"><app-badge [value]="l.status" /></td></tr>
          }</tbody>
        </table></div>
      }
      <app-pager [page]="page()" [totalPages]="lots.data()?.totalPages ?? 0" [total]="lots.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class Lots implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly projects = new Loader<ProjectResponse[]>();
  protected readonly lots = new Loader<Page<LotResponse>>();
  protected readonly page = signal(0);
  protected readonly projectForm = signal(false);
  protected readonly lotForm = signal(false);
  protected readonly view = signal<'grid' | 'table'>(readView());
  protected readonly busy = new Busy();
  protected readonly statuses = LOT_STATUSES;
  protected readonly lotTypes = LOT_TYPES;
  protected readonly projectStatuses = PROJECT_STATUSES;
  protected q = '';
  protected status = '';
  protected projectId: number | null = null;
  protected pr = { code: '', name: '', description: '', region: '', departement: '', commune: '', status: 'ETUDE' };
  protected lot = this.blankLot();

  ngOnInit(): void {
    this.projects.load(() => this.api.get<ProjectResponse[]>('/property/projects'));
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.lots.load(() => this.api.get<Page<LotResponse>>('/property/lots', { q: this.q.trim(), status: this.status, projectId: this.projectId, page: p, size: 25 }));
  }

  setView(v: 'grid' | 'table'): void {
    this.view.set(v);
    try {
      localStorage.setItem(VIEW_KEY, v);
    } catch {
      // Stockage local indisponible : la préférence ne sera simplement pas conservée.
    }
  }

  saveProject(): void {
    this.busy.run(this.api.post('/property/projects', this.pr), () => {
      this.toasts.success('Projet créé.');
      this.projectForm.set(false);
      this.projects.reload();
    }, (e) => this.toasts.error(e));
  }

  saveLot(): void {
    this.busy.run(this.api.post('/property/lots', this.lot), () => {
      this.toasts.success('Lot créé en préparation.');
      this.lotForm.set(false);
      this.lot = this.blankLot();
      this.go(0);
    }, (e) => this.toasts.error(e));
  }

  private blankLot() {
    return { reference: '', projectId: null as number | null, lotType: 'TERRAIN', region: '', departement: '', commune: '', locality: '', areaM2: null as number | null,
      latitude: null as number | null, longitude: null as number | null, coordinatesSource: '', coordinatesVerified: false, description: '' };
  }
}

@Component({
  selector: 'app-lot',
  imports: [FormsModule, PageHeader, StateView, Badge, Kpi, LabelPipe, NumPipe, WhenPipe],
  template: `
    <app-state [loading]="d.loading() && !d.data()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as x) {
        <app-page-header [title]="'Lot ' + x.lot.reference" [subtitle]="x.lot.projectName" back="/bureau/lots" backLabel="Projets et lots" />
        @if (x.lot.demo) { <p class="alert" data-tone="demo">Lot fictif de démonstration.</p> }
        <div class="kpis">
          <app-kpi label="Surface" [value]="(x.lot.areaM2 | num) + ' m²'" />
          <app-kpi label="Type" [value]="x.lot.lotType | label" />
          <app-kpi label="Commune" [value]="x.lot.commune || '—'" [hint]="x.lot.departement ?? ''" />
        </div>
        <section class="card">
          <div class="card-head"><h2>Fiche du lot</h2><app-badge [value]="x.lot.status" /></div>
          <dl class="dl">
            <dt>Statut</dt><dd><app-badge [value]="x.lot.status" /></dd>
            <dt>Type</dt><dd>{{ x.lot.lotType | label }}</dd><dt>Surface</dt><dd>{{ x.lot.areaM2 | num }} m²</dd>
            <dt>Localisation</dt><dd>{{ x.lot.region }} / {{ x.lot.departement }} / {{ x.lot.commune }}{{ x.lot.locality ? ' / ' + x.lot.locality : '' }}</dd>
            <dt>Coordonnées</dt><dd>
              @if (x.lot.latitude !== null && x.lot.latitude !== undefined) {
                {{ x.lot.latitude }}, {{ x.lot.longitude }} —
                <span class="badge" [attr.data-tone]="x.lot.coordinatesVerified ? 'ok' : 'warn'">{{ x.lot.coordinatesVerified ? 'vérifiées' : 'non vérifiées' }}</span>
                @if (x.lot.coordinatesSource) { <small class="muted">({{ x.lot.coordinatesSource }})</small> }
                <br /><a [href]="'https://www.openstreetmap.org/?mlat=' + x.lot.latitude + '&mlon=' + x.lot.longitude + '#map=18/' + x.lot.latitude + '/' + x.lot.longitude" target="_blank" rel="noopener noreferrer">Ouvrir sur OpenStreetMap</a>
              } @else { Non renseignées }</dd>
            @if (x.lot.description) { <dt>Description</dt><dd>{{ x.lot.description }}</dd> }
          </dl>
        </section>
        @if (auth.has('LOT_STATUS_CHANGE')) {
          <section class="card"><h2>Changer le statut</h2>
            <form class="grid-2" (ngSubmit)="changeStatus()" #f="ngForm">
              <label class="field"><span>Nouveau statut</span><select name="s" [(ngModel)]="st.status" required>
                @for (s of manualStatuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
              <label class="field"><span>Motif</span><input name="r" [(ngModel)]="st.reason" required minlength="5" /></label>
              <div class="span-2 form-actions"><button class="btn" [disabled]="f.invalid || busy.on()">Appliquer</button></div>
            </form>
            <p class="muted small">Un lot réservé ou attribué ne peut changer de statut que par la procédure d’attribution (résultat, annulation, contestation).</p>
          </section>
        }
        <section class="card"><h2>Historique</h2>
          <ul class="timeline">@for (h of x.history; track h.id) {
            <li>{{ h.changedAt | when }} : {{ h.fromStatus | label }} → <strong>{{ h.toStatus | label }}</strong> — {{ h.reason }}
              @if (h.sourceType) { <small class="muted">({{ h.sourceType }} n° {{ h.sourceId }})</small> }</li>
          }</ul>
        </section>
      }
    </app-state>
  `,
})
export class LotDetail implements OnInit {
  readonly id = input.required<string>();
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly d = new Loader<{ lot: LotResponse; history: LotHistoryItem[] }>();
  protected readonly busy = new Busy();
  protected readonly manualStatuses = ['EN_PREPARATION', 'DISPONIBLE', 'INDISPONIBLE', 'LITIGIEUX'];
  protected st = { status: 'DISPONIBLE', reason: '' };

  ngOnInit(): void {
    this.d.load(() => forkJoin({ lot: this.api.get<LotResponse>(`/property/lots/${this.id()}`), history: this.api.get<LotHistoryItem[]>(`/property/lots/${this.id()}/history`) }));
  }

  changeStatus(): void {
    this.busy.run(this.api.post(`/property/lots/${this.id()}/status`, this.st), () => {
      this.toasts.success('Statut du lot modifié.');
      this.st = { status: 'DISPONIBLE', reason: '' };
      this.d.reload();
    }, (e) => this.toasts.error(e));
  }
}
