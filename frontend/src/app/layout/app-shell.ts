import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Api } from '../core/api';
import { Auth } from '../core/auth';
import { ROLE_LABELS } from '../core/labels';
import { PublicInfoStore } from '../core/public-info';
import { Banners } from './banners';

interface NavItem {
  path: string;
  label: string;
  perms?: string[];
  exact?: boolean;
}

const MEMBER_NAV: NavItem[] = [
  { path: '/espace', label: 'Ma situation', exact: true },
  { path: '/espace/cotisations', label: 'Mes cotisations' },
  { path: '/espace/paiements', label: 'Paiements et reçus' },
  { path: '/espace/attributions', label: 'Lots et attributions' },
  { path: '/espace/assemblees', label: 'Assemblées' },
  { path: '/espace/transparence', label: 'Transparence financière' },
  { path: '/espace/documents', label: 'Documents' },
];

const BUREAU_NAV: NavItem[] = [
  { path: '/bureau', label: 'Tableau de bord', perms: ['DASHBOARD_BUREAU'], exact: true },
  { path: '/bureau/membres', label: 'Membres', perms: ['MEMBER_READ_ALL'] },
  { path: '/bureau/cotisations', label: 'Cotisations', perms: ['PLAN_READ'] },
  { path: '/bureau/paiements', label: 'Paiements', perms: ['PAYMENT_READ_ALL'] },
  { path: '/bureau/lots', label: 'Projets et lots', perms: ['LOT_READ_ALL'] },
  { path: '/bureau/attributions', label: 'Attributions', perms: ['ALLOCATION_READ_ALL'] },
  { path: '/bureau/assemblees', label: 'Assemblées', perms: ['ASSEMBLY_MANAGE'] },
  { path: '/bureau/finances', label: 'Finances', perms: ['ACCOUNTING_READ', 'EXPENSE_CREATE'] },
  { path: '/bureau/documents', label: 'Documents', perms: ['DOCUMENT_MANAGE', 'DOCUMENT_READ_BUREAU'] },
  { path: '/bureau/notifications', label: 'Notifications', perms: ['NOTIFICATION_MANAGE'] },
  { path: '/bureau/audit', label: 'Journal d’audit', perms: ['AUDIT_READ'] },
  { path: '/bureau/administration', label: 'Administration', perms: ['USER_ADMIN', 'SETTINGS_ADMIN', 'DATA_REQUEST_HANDLE'] },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Banners],
  template: `
    <a class="skip" href="#contenu">Aller au contenu</a>
    <app-banners />
    <div class="shell" [class.nav-open]="navOpen()">
      <aside class="sidebar" aria-label="Menu de l’application">
        <a routerLink="/" class="brand"><span class="brand-mark" aria-hidden="true">⌂</span><span>{{ info.name() }}</span></a>
        <nav>
          @if (memberNav().length) {
            <p class="nav-title">Mon espace</p>
            @for (n of memberNav(); track n.path) {
              <a [routerLink]="n.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!n.exact }">{{ n.label }}</a>
            }
          }
          @if (bureauNav().length) {
            <p class="nav-title">Gestion</p>
            @for (n of bureauNav(); track n.path) {
              <a [routerLink]="n.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!n.exact }">{{ n.label }}</a>
            }
          }
        </nav>
      </aside>
      <div class="shell-main">
        <header class="topbar">
          <button type="button" class="icon-btn menu-toggle" [attr.aria-expanded]="navOpen()" (click)="navOpen.set(!navOpen())">
            ☰<span class="sr">Menu</span>
          </button>
          <span class="grow"></span>
          <a routerLink="/compte/notifications" class="notif" [attr.aria-label]="'Notifications : ' + unread() + ' non lues'">
            Notifications @if (unread() > 0) { <span class="count">{{ unread() }}</span> }
          </a>
          <a routerLink="/compte" class="user">
            <strong>{{ auth.user()?.displayName }}</strong>
            <small>{{ roles() }}</small>
          </a>
          <button type="button" class="btn small" (click)="auth.logout()">Se déconnecter</button>
        </header>
        <main id="contenu" class="content" tabindex="-1"><router-outlet /></main>
      </div>
    </div>
  `,
})
export class AppShell implements OnInit, OnDestroy {
  protected readonly auth = inject(Auth);
  protected readonly info = inject(PublicInfoStore);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly navOpen = signal(false);
  protected readonly unread = signal(0);
  private timer?: ReturnType<typeof setInterval>;

  protected readonly memberNav = computed(() => (this.auth.isMember() ? MEMBER_NAV : []));
  protected readonly bureauNav = computed(() => {
    this.auth.user();
    return BUREAU_NAV.filter((n) => !n.perms || this.auth.hasAny(...n.perms));
  });
  protected readonly roles = computed(() =>
    (this.auth.user()?.roles ?? []).map((r) => ROLE_LABELS[r] ?? r).join(', '),
  );

  ngOnInit(): void {
    this.refreshUnread();
    this.timer = setInterval(() => this.refreshUnread(), 60_000);
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.navOpen.set(false);
      this.refreshUnread();
    });
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private refreshUnread(): void {
    if (!navigator.onLine || !this.auth.loggedIn()) return;
    this.api.get<{ unread?: number; count?: number }>('/notifications/inbox/unread-count').subscribe({
      next: (r) => this.unread.set(r.unread ?? r.count ?? 0),
      error: () => undefined,
    });
  }
}
