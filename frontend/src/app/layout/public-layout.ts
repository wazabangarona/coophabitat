import { Component, HostListener, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Auth } from '../core/auth';
import { PublicInfoStore } from '../core/public-info';
import { BrandMark, Icon } from '../ui/components';
import { Banners } from './banners';

@Component({
  selector: 'app-public-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Banners, BrandMark, Icon],
  template: `
    <a class="skip" href="#contenu">Aller au contenu</a>
    <app-banners />
    <header class="site-header" [class.scrolled]="scrolled()">
      <div class="site-header-inner">
        <a routerLink="/" class="brand" aria-label="Accueil">
          <app-brand-mark />
          <span class="brand-text"><span class="brand-name">{{ info.name() }}</span>
            @if (info.info()?.city) { <span class="brand-sub">{{ info.info()?.city }}</span> }</span>
        </a>
        <button type="button" class="icon-btn menu-toggle" [attr.aria-expanded]="open()" aria-controls="nav-public" (click)="open.set(!open())">
          <app-icon [name]="open() ? 'x' : 'menu'" /><span class="sr">Menu</span>
        </button>
        <nav id="nav-public" [class.open]="open()" aria-label="Navigation principale">
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Accueil</a>
          <a routerLink="/projets" routerLinkActive="active" ariaCurrentWhenActive="page">Projets</a>
          <a routerLink="/documents-publics" routerLinkActive="active" ariaCurrentWhenActive="page">Documents</a>
          <a routerLink="/verification-recu" routerLinkActive="active" ariaCurrentWhenActive="page">Vérifier un reçu</a>
          <span class="nav-actions">
            @if (auth.loggedIn()) {
              <a class="btn primary small" [routerLink]="auth.homeUrl()">Mon espace<app-icon name="arrow-right" /></a>
            } @else {
              <a class="btn small" routerLink="/connexion">Se connecter</a>
              @if (info.registrationOpen()) { <a class="btn primary small" routerLink="/inscription">Adhérer</a> }
            }
          </span>
        </nav>
      </div>
    </header>
    <main id="contenu" class="site-main" tabindex="-1"><router-outlet /></main>
    <footer class="site-footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <a routerLink="/" class="brand"><app-brand-mark /><span class="brand-text"><span class="brand-name">{{ info.name() }}</span>
            @if (info.info()?.city) { <span class="brand-sub">{{ info.info()?.city }}</span> }</span></a>
          <p>{{ info.escrow() }}</p>
        </div>
        <div class="footer-cols">
          <div>
            <h2>Coopérative</h2>
            <ul>
              <li><a routerLink="/projets">Projets</a></li>
              <li><a routerLink="/documents-publics">Documents publics</a></li>
              <li><a routerLink="/verification-recu">Vérifier un reçu</a></li>
            </ul>
          </div>
          <div>
            <h2>Espace membre</h2>
            <ul>
              <li><a routerLink="/connexion">Connexion</a></li>
              <li><a routerLink="/mot-de-passe-oublie">Mot de passe oublié</a></li>
            </ul>
          </div>
          <div>
            <h2>Informations</h2>
            <ul>
              <li><a routerLink="/credits-photographiques">Crédits photographiques</a></li>
            </ul>
          </div>
        </div>
        <div class="footer-legal">
          <p>CoopHabitat — logiciel de gestion coopérative. Les montants sont exprimés en francs CFA (XOF).</p>
          <p>Les photographies du site sont des illustrations et ne représentent pas les projets de la coopérative.</p>
        </div>
      </div>
    </footer>
  `,
})
export class PublicLayout {
  protected readonly info = inject(PublicInfoStore);
  protected readonly auth = inject(Auth);
  protected readonly open = signal(false);
  protected readonly scrolled = signal(false);

  constructor() {
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed()).subscribe(() => this.open.set(false));
  }

  @HostListener('window:scroll')
  protected onScroll(): void {
    this.scrolled.set(window.scrollY > 8);
  }

  @HostListener('document:keydown.escape')
  protected close(): void {
    this.open.set(false);
  }
}
