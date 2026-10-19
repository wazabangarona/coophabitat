import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, expand, map, reduce } from 'rxjs';

export const API = '/api/v1';

export type Query = Record<string, string | number | boolean | null | undefined>;

function params(q?: Query): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(q ?? {})) {
    if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
  }
  return p;
}

/** Accès typé à l'API REST. Aucun secret n'est présent côté navigateur : seul le jeton d'accès de session transite. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  get<T>(path: string, query?: Query): Observable<T> {
    return this.http.get<T>(API + path, { params: params(query) });
  }

  post<T>(path: string, body: unknown = {}, query?: Query): Observable<T> {
    return this.http.post<T>(API + path, body, { params: params(query) });
  }

  /** POST protégé contre la double soumission : le serveur rejoue la réponse d'une même clé au lieu de recréer l'opération. */
  postOnce<T>(path: string, body: unknown, idempotencyKey: string): Observable<T> {
    return this.http.post<T>(API + path, body, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  put<T>(path: string, body: unknown = {}, query?: Query): Observable<T> {
    return this.http.put<T>(API + path, body, { params: params(query) });
  }

  /** Parcourt toutes les pages d'une liste paginée (taille de page plafonnée côté serveur). */
  getAll<T>(path: string, query?: Query): Observable<T[]> {
    const page = (n: number) => this.get<{ content?: T[]; totalPages?: number }>(path, { ...query, page: n, size: 100 }).pipe(map((r) => ({ r, n })));
    return page(0).pipe(
      expand(({ r, n }) => (n + 1 < (r.totalPages ?? 0) && n < 50 ? page(n + 1) : EMPTY)),
      reduce((acc, { r }) => acc.concat(r.content ?? []), [] as T[]),
    );
  }

  upload<T>(path: string, form: FormData, query?: Query): Observable<T> {
    return this.http.post<T>(API + path, form, { params: params(query) });
  }

  /** Télécharge un fichier protégé (le jeton est ajouté par l'intercepteur, jamais placé dans l'URL). */
  download(path: string, query?: Query): Observable<{ blob: Blob; filename: string }> {
    return this.http
      .get(API + path, { params: params(query), responseType: 'blob', observe: 'response' })
      .pipe(map((r: HttpResponse<Blob>) => ({ blob: r.body as Blob, filename: filenameOf(r) })));
  }
}

function filenameOf(r: HttpResponse<Blob>): string {
  const cd = r.headers.get('Content-Disposition') ?? '';
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1] : 'document';
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function openBlob(blob: Blob): void {
  const url = URL.createObjectURL(blob);
  window.open(url, '_blank', 'noopener');
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
