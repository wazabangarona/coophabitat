import { Component, OnInit, inject } from '@angular/core';
import { Api } from '../../core/api';
import { PublicProject } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { NumPipe } from '../../ui/pipes';

@Component({
  selector: 'app-public-projects',
  imports: [PageHeader, StateView, Badge, NumPipe],
  template: `
    <app-page-header title="Projets de la coopérative"
      subtitle="Localisation administrative et nombre de lots. Les informations foncières détaillées sont réservées aux membres." />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucun projet publié." (retry)="list.reload()">
      <div class="table-wrap">
        <table>
          <caption class="sr">Projets</caption>
          <thead><tr><th scope="col">Projet</th><th scope="col">Localisation</th><th scope="col">Statut</th><th scope="col" class="num">Lots</th></tr></thead>
          <tbody>
            @for (p of list.data(); track p.code) {
              <tr>
                <td data-label="Projet"><strong>{{ p.name }}</strong><br /><small class="muted">{{ p.code }}</small></td>
                <td data-label="Localisation">{{ p.commune || '—' }}{{ p.departement ? ', ' + p.departement : '' }}{{ p.region ? ' — ' + p.region : '' }}</td>
                <td data-label="Statut"><app-badge [value]="p.status" /></td>
                <td data-label="Lots" class="num">{{ p.lots | num }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
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
