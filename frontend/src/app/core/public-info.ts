import { Injectable, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { PublicInfo } from './api-types';

export const ESCROW_DEFAULT = 'Fonds suivis comptablement — séquestre externe non vérifié ou non intégré';
export const SIMULATION_NOTICE = 'SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ';

/** Informations publiques de la coopérative et état de l'environnement (démonstration, simulation). */
@Injectable({ providedIn: 'root' })
export class PublicInfoStore {
  private readonly api = inject(Api);
  readonly info = signal<PublicInfo | null>(null);
  readonly demo = computed(() => this.info()?.demoData === true);
  readonly simulation = computed(() => this.info()?.paymentSimulationEnabled === true);
  readonly escrow = computed(() => this.info()?.escrowStatement || ESCROW_DEFAULT);
  readonly name = computed(() => this.info()?.name || 'CoopHabitat');

  load(): void {
    this.api.get<PublicInfo>('/public/info').subscribe({
      next: (i) => this.info.set(i),
      error: () => this.info.set(null),
    });
  }
}
