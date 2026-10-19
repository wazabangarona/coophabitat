import { Injectable, signal } from '@angular/core';
import { MemberFinancialSummary, ObligationResponse } from './api-types';

const KEY = 'coophabitat.copie-locale.v1';
const OPT_IN = 'coophabitat.copie-locale.accord';

export interface Snapshot {
  savedAt: string;
  userId: number;
  summary: MemberFinancialSummary;
  obligations: ObligationResponse[];
}

/**
 * Mode hors ligne partiel.
 * Le service worker ne met en cache que l'application et les informations publiques. Une copie de la
 * situation personnelle n'est conservée sur l'appareil que si le membre l'a demandé ; elle est effacée à la
 * déconnexion et toujours affichée comme une copie datée, jamais comme une situation financière à jour.
 */
@Injectable({ providedIn: 'root' })
export class OfflineSnapshot {
  readonly online = signal(typeof navigator === 'undefined' ? true : navigator.onLine);

  constructor() {
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => this.online.set(true));
      window.addEventListener('offline', () => this.online.set(false));
    }
  }

  enabled(): boolean {
    return read(OPT_IN) === 'oui';
  }

  setEnabled(on: boolean): void {
    if (on) write(OPT_IN, 'oui');
    else {
      remove(OPT_IN);
      this.clear();
    }
  }

  save(userId: number, summary: MemberFinancialSummary, obligations: ObligationResponse[]): void {
    if (!this.enabled()) return;
    const s: Snapshot = { savedAt: new Date().toISOString(), userId, summary, obligations };
    write(KEY, JSON.stringify(s));
  }

  load(userId: number): Snapshot | null {
    try {
      const raw = read(KEY);
      if (!raw) return null;
      const s = JSON.parse(raw) as Snapshot;
      return s.userId === userId ? s : null;
    } catch {
      return null;
    }
  }

  clear(): void {
    remove(KEY);
  }
}

function read(k: string): string | null {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
}
function write(k: string, v: string): void {
  try {
    localStorage.setItem(k, v);
  } catch {
    /* stockage indisponible : la copie locale est simplement ignorée */
  }
}
function remove(k: string): void {
  try {
    localStorage.removeItem(k);
  } catch {
    /* rien à faire */
  }
}
