import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { CampaignResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe } from '../../ui/pipes';

export function blankCampaign() {
  return {
    title: '', description: '', method: 'TIRAGE_AU_SORT', eligibilityText: '', rulesText: '', minSeniorityMonths: 6,
    requireUpToDate: true, maxArrearsAmount: 0, allowedMemberTypes: ['FONDATEUR', 'ORDINAIRE', 'BENEFICIAIRE'],
    referenceDate: new Date().toISOString().slice(0, 10), applicationClosesAt: '', requiredApprovals: 2, sessionDate: '', sessionLocation: '',
  };
}
export type CampaignForm = ReturnType<typeof blankCampaign>;

export function toRequest(f: CampaignForm) {
  return {
    ...f,
    applicationClosesAt: f.applicationClosesAt ? new Date(f.applicationClosesAt).toISOString() : null,
    sessionDate: f.sessionDate ? new Date(f.sessionDate).toISOString() : null,
  };
}

@Component({
  selector: 'app-allocations',
  imports: [FormsModule, RouterLink, PageHeader, StateView, Badge, LabelPipe, DayPipe],
  template: `
    <app-page-header title="Attributions" subtitle="Procédures d’attribution des lots, de la publication des critères à l’archivage.">
      @if (auth.has('ALLOCATION_MANAGE')) { <button type="button" class="btn small primary" (click)="form.set(!form())">Nouvelle procédure</button> }
    </app-page-header>
    @if (form()) {
      <section class="card"><h2>Nouvelle procédure</h2>
        <form class="grid-2" (ngSubmit)="create()" #f="ngForm">
          <label class="field span-2"><span>Intitulé</span><input name="t" [(ngModel)]="c.title" required /></label>
          <label class="field"><span>Méthode</span><select name="m" [(ngModel)]="c.method"><option value="TIRAGE_AU_SORT">Tirage au sort</option><option value="COMMISSION">Décision motivée d’une commission</option></select></label>
          <label class="field"><span>Validations distinctes requises</span><input type="number" name="ra" [(ngModel)]="c.requiredApprovals" min="2" max="9" required /></label>
          <label class="field"><span>Ancienneté minimale (mois)</span><input type="number" name="ms" [(ngModel)]="c.minSeniorityMonths" min="0" /></label>
          <label class="field"><span>Arriérés tolérés (XOF)</span><input type="number" name="ma" [(ngModel)]="c.maxArrearsAmount" min="0" step="1" required /></label>
          <label class="check"><input type="checkbox" name="up" [(ngModel)]="c.requireUpToDate" /> Être à jour de ses cotisations</label>
          <label class="field"><span>Date de référence de l’éligibilité</span><input type="date" name="rd" [(ngModel)]="c.referenceDate" /></label>
          <label class="field"><span>Clôture des candidatures</span><input type="datetime-local" name="cl" [(ngModel)]="c.applicationClosesAt" /></label>
          <label class="field"><span>Séance publique (date)</span><input type="datetime-local" name="sd" [(ngModel)]="c.sessionDate" /></label>
          <label class="field"><span>Lieu de la séance</span><input name="sl" [(ngModel)]="c.sessionLocation" /></label>
          <fieldset class="field span-2"><legend>Types de membres admis</legend>
            @for (t of memberTypes; track t) { <label class="check"><input type="checkbox" [checked]="c.allowedMemberTypes.includes(t)" (change)="toggleType(t)" /> {{ t | label }}</label> }</fieldset>
          <label class="field span-2"><span>Critères d’éligibilité (texte publié aux membres)</span><textarea name="el" [(ngModel)]="c.eligibilityText" required></textarea></label>
          <label class="field span-2"><span>Règles de la procédure (texte publié)</span><textarea name="ru" [(ngModel)]="c.rulesText" required></textarea></label>
          <label class="field span-2"><span>Description</span><textarea name="de" [(ngModel)]="c.description"></textarea></label>
          <div class="span-2 row"><button type="button" class="btn" (click)="form.set(false)">Annuler</button><button class="btn primary" [disabled]="f.invalid || busy.on()">Créer en brouillon</button></div>
        </form>
      </section>
    }
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length" emptyText="Aucune procédure." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Référence</th><th>Intitulé</th><th>Méthode</th><th>Référence d’éligibilité</th><th class="num">Candidats</th><th>Statut</th></tr></thead>
        <tbody>@for (x of list.data(); track x.id) {
          <tr><td data-label="Référence"><a [routerLink]="['/bureau/attributions', x.id]">{{ x.reference }}</a></td><td data-label="Intitulé">{{ x.title }}</td>
            <td data-label="Méthode">{{ x.method | label }}</td><td data-label="Date de référence">{{ x.referenceDate | day }}</td>
            <td data-label="Candidats" class="num">{{ x.candidateCount }} ({{ x.eligibleCount }} éligibles)</td><td data-label="Statut"><app-badge [value]="x.status" /></td></tr>
        }</tbody>
      </table></div>
    </app-state>
  `,
})
export class Allocations implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<CampaignResponse[]>();
  protected readonly form = signal(false);
  protected readonly busy = new Busy();
  protected readonly memberTypes = ['FONDATEUR', 'ORDINAIRE', 'BENEFICIAIRE'];
  protected c = blankCampaign();

  ngOnInit(): void {
    this.list.load(() => this.api.get<CampaignResponse[]>('/allocations'));
  }

  toggleType(t: string): void {
    const l = this.c.allowedMemberTypes;
    this.c.allowedMemberTypes = l.includes(t) ? l.filter((x) => x !== t) : [...l, t];
  }

  create(): void {
    this.busy.run(this.api.post<CampaignResponse>('/allocations', toRequest(this.c)), (r) => {
      this.toasts.success(`Procédure ${r.reference} créée en brouillon.`);
      void this.router.navigate(['/bureau/attributions', r.id]);
    }, (e) => this.toasts.error(e));
  }
}
