import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';
import { API } from './api';
import { MeResponse, TokenResponse } from './api-types';
import { OfflineSnapshot } from './offline';

/**
 * Session côté navigateur.
 * - Le jeton d'accès (courte durée) est conservé uniquement en mémoire : jamais dans localStorage.
 * - Le jeton de renouvellement est un cookie HttpOnly SameSite=Strict, illisible par le JavaScript.
 * - Les permissions affichées ne servent qu'à adapter l'interface : le serveur vérifie chaque requête.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly snapshot = inject(OfflineSnapshot);

  private token: string | null = null;
  private refreshing$: Observable<boolean> | null = null;
  readonly user = signal<MeResponse | null>(null);
  readonly ready = signal(false);
  readonly loggedIn = computed(() => this.user() !== null);
  readonly isMember = computed(() => this.user()?.memberId != null);
  readonly isBureau = computed(() => this.has('DASHBOARD_BUREAU'));

  accessToken(): string | null {
    return this.token;
  }

  has(permission: string): boolean {
    return this.user()?.permissions?.includes(permission) ?? false;
  }

  hasAny(...permissions: string[]): boolean {
    return permissions.some((p) => this.has(p));
  }

  login(email: string, password: string): Observable<MeResponse> {
    return this.http
      .post<TokenResponse>(`${API}/auth/login`, { email, password })
      .pipe(map((r) => this.accept(r)));
  }

  /** Restaure la session au démarrage à partir du cookie de renouvellement, s'il existe. */
  restore(): Observable<boolean> {
    return this.refresh().pipe(finalize(() => this.ready.set(true)));
  }

  /** Un seul renouvellement à la fois, partagé entre les requêtes concurrentes. */
  refresh(): Observable<boolean> {
    if (!this.refreshing$) {
      this.refreshing$ = this.http.post<TokenResponse>(`${API}/auth/refresh`, {}).pipe(
        map((r) => {
          this.accept(r);
          return true;
        }),
        catchError(() => {
          this.clear();
          return of(false);
        }),
        finalize(() => (this.refreshing$ = null)),
        shareReplay(1),
      );
    }
    return this.refreshing$;
  }

  logout(everywhere = false): void {
    const done = () => {
      this.clear();
      void this.router.navigateByUrl('/connexion');
    };
    this.http
      .post(`${API}/auth/${everywhere ? 'logout-all' : 'logout'}`, {})
      .pipe(catchError(() => of(null)))
      .subscribe(done);
  }

  /** Appelé lorsque le serveur refuse définitivement la session (jeton révoqué, compte désactivé). */
  expire(): void {
    this.clear();
    void this.router.navigate(['/connexion'], { queryParams: { session: 'expiree' } });
  }

  reloadProfile(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${API}/auth/me`).pipe(tap((u) => this.user.set(u)));
  }

  homeUrl(): string {
    if (this.isBureau()) return '/bureau';
    if (this.isMember()) return '/espace';
    if (this.has('USER_ADMIN')) return '/bureau/administration';
    return '/';
  }

  private accept(r: TokenResponse): MeResponse {
    this.token = r.accessToken ?? null;
    const u = r.user ?? null;
    const previous = this.user();
    if (previous && u && previous.id !== u.id) this.snapshot.clear();
    this.user.set(u);
    return u as MeResponse;
  }

  private clear(): void {
    this.token = null;
    this.user.set(null);
    // Les données personnelles conservées pour la consultation hors ligne sont effacées à la déconnexion.
    this.snapshot.clear();
    if (typeof navigator !== 'undefined') navigator.serviceWorker?.controller?.postMessage('vider-caches');
  }
}
