import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, openBlob } from '../../core/api';
import { DocumentItem, PageResponseDocumentItem } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { PageHeader, Pager, StateView } from '../../ui/components';
import { BytesPipe, DayPipe, LabelPipe } from '../../ui/pipes';

export const CATEGORIES = ['STATUTS', 'REGLEMENT', 'CONVOCATION', 'COMPTE_RENDU_AG', 'PV_ATTRIBUTION', 'RECU',
  'RAPPORT_FINANCIER', 'JUSTIFICATIF_DEPENSE', 'DOCUMENT_FONCIER', 'DOCUMENT_MEMBRE', 'AUTRE'];

@Component({
  selector: 'app-my-documents',
  imports: [FormsModule, PageHeader, StateView, Pager, LabelPipe, DayPipe, BytesPipe],
  template: `
    <app-page-header title="Documents" subtitle="Documents publiés aux membres et documents qui vous concernent." />
    <div class="filters">
      <label class="field inline"><span>Catégorie</span>
        <select [(ngModel)]="category" (ngModelChange)="go(0)">
          <option value="">Toutes</option>
          @for (c of categories; track c) { <option [value]="c">{{ c | label }}</option> }
        </select></label>
    </div>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length"
      emptyText="Aucun document." (retry)="list.reload()">
      <ul class="doc-list">
        @for (d of list.data()?.content; track d.id) {
          <li class="card row between">
            <div><strong>{{ d.title }}</strong>
              <p class="muted small">{{ d.category | label }} · {{ d.visibility | label }} · {{ (d.publishedAt || d.createdAt) | day }} · {{ d.sizeBytes | bytes }}</p></div>
            <button type="button" class="btn small" (click)="open(d)">Ouvrir</button>
          </li>
        }
      </ul>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class MyDocuments implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<PageResponseDocumentItem>();
  protected readonly page = signal(0);
  protected readonly categories = CATEGORIES;
  protected category = '';

  ngOnInit(): void {
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseDocumentItem>('/documents', { page: p, size: 20, category: this.category }));
  }

  open(d: DocumentItem): void {
    this.api.download(`/documents/${d.id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
