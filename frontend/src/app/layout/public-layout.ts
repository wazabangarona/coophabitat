import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Auth } from '../core/auth';
import { PublicInfoStore } from '../core/public-info';
import { Banners } from './banners';

@Component({
  selector: 'app-public-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Banners],
  template: `
    <a class="skip" href="#contenu">Aller au contenu</a>
    <app-banners />
    <header class="site-header">
      <a routerLink="/" class="brand" aria-label="Accueil">
        <span class="brand-mark" aria-hidden="true">⌂</span>
        <span>{{ info.name() }}</span>
      </a>
      <button type="button" class="icon-btn menu-toggle" [attr.aria-expanded]="open()" aria-controls="nav-public"
        (click)="open.set(!open())">☰<span class="sr">Menu</span></button>
      <nav id="nav-public" [class.open]="open()" aria-label="Navigation principale">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Accueil</a>
        <a routerLink="/projets" routerLinkActive="active">Projets</a>
        <a routerLink="/documents-publics" routerLinkActive="active">Documents</a>
        <a routerLink="/verification-recu" routerLinkActive="active">Vérifier un reçu</a>
        @if (auth.loggedIn()) {
          <a class="btn primary small" [routerLink]="auth.homeUrl()">Mon espace</a>
        } @else {
          <a class="btn primary small" routerLink="/connexion">Se connecter</a>
        }
      </nav>
    </header>
    <main id="contenu" class="site-main" tabindex="-1"><router-outlet /></main>
    <footer class="site-footer">
      <p><strong>{{ info.name() }}</strong>
        @if (info.info()?.city) { — {{ info.info()?.city }} }
      </p>
      <p class="muted">{{ info.escrow() }}</p>
      <p class="muted small">CoopHabitat — logiciel de gestion coopérative. Les montants sont exprimés en francs CFA (XOF).</p>
    </footer>
  `,
})
export class PublicLayout {
  protected readonly info = inject(PublicInfoStore);
  protected readonly auth = inject(Auth);
  protected readonly open = signal(false);

  constructor() {
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed()).subscribe(() => this.open.set(false));
  }
}
