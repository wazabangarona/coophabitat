import { Component, OnInit, inject } from '@angular/core';
import { Api } from '../../core/api';
import { PublicProject } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Badge, Icon, PageHeader, StateView } from '../../ui/components';
import { NumPipe } from '../../ui/pipes';

@Component({
  selector: 'app-public-projects',
  imports: [PageHeader, StateView, Badge, Icon, NumPipe],
  template: `
    <app-page-header title="Projets de la coopérative"
      subtitle="Localisation administrative et nombre de lots. Les informations foncières détaillées sont réservées aux membres." />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucun projet publié." emptyIcon="building-2" (retry)="list.reload()">
      <div class="project-grid">
        @for (p of list.data(); track p.code) {
          <article class="project-card">
            <div class="plan-art"><app-badge [value]="p.status" /><app-icon name="building-2" /></div>
            <div class="project-body">
              <h2 class="h3">{{ p.name }}</h2>
              <p class="project-place"><app-icon name="map-pin" />{{ p.commune || '—' }}{{ p.departement ? ', ' + p.departement : '' }}{{ p.region ? ' — ' + p.region : '' }}</p>
              <p class="muted small mono">{{ p.code }}</p>
              <div class="project-foot"><span>Lots</span><strong>{{ p.lots | num }}</strong></div>
            </div>
          </article>
        }
      </div>
      <p class="chart-caption">Les visuels de ces fiches sont des motifs graphiques : ils ne représentent ni les terrains ni les plans des projets.</p>
    </app-state>
  `,
})
export class PublicProjects implements OnInit {
  private readonly api = inject(Api);
  protected readonly list = new Loader<PublicProject[]>();
  ngOnInit(): void {
    this.list.load(() => this.api.get<PublicProject[]>('/public/projects'));
  }
}
