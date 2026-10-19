import { Component } from '@angular/core';
import { PageHeader } from '../../ui/components';
import { PHOTOS } from '../../ui/photos';

/** Sources et licences des photographies d'illustration du site. */
@Component({
  selector: 'app-photo-credits',
  imports: [PageHeader],
  template: `
    <app-page-header title="Crédits photographiques"
      subtitle="Les photographies du site sont des illustrations. Elles ne représentent ni les terrains, ni les logements, ni les chantiers de la coopérative." />
    @if (credits.length) {
      <ul class="doc-list">
        @for (c of credits; track c.source) {
          <li><div class="doc-body"><strong>{{ c.alt }}</strong>
            <p class="muted small">Photographie : {{ c.author }} · {{ c.license }} ·
              <a [href]="c.source" target="_blank" rel="noopener noreferrer">page de la photographie</a></p></div></li>
        }
      </ul>
    } @else {
      <div class="empty"><p class="empty-title">Aucune photographie n’est actuellement utilisée sur le site.</p></div>
    }
    <p class="muted small">Typographie : Inter (SIL Open Font License 1.1). Icônes : Lucide (licence ISC).</p>
  `,
})
export class PhotoCredits {
  protected readonly credits = Object.values(PHOTOS);
}
