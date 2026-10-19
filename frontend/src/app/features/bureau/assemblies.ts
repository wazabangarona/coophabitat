import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { AssemblyResponse } from '../../core/api-types';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { LabelPipe, WhenPipe } from '../../ui/pipes';

export function blankAssembly() {
  return { title: '', type: 'ORDINAIRE', scheduledAt: '', location: '', modality: 'PRESENTIEL', quorumRule: '', convocationText: '' };
}

@Component({
  selector: 'app-assemblies',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Badge, LabelPipe, WhenPipe],
  template: `
    <app-page-header title="Assemblées générales">
      <button type="button" class="btn small primary" (click)="form.set(!form())">Programmer une assemblée</button>
    </app-page-header>
    @if (form()) {
      <section class="card"><h2>Nouvelle assemblée</h2>
        <form class="grid-2" (ngSubmit)="create()" #f="ngForm">
          <label class="field span-2"><span>Intitulé</span><input name="t" [(ngModel)]="a.title" required /></label>
          <label class="field"><span>Type</span><select name="ty" [(ngModel)]="a.type"><option value="ORDINAIRE">Ordinaire</option><option value="EXTRAORDINAIRE">Extraordinaire</option></select></label>
          <label class="field"><span>Date et heure</span><input type="datetime-local" name="d" [(ngModel)]="a.scheduledAt" required /></label>
          <label class="field"><span>Modalité</span><select name="m" [(ngModel)]="a.modality"><option value="PRESENTIEL">Présentiel</option><option value="EN_LIGNE">En ligne</option><option value="MIXTE">Mixte</option></select></label>
          <label class="field"><span>Lieu ou lien</span><input name="l" [(ngModel)]="a.location" /></label>
          <label class="field span-2"><span>Règle de quorum (selon les statuts)</span><input name="q" [(ngModel)]="a.quorumRule" /></label>
          <label class="field span-2"><span>Texte de convocation</span><textarea name="c" [(ngModel)]="a.convocationText"></textarea></label>
          <div class="span-2 row"><button type="button" class="btn" (click)="form.set(false)">Annuler</button><button class="btn primary" [disabled]="f.invalid || busy.on()">Créer</button></div>
        </form>
      </section>
    }
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length" emptyText="Aucune assemblée." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Référence</th><th>Intitulé</th><th>Date</th><th>Type</th><th>Statut</th></tr></thead>
        <tbody>@for (x of list.data(); track x.id) {
          <tr><td data-label="Référence"><a [routerLink]="['/bureau/assemblees', x.id]">{{ x.reference }}</a></td><td data-label="Intitulé">{{ x.title }}</td>
            <td data-label="Date">{{ x.scheduledAt | when }}</td><td data-label="Type">{{ x.type | label }}</td><td data-label="Statut"><app-badge [value]="x.status" /></td></tr>
        }</tbody>
      </table></div>
    </app-state>
  `,
})
export class Assemblies implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<AssemblyResponse[]>();
  protected readonly form = signal(false);
  protected readonly busy = new Busy();
  protected a = blankAssembly();

  ngOnInit(): void {
    this.list.load(() => this.api.get<AssemblyResponse[]>('/assemblies'));
  }

  create(): void {
    this.busy.run(this.api.post<AssemblyResponse>('/assemblies', { ...this.a, scheduledAt: new Date(this.a.scheduledAt).toISOString() }), (r) => {
      this.toasts.success('Assemblée programmée. Définissez l’ordre du jour avant de convoquer.');
      void this.router.navigate(['/bureau/assemblees', r.id]);
    }, (e) => this.toasts.error(e));
  }
}
