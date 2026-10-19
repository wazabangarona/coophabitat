import { Component, inject } from '@angular/core';
import { OfflineSnapshot } from '../core/offline';
import { PublicInfoStore, SIMULATION_NOTICE } from '../core/public-info';

/** Bandeaux permanents : données fictives, simulation de paiement, absence de connexion. */
@Component({
  selector: 'app-banners',
  template: `
    @if (info.demo()) {
      <div class="banner" data-tone="demo" role="note">
        <strong>Démonstration</strong> — toutes les personnes, montants et documents affichés sont fictifs.
      </div>
    }
    @if (info.simulation()) {
      <div class="banner" data-tone="sim" role="note">
        <strong>{{ notice }}</strong> — le simulateur de paiement est actif dans cet environnement.
      </div>
    }
    @if (!offline.online()) {
      <div class="banner" data-tone="offline" role="status">
        <strong>Hors ligne</strong> — les informations affichées peuvent être anciennes ; aucun paiement ni
        modification n’est possible.
      </div>
    }
  `,
})
export class Banners {
  protected readonly info = inject(PublicInfoStore);
  protected readonly offline = inject(OfflineSnapshot);
  protected readonly notice = SIMULATION_NOTICE;
}
