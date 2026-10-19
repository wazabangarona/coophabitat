import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, openBlob } from '../../../core/api';
import { ReportResponse } from '../../../core/api-types';
import { Auth } from '../../../core/auth';
import { Dialogs } from '../../../core/dialog';
import { Busy, Loader } from '../../../core/loader';
import { Toasts } from '../../../core/toast';
import { Badge, PageHeader, StateView } from '../../../ui/components';
import { DayPipe, LabelPipe, WhenPipe } from '../../../ui/pipes';

@Component({
  selector: 'app-reports',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Rapports financiers" subtitle="Chaque rapport fige un instantané des comptes ; sa publication aux membres exige une seconde personne." />
    @if (auth.has('REPORT_GENERATE')) {
      <form class="card grid-2" (ngSubmit)="generate()" #f="ngForm">
        <h2 class="span-2">Générer un rapport</h2>
        <label class="field"><span>Type</span><select name="t" [(ngModel)]="r.type"><option value="SEMESTRIEL">Semestriel</option><option value="ANNUEL">Annuel</option><option value="PERIODE">Période libre</option></select></label>
        <span></span>
        <label class="field"><span>Du</span><input type="date" name="s" [(ngModel)]="r.periodStart" required /></label>
        <label class="field"><span>Au</span><input type="date" name="e" [(ngModel)]="r.periodEnd" required /></label>
        <div class="span-2"><button class="btn primary" [disabled]="f.invalid || busy.on()">Générer (brouillon réservé au bureau)</button></div>
      </form>
    }
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length" emptyText="Aucun rapport." (retry)="list.reload()">
      <ul class="plain">@for (x of list.data(); track x.id) {
        <li class="card row between"><div><strong>{{ x.reference }}</strong> — {{ x.type | label }} du {{ x.periodStart | day }} au {{ x.periodEnd | day }}
          <p class="small muted">Généré le {{ x.generatedAt | when }}@if (x.publishedAt) { , publié le {{ x.publishedAt | when }} }</p></div>
          <div class="row"><app-badge [value]="x.status" />
            @if (x.documentId) { <button type="button" class="btn small" (click)="open(x.documentId)">PDF</button> }
            @if (x.status === 'BROUILLON' && auth.has('REPORT_PUBLISH')) { <button type="button" class="btn small primary" (click)="publish(x)">Publier aux membres</button> }</div></li>
      }</ul>
    </app-state>
  `,
})
export class Reports implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly list = new Loader<ReportResponse[]>();
  protected readonly busy = new Busy();
  protected r = { type: 'SEMESTRIEL', periodStart: `${new Date().getFullYear()}-01-01`, periodEnd: `${new Date().getFullYear()}-06-30` };

  ngOnInit(): void {
    this.list.load(() => this.api.get<ReportResponse[]>('/finance/reports'));
  }

  generate(): void {
    this.busy.run(this.api.post('/finance/reports', this.r), () => { this.toasts.success('Rapport généré.'); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  async publish(x: ReportResponse): Promise<void> {
    if ((await this.dialogs.ask({ title: `Publier ${x.reference} ?`, message: 'Le rapport sera visible de tous les membres, qui seront notifiés. Vous ne pouvez pas publier un rapport que vous avez généré.' })) === null) return;
    this.busy.run(this.api.post(`/finance/reports/${x.id}/publish`), () => { this.toasts.success('Rapport publié.'); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  open(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
