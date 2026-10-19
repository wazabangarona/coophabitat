import { Component, OnInit, inject, signal } from '@angular/core';
import { Api } from '../../core/api';
import { InAppItem, PageResponseInAppItem } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { PageHeader, Pager, StateView } from '../../ui/components';
import { WhenPipe } from '../../ui/pipes';

@Component({
  selector: 'app-inbox',
  imports: [PageHeader, StateView, Pager, WhenPipe],
  template: `
    <app-page-header title="Notifications" subtitle="Messages envoyés par la coopérative dans l’application." />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length"
      emptyText="Aucune notification." (retry)="list.reload()">
      <ul class="plain inbox">
        @for (n of list.data()?.content; track n.id) {
          <li class="card" [class.unread]="!n.readAt">
            <div class="row between">
              <strong>{{ n.subject }}</strong>
              <small class="muted">{{ n.createdAt | when }}</small>
            </div>
            <p class="pre">{{ n.body }}</p>
            @if (!n.readAt) { <button type="button" class="btn small" (click)="read(n)">Marquer comme lue</button> }
          </li>
        }
      </ul>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class Inbox implements OnInit {
  private readonly api = inject(Api);
  protected readonly list = new Loader<PageResponseInAppItem>();
  protected readonly page = signal(0);

  ngOnInit(): void {
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseInAppItem>('/notifications/inbox', { page: p, size: 20 }));
  }

  read(n: InAppItem): void {
    this.api.post<InAppItem>(`/notifications/inbox/${n.id}/read`).subscribe((u) =>
      this.list.data.update((d) => (d ? { ...d, content: d.content?.map((x) => (x.id === u.id ? u : x)) } : d)),
    );
  }
}
