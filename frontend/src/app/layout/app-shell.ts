import { Component, HostListener, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Api } from '../core/api';
import { Auth } from '../core/auth';
import { ROLE_LABELS } from '../core/labels';
import { PublicInfoStore } from '../core/public-info';
import { BrandMark, Icon } from '../ui/components';
import { Banners } from './banners';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  perms?: string[];
  exact?: boolean;
  /** Libellé court pour la barre de navigation mobile. */
  short?: string;
}

const MEMBER_NAV: NavItem[] = [
  { path: '/espace', label: 'Ma situation', short: 'Situation', icon: 'house', exact: true },
  { path: '/espace/cotisations', label: 'Mes cotisations', short: 'Cotisations', icon: 'piggy-bank' },
  { path: '/espace/paiements', label: 'Paiements et reçus', short: 'Paiements', icon: 'receipt' },
  { path: '/espace/attributions', label: 'Lots et attributions', icon: 'map-pin' },
  { path: '/espace/assemblees', label: 'Assemblées', icon: 'vote' },
  { path: '/espace/transparence', label: 'Transparence financière', icon: 'scale' },
  { path: '/espace/documents', label: 'Documents', short: 'Documents', icon: 'file-text' },
];

const BUREAU_NAV: NavItem[] = [
  { path: '/bureau', label: 'Tableau de bord', icon: 'layout-dashboard', perms: ['DASHBOARD_BUREAU'], exact: true },
  { path: '/bureau/membres', label: 'Membres', icon: 'users', perms: ['MEMBER_READ_ALL'] },
  { path: '/bureau/cotisations', label: 'Cotisations', icon: 'piggy-bank', perms: ['PLAN_READ'] },
  { path: '/bureau/paiements', label: 'Paiements', icon: 'credit-card', perms: ['PAYMENT_READ_ALL'] },
  { path: '/bureau/lots', label: 'Projets et lots', icon: 'building-2', perms: ['LOT_READ_ALL'] },
  { path: '/bureau/attributions', label: 'Attributions', icon: 'key-round', perms: ['ALLOCATION_READ_ALL'] },
  { path: '/bureau/assemblees', label: 'Assemblées', icon: 'vote', perms: ['ASSEMBLY_MANAGE'] },
  { path: '/bureau/finances', label: 'Finances', icon: 'landmark', perms: ['ACCOUNTING_READ', 'EXPENSE_CREATE'] },
  { path: '/bureau/documents', label: 'Documents', icon: 'folder-open', perms: ['DOCUMENT_MANAGE', 'DOCUMENT_READ_BUREAU'] },
  { path: '/bureau/notifications', label: 'Notifications', icon: 'bell', perms: ['NOTIFICATION_MANAGE'] },
  { path: '/bureau/audit', label: 'Journal d’audit', icon: 'scroll-text', perms: ['AUDIT_READ'] },
  { path: '/bureau/administration', label: 'Administration', icon: 'settings', perms: ['USER_ADMIN', 'SETTINGS_ADMIN', 'DATA_REQUEST_HANDLE'] },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Banners, BrandMark, Icon],
  template: `
    <a class="skip" href="#contenu">Aller au contenu</a>
    <app-banners />
    <div class="shell" [class.nav-open]="navOpen()">
      <aside class="sidebar" id="menu-app" aria-label="Menu de l’application">
        <div class="sidebar-head">
          <a routerLink="/" class="brand">
            <app-brand-mark />
            <span class="brand-text"><span class="brand-name">{{ info.name() }}</span><span class="brand-sub">Espace coopératif</span></span>
          </a>
          <button type="button" class="icon-btn" aria-label="Fermer le menu" (click)="navOpen.set(false)"><app-icon name="x" /></button>
        </div>
        <nav>
          @if (memberNav().length) {
            <p class="nav-title">Mon espace</p>
            @for (n of memberNav(); track n.path) {
              <a [routerLink]="n.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!n.exact }" ariaCurrentWhenActive="page">
                <app-icon [name]="n.icon" />{{ n.label }}</a>
            }
          }
          @if (bureauNav().length) {
            <p class="nav-title">Gestion</p>
            @for (n of bureauNav(); track n.path) {
              <a [routerLink]="n.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!n.exact }" ariaCurrentWhenActive="page">
                <app-icon [name]="n.icon" />{{ n.label }}</a>
            }
          }
          <p class="nav-title">Compte</p>
          <a routerLink="/compte" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page"><app-icon name="user" />Mon compte</a>
        </nav>
        <div class="sidebar-foot">
          <p>Montants exprimés en francs CFA (XOF).</p>
          <a routerLink="/verification-recu">Vérifier un reçu</a>
        </div>
      </aside>
      <button type="button" class="scrim" tabindex="-1" aria-hidden="true" (click)="navOpen.set(false)"></button>
      <div class="shell-main">
        <header class="topbar">
          <button type="button" class="icon-btn menu-toggle" aria-controls="menu-app" [attr.aria-expanded]="navOpen()" (click)="navOpen.set(!navOpen())">
            <app-icon name="menu" /><span class="sr">Menu</span>
          </button>
          <div class="topbar-context">
            <a routerLink="/" class="brand" aria-label="Accueil"><app-brand-mark /><span class="brand-text"><span class="brand-name">{{ info.name() }}</span></span></a>
            <span class="crumb">{{ section() }}</span>
          </div>
          <span class="grow"></span>
          <a routerLink="/compte/notifications" class="icon-btn notif" [attr.aria-label]="'Notifications : ' + unread() + ' non lues'">
            <app-icon name="bell" />@if (unread() > 0) { <span class="count" aria-hidden="true">{{ unread() > 99 ? '99+' : unread() }}</span> }
          </a>
          <a routerLink="/compte" class="user-chip" [attr.aria-label]="'Mon compte : ' + (auth.user()?.displayName ?? '')">
            <span class="avatar" aria-hidden="true">{{ initials() }}</span>
            <span class="who"><strong>{{ auth.user()?.displayName }}</strong><small>{{ roles() }}</small></span>
          </a>
          <button type="button" class="icon-btn" aria-label="Se déconnecter" title="Se déconnecter" (click)="auth.logout()"><app-icon name="log-out" /></button>
        </header>
        <main id="contenu" class="content" tabindex="-1"><router-outlet /></main>
      </div>
      @if (memberNav().length) {
        <nav class="bottom-nav" aria-label="Navigation rapide">
          @for (n of quickNav(); track n.path) {
            <a [routerLink]="n.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!n.exact }" ariaCurrentWhenActive="page">
              <app-icon [name]="n.icon" />{{ n.short ?? n.label }}</a>
          }
          <button type="button" aria-controls="menu-app" [attr.aria-expanded]="navOpen()" (click)="navOpen.set(true)"><app-icon name="menu" />Menu</button>
        </nav>
      }
    </div>
  `,
})
export class AppShell implements OnInit, OnDestroy {
  protected readonly auth = inject(Auth);
  protected readonly info = inject(PublicInfoStore);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  protected readonly navOpen = signal(false);
  protected readonly unread = signal(0);
  protected readonly section = signal('');
  private timer?: ReturnType<typeof setInterval>;

  protected readonly memberNav = computed(() => (this.auth.isMember() ? MEMBER_NAV : []));
  protected readonly quickNav = computed(() => this.memberNav().filter((n) => n.short));
  protected readonly bureauNav = computed(() => {
    this.auth.user();
    return BUREAU_NAV.filter((n) => !n.perms || this.auth.hasAny(...n.perms));
  });
  protected readonly roles = computed(() =>
    (this.auth.user()?.roles ?? []).map((r) => ROLE_LABELS[r] ?? r).join(', '),
  );
  protected readonly initials = computed(() => {
    const parts = (this.auth.user()?.displayName ?? '').replace(/\(.*?\)/g, '').trim().split(/\s+/).filter(Boolean);
    return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '·';
  });

  ngOnInit(): void {
    this.refreshUnread();
    this.timer = setInterval(() => this.refreshUnread(), 60_000);
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.navOpen.set(false);
      this.refreshUnread();
      // Le titre de la route (« Page · CoopHabitat ») sert de repère dans la barre supérieure.
      setTimeout(() => this.section.set(this.title.getTitle().split(' · ').slice(0, -1).reverse().join(' · ')));
    });
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  @HostListener('document:keydown.escape')
  protected closeNav(): void {
    this.navOpen.set(false);
  }

  private refreshUnread(): void {
    if (!navigator.onLine || !this.auth.loggedIn()) return;
    this.api.get<{ unread?: number; count?: number }>('/notifications/inbox/unread-count').subscribe({
      next: (r) => this.unread.set(r.unread ?? r.count ?? 0),
      error: () => undefined,
    });
  }
}
