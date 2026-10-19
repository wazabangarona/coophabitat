import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../../core/auth';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `<section class="card narrow"><h1>Page introuvable</h1><p>L’adresse demandée n’existe pas.</p>
    <a class="btn primary" routerLink="/">Retour à l’accueil</a></section>`,
})
export class NotFound {}

@Component({
  selector: 'app-access-denied',
  imports: [RouterLink],
  template: `<section class="card narrow"><h1>Accès refusé</h1>
    <p>Votre rôle ne donne pas accès à cette page. Si vous pensez qu’il s’agit d’une erreur, contactez le bureau.</p>
    <a class="btn primary" [routerLink]="auth.homeUrl()">Retour</a></section>`,
})
export class AccessDenied {
  protected readonly auth = inject(Auth);
}
