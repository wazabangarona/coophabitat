import { signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { errorMessage } from './errors';

/** État de chargement d'une ressource, exposé sous forme de signaux. */
export class Loader<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private sub?: Subscription;
  private last?: () => Observable<T>;

  constructor(
    private readonly onLoaded?: (v: T) => void,
    private readonly onFailed?: (e: unknown) => void,
  ) {}

  load(source: () => Observable<T>): void {
    this.last = source;
    this.sub?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.sub = source().subscribe({
      next: (v) => {
        this.data.set(v);
        this.loading.set(false);
        this.onLoaded?.(v);
      },
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
        this.onFailed?.(e);
      },
    });
  }

  reload(): void {
    if (this.last) this.load(this.last);
  }
}

/** Exécute une action avec indicateur d'occupation, pour désactiver le bouton pendant l'appel. */
export class Busy {
  readonly on = signal(false);

  run<T>(source: Observable<T>, next: (v: T) => void, fail: (e: unknown) => void): void {
    if (this.on()) return;
    this.on.set(true);
    source.subscribe({
      next: (v) => {
        this.on.set(false);
        next(v);
      },
      error: (e: unknown) => {
        this.on.set(false);
        fail(e);
      },
    });
  }
}
