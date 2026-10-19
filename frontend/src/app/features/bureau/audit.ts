import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api';
import { ChainVerification, PageResponseAuditItem } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { PageHeader, Pager, StateView } from '../../ui/components';
import { LabelPipe, WhenPipe } from '../../ui/pipes';

const CATS = ['SECURITE', 'FINANCE', 'MEMBRE', 'ATTRIBUTION', 'LOT', 'DOCUMENT', 'ASSEMBLEE', 'ADMIN', 'NOTIFICATION', 'DONNEES'];

@Component({
  selector: 'app-audit',
  imports: [FormsModule, PageHeader, StateView, Pager, LabelPipe, WhenPipe],
  template: `
    <app-page-header title="Journal d’audit" subtitle="Chaque événement est chaîné au précédent par une empreinte : une modification ou suppression est détectable.">
      <button type="button" class="btn small primary" (click)="verify()">Vérifier l’intégrité de la chaîne</button>
    </app-page-header>
    @if (check(); as c) { <p class="alert" [attr.data-tone]="c.valid ? 'ok' : 'bad'" role="status">{{ c.message }} ({{ c.checkedEvents }} événements contrôlés)
      @if (c.firstInvalidEventId) { — premier événement invalide : n° {{ c.firstInvalidEventId }} }</p> }
    <form class="filters" (ngSubmit)="go(0)">
      <label class="field inline"><span>Catégorie</span><select name="c" [(ngModel)]="category"><option value="">Toutes</option>
        @for (c of cats; track c) { <option [value]="c">{{ c | label }}</option> }</select></label>
      <label class="field inline"><span>Action</span><input name="a" [(ngModel)]="action" placeholder="ex. PAIEMENT_CONFIRME" /></label>
      <label class="field inline"><span>Du</span><input type="date" name="f" [(ngModel)]="from" /></label>
      <label class="field inline"><span>Au</span><input type="date" name="t" [(ngModel)]="to" /></label>
      <button class="btn">Filtrer</button>
    </form>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="Aucun événement." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Date</th><th>Catégorie</th><th>Action</th><th>Auteur</th><th>Objet</th><th>Détails</th></tr></thead>
        <tbody>@for (e of list.data()?.content; track e.id) {
          <tr><td data-label="Date">{{ e.occurredAt | when }}</td><td data-label="Catégorie">{{ e.category | label }}</td><td data-label="Action" class="mono">{{ e.action }}</td>
            <td data-label="Auteur">{{ e.actorLabel || 'système' }}</td><td data-label="Objet">{{ e.entityType }} {{ e.entityId }}</td>
            <td data-label="Détails" class="small mono">{{ e.details || '—' }}</td></tr>
        }</tbody>
      </table></div>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class Audit implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<PageResponseAuditItem>();
  protected readonly check = signal<ChainVerification | null>(null);
  protected readonly page = signal(0);
  protected readonly cats = CATS;
  protected category = '';
  protected action = '';
  protected from = '';
  protected to = '';

  ngOnInit(): void {
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseAuditItem>('/audit', { category: this.category, action: this.action.trim(), from: this.from, to: this.to, page: p, size: 50 }));
  }

  verify(): void {
    this.api.get<ChainVerification>('/audit/verify').subscribe({ next: (c) => this.check.set(c), error: (e) => this.toasts.error(e) });
  }
}
