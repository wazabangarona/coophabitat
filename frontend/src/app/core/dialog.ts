import { Injectable, signal } from '@angular/core';

export interface DialogRequest {
  title: string;
  message?: string;
  confirmLabel?: string;
  danger?: boolean;
  /** Si présent, un motif écrit est exigé (il sera tracé dans le journal d'audit). */
  reasonLabel?: string;
  reasonMinLength?: number;
}

interface Pending extends DialogRequest {
  resolve: (v: string | null) => void;
}

/** Fenêtre de confirmation accessible (élément dialog natif), sans alert/confirm bloquants du navigateur. */
@Injectable({ providedIn: 'root' })
export class Dialogs {
  readonly current = signal<Pending | null>(null);

  /** Renvoie le motif saisi (ou une chaîne vide sans motif demandé), ou null si l'utilisateur annule. */
  ask(req: DialogRequest): Promise<string | null> {
    this.current()?.resolve(null);
    return new Promise((resolve) => this.current.set({ ...req, resolve }));
  }

  close(value: string | null): void {
    const c = this.current();
    this.current.set(null);
    c?.resolve(value);
  }
}
