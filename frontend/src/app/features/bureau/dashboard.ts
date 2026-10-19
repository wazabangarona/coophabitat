import { NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { Dashboard, Indicator, Section } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { ROLE_LABELS } from '../../core/labels';
import { Loader } from '../../core/loader';
import { Distribution, DistributionItem, Icon, Meter, PageHeader, StateView } from '../../ui/components';
import { DayPipe, WhenPipe, formatMoney } from '../../ui/pipes';

const NATURE: Record<string, string> = {
  REEL: 'Réel', DECLARE: 'Déclaré', SIMULATION: 'Simulation', EN_ATTENTE: 'En attente de confirmation', COMPTAGE: '',
};

/** Couleur de chaque statut dans les répartitions (même signification que les pastilles). */
const STATUS_TONE: Record<string, string> = {
  actif: 'ok', en_attente: 'warn', suspendu: 'bad', radie: 'neutral',
  en_preparation: 'neutral', disponible: 'ok', reserve: 'warn', attribue: 'info', litigieux: 'bad', indisponible: 'neutral',
};

interface QuickLink { path: string; label: string; icon: string; perms: string[] }

/** Accès rapides propres à chaque fonction du bureau, filtrés ensuite par les permissions réelles. */
const QUICK: Record<string, QuickLink[]> = {
  PRESIDENT: [
    { path: '/bureau/attributions', label: 'Procédures d’attribution', icon: 'key-round', perms: ['ALLOCATION_READ_ALL'] },
    { path: '/bureau/assemblees', label: 'Assemblées générales', icon: 'vote', perms: ['ASSEMBLY_MANAGE'] },
    { path: '/bureau/documents', label: 'Publications', icon: 'folder-open', perms: ['DOCUMENT_MANAGE', 'DOCUMENT_READ_BUREAU'] },
    { path: '/bureau/audit', label: 'Journal d’audit', icon: 'scroll-text', perms: ['AUDIT_READ'] },
  ],
  TRESORIER: [
    { path: '/bureau/paiements', label: 'Paiements à vérifier', icon: 'credit-card', perms: ['PAYMENT_READ_ALL'] },
    { path: '/bureau/finances/depenses', label: 'Dépenses', icon: 'banknote', perms: ['EXPENSE_CREATE', 'ACCOUNTING_READ'] },
    { path: '/bureau/finances/rapprochement', label: 'Rapprochement bancaire', icon: 'landmark', perms: ['ACCOUNTING_READ'] },
    { path: '/bureau/finances/rapports', label: 'Rapports financiers', icon: 'file-check', perms: ['ACCOUNTING_READ'] },
  ],
  SECRETAIRE: [
    { path: '/bureau/assemblees', label: 'Assemblées et convocations', icon: 'vote', perms: ['ASSEMBLY_MANAGE'] },
    { path: '/bureau/documents', label: 'Documents et procès-verbaux', icon: 'folder-open', perms: ['DOCUMENT_MANAGE', 'DOCUMENT_READ_BUREAU'] },
    { path: '/bureau/membres', label: 'Dossiers des membres', icon: 'users', perms: ['MEMBER_READ_ALL'] },
    { path: '/bureau/notifications', label: 'Notifications', icon: 'bell', perms: ['NOTIFICATION_MANAGE'] },
  ],
};

/** Ordre de lecture des sections selon la fonction : chacun voit d'abord ce dont il répond. */
const ORDER: Record<string, string[]> = {
  TRESORIER: ['cotisations', 'tresorerie', 'membres', 'patrimoine', 'gouvernance'],
  SECRETAIRE: ['gouvernance', 'membres', 'patrimoine', 'cotisations', 'tresorerie'],
  PRESIDENT: ['cotisations', 'patrimoine', 'gouvernance', 'tresorerie', 'membres'],
};

@Component({
  selector: 'app-bureau-dashboard',
  imports: [NgTemplateOutlet, RouterLink, PageHeader, StateView, Icon, Meter, Distribution, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Tableau de bord" [eyebrow]="roleLabel()"
      subtitle="Indicateurs calculés par le serveur, limités à ce que votre rôle permet de voir.">
      <button type="button" class="btn small" (click)="d.reload()" [disabled]="d.loading()"><app-icon name="refresh-cw" />Actualiser</button>
    </app-page-header>

    @if (quick().length) {
      <nav class="quick-links" aria-label="Accès rapides">
        @for (q of quick(); track q.path) {
          <a [routerLink]="q.path"><span class="ql-icon"><app-icon [name]="q.icon" /></span>{{ q.label }}<app-icon name="chevron-right" /></a>
        }
      </nav>
    }

    <app-state [loading]="d.loading() && !d.data()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as db) {
        @for (n of db.notices; track $index) {
          <p class="alert" [attr.data-tone]="n.includes('SIMULATION') ? 'sim' : n.startsWith('Écart') ? 'warn' : 'info'">{{ n }}</p>
        }
        <div class="dash-grid">
          @for (s of sections(); track s.key) {
            <section class="card dash-section" [class.wide]="s.key === 'cotisations' || s.key === 'tresorerie'" [attr.aria-labelledby]="'sec-' + s.key">
              <div class="card-head"><h2 id="sec-{{ s.key }}">{{ s.title }}</h2>
                @if (link(s.key ?? ''); as l) { <a [routerLink]="l.path">{{ l.label }}<app-icon name="arrow-right" /></a> }</div>

              @switch (s.key) {
                @case ('cotisations') {
                  @if (find(s, 'taux_recouvrement'); as rate) {
                    @if (rate.value !== null && rate.value !== undefined) {
                      <app-meter [value]="$any(rate.value)" [label]="rate.label ?? ''" />
                      <p class="chart-caption">Réglé {{ value(find(s, 'recouvre_mois')) }} sur {{ value(find(s, 'attendu_mois')) }} attendus pour les échéances du mois.</p>
                    } @else {
                      <p class="muted small">{{ rate.label }} : aucune échéance n’est attendue ce mois-ci.</p>
                    }
                  }
                  <div class="kpis inset">
                    @for (i of others(s, ['taux_recouvrement']); track i.key) { <ng-container *ngTemplateOutlet="kpi; context: { $implicit: i }" /> }
                  </div>
                }
                @case ('membres') {
                  <app-distribution [items]="dist(s)" />
                  <p class="chart-caption">Effectif par statut actuel du dossier, au {{ db.asOf | day }}.</p>
                }
                @case ('patrimoine') {
                  <app-distribution [items]="dist(s)" />
                  <p class="chart-caption">Nombre de lots par statut, au {{ db.asOf | day }}.</p>
                  <div class="kpis inset">
                    @for (i of others(s, statusKeys(s)); track i.key) { <ng-container *ngTemplateOutlet="kpi; context: { $implicit: i }" /> }
                  </div>
                }
                @case ('gouvernance') {
                  <ul class="list-rows">
                    @for (i of s.indicators; track i.key) {
                      <li><span class="muted small">{{ i.label }}</span><strong class="num">{{ value(i) }}</strong></li>
                    }
                  </ul>
                }
                @default {
                  <div class="kpis inset">
                    @for (i of s.indicators; track i.key) { <ng-container *ngTemplateOutlet="kpi; context: { $implicit: i }" /> }
                  </div>
                }
              }

              <details class="defs" (toggle)="toggleDefs(s.key ?? '', $event)"><summary>Définitions</summary>
                @if (openDefs().has(s.key ?? '')) {
                  <dl class="dl">@for (i of s.indicators; track i.key) { <dt>{{ i.label }}</dt><dd>{{ i.definition }}</dd> }</dl>
                }
              </details>
            </section>
          }
        </div>
        <p class="muted small">Situation au {{ db.asOf | day }}, calculée le {{ db.computedAt | when }}.</p>
      }
    </app-state>

    <ng-template #kpi let-i>
      <div class="kpi" [attr.data-tone]="tone(i)" [title]="i.definition ?? ''">
        <span class="kpi-label">{{ i.label }}</span>
        <strong class="kpi-value">{{ value(i) }}</strong>
        @if (nature(i)) { <span class="kpi-hint">{{ nature(i) }}</span> }
      </div>
    </ng-template>
  `,
})
export class BureauDashboard implements OnInit {
  private readonly api = inject(Api);
  private readonly auth = inject(Auth);
  protected readonly d = new Loader<Dashboard>();
  /** Définitions affichées à la demande, pour ne pas dupliquer chaque libellé dans la page. */
  protected readonly openDefs = signal(new Set<string>());

  private readonly role = computed(() => {
    const roles = this.auth.user()?.roles ?? [];
    return ['PRESIDENT', 'TRESORIER', 'SECRETAIRE'].find((r) => roles.includes(r)) ?? roles[0] ?? '';
  });
  protected readonly roleLabel = computed(() => (this.role() ? `Espace ${(ROLE_LABELS[this.role()] ?? this.role()).toLowerCase()}` : ''));
  protected readonly quick = computed(() => (QUICK[this.role()] ?? []).filter((q) => this.auth.hasAny(...q.perms)));
  protected readonly sections = computed(() => {
    const list = this.d.data()?.sections ?? [];
    const order = ORDER[this.role()];
    if (!order) return list;
    return [...list].sort((a, b) => order.indexOf(a.key ?? '') - order.indexOf(b.key ?? ''));
  });

  ngOnInit(): void {
    this.d.load(() => this.api.get<Dashboard>('/dashboard/bureau'));
  }

  protected toggleDefs(key: string, e: Event): void {
    const open = (e.target as HTMLDetailsElement).open;
    this.openDefs.update((set) => {
      const next = new Set(set);
      if (open) next.add(key);
      else next.delete(key);
      return next;
    });
  }

  protected find(s: Section, key: string): Indicator | undefined {
    return s.indicators?.find((i) => i.key === key);
  }

  protected others(s: Section, exclude: string[]): Indicator[] {
    return (s.indicators ?? []).filter((i) => !exclude.includes(i.key ?? ''));
  }

  protected statusKeys(s: Section): string[] {
    return (s.indicators ?? []).filter((i) => i.unit === 'lots' || i.unit === 'membres').map((i) => i.key ?? '');
  }

  protected dist(s: Section): DistributionItem[] {
    const prefix = s.key === 'membres' ? 'membres_' : 'lots_';
    return (s.indicators ?? [])
      .filter((i) => i.key?.startsWith(prefix) && typeof i.value === 'number')
      .map((i) => ({ label: i.label ?? '', value: i.value as number, tone: STATUS_TONE[(i.key ?? '').slice(prefix.length)] ?? 'neutral' }));
  }

  protected link(key: string): { path: string; label: string } | null {
    const links: Record<string, { path: string; label: string; perms: string[] }> = {
      membres: { path: '/bureau/membres', label: 'Membres', perms: ['MEMBER_READ_ALL'] },
      cotisations: { path: '/bureau/paiements', label: 'Paiements', perms: ['PAYMENT_READ_ALL'] },
      tresorerie: { path: '/bureau/finances', label: 'Finances', perms: ['ACCOUNTING_READ'] },
      patrimoine: { path: '/bureau/lots', label: 'Projets et lots', perms: ['LOT_READ_ALL'] },
      gouvernance: { path: '/bureau/assemblees', label: 'Assemblées', perms: ['ASSEMBLY_MANAGE'] },
    };
    const l = links[key];
    return l && this.auth.hasAny(...l.perms) ? l : null;
  }

  value(i: Indicator | undefined): string {
    if (!i || i.value === null || i.value === undefined) return '—';
    if (i.unit === 'XOF') return formatMoney(i.value as number);
    if (i.unit === '%') return `${i.value} %`;
    return typeof i.value === 'number' ? `${i.value}` : String(i.value);
  }

  nature(i: Indicator): string {
    return NATURE[i.nature ?? ''] ?? '';
  }

  tone(i: Indicator): string {
    if (i.nature === 'SIMULATION') return 'sim';
    if (i.nature === 'EN_ATTENTE') return 'warn';
    if (i.nature === 'DECLARE') return 'info';
    return 'neutral';
  }
}
