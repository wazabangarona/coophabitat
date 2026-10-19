import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { API } from './api';
import { Auth } from './auth';

const NO_RETRY = [`${API}/auth/login`, `${API}/auth/refresh`, `${API}/auth/logout`];

/**
 * Ajoute le jeton d'accès et l'en-tête anti-CSRF aux appels de l'API ; renouvelle une fois la session
 * sur une réponse 401 puis rejoue la requête.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API)) return next(req);
  const auth = inject(Auth);
  const decorate = (r: HttpRequest<unknown>) => {
    const token = auth.accessToken();
    return r.clone({
      withCredentials: r.url.startsWith(`${API}/auth/`),
      setHeaders: {
        'X-Requested-With': 'CoopHabitat',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
  };
  return next(decorate(req)).pipe(
    catchError((err: unknown) => {
      const retryable =
        err instanceof HttpErrorResponse &&
        err.status === 401 &&
        auth.accessToken() !== null &&
        !NO_RETRY.some((u) => req.url.startsWith(u));
      if (!retryable) return throwError(() => err);
      return auth.refresh().pipe(
        switchMap((ok) => {
          if (!ok) {
            auth.expire();
            return throwError(() => err);
          }
          return next(decorate(req));
        }),
      );
    }),
  );
};
