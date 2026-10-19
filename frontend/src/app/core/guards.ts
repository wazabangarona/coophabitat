import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

/** Les gardes n'améliorent que la navigation : l'autorisation réelle est vérifiée par le serveur. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  return auth.loggedIn()
    ? true
    : inject(Router).createUrlTree(['/connexion'], { queryParams: { retour: state.url } });
};

export const memberGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  return auth.isMember() ? true : inject(Router).parseUrl(auth.homeUrl());
};

export function permissionGuard(...permissions: string[]): CanActivateFn {
  return () => {
    const auth = inject(Auth);
    return auth.hasAny(...permissions) ? true : inject(Router).parseUrl('/acces-refuse');
  };
}

export const guestGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  return auth.loggedIn() ? inject(Router).parseUrl(auth.homeUrl()) : true;
};
