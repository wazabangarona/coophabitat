import { Component, OnInit, inject } from '@angular/core';
import { Api, openBlob } from '../../core/api';
import { DocumentItem } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { PageHeader, StateView } from '../../ui/components';
import { BytesPipe, DayPipe, LabelPipe } from '../../ui/pipes';

@Component({
  selector: 'app-public-documents',
  imports: [PageHeader, StateView, LabelPipe, DayPipe, BytesPipe],
  template: `
    <app-page-header title="Documents publics" subtitle="Documents que la coopérative a choisi de rendre publics." />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucun document public n’a encore été publié." (retry)="list.reload()">
      <ul class="doc-list">
        @for (d of list.data(); track d.id) {
          <li class="card row between">
            <div>
              <strong>{{ d.title }}</strong>
              <p class="muted small">{{ d.category | label }} · publié le {{ d.publishedAt | day }} · {{ d.sizeBytes | bytes }}</p>
              <p class="muted small mono" title="Empreinte SHA-256 du fichier">SHA-256 : {{ d.sha256 }}</p>
            </div>
            <button type="button" class="btn small" (click)="open(d)">Ouvrir</button>
          </li>
        }
      </ul>
    </app-state>
  `,
})
export class PublicDocuments implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<DocumentItem[]>();
  ngOnInit(): void {
    this.list.load(() => this.api.get<DocumentItem[]>('/public/documents'));
  }
  protected open(d: DocumentItem): void {
    this.api.download(`/public/documents/${d.id}/content`).subscribe({
      next: (r) => openBlob(r.blob),
      error: (e) => this.toasts.error(e),
    });
  }
}
