import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api';
import { ChannelState, NotificationAttempt, PageResponseQueueItem, QueueItem } from '../../core/api-types';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, Pager, StateView } from '../../ui/components';
import { LabelPipe, WhenPipe } from '../../ui/pipes';

interface Stat { status: string; count: number; channel: string }

@Component({
  selector: 'app-bureau-notifications',
  imports: [FormsModule, PageHeader, StateView, Pager, Badge, LabelPipe, WhenPipe],
  template: `
    <app-page-header title="Notifications" subtitle="File d’envoi des messages. Un message n’est marqué envoyé que si le canal l’a accepté.">
      <button type="button" class="btn small" [disabled]="busy.on()" (click)="dispatch()">Traiter la file maintenant</button>
    </app-page-header>
    <section class="card"><h2>Canaux</h2>
      <ul class="plain">@for (c of channels(); track c.channel) {
        <li><strong>{{ c.channel | label }}</strong> : <span class="badge" [attr.data-tone]="c.configured ? 'ok' : 'warn'">{{ c.configured ? 'configuré' : 'non configuré' }}</span> {{ c.message }}</li>
      }</ul>
      <p class="small">@for (s of stats(); track $index) { <span class="chip">{{ s.channel | label }} · {{ s.status | label }} : {{ s.count }}</span> }</p>
    </section>
    <div class="filters">
      <label class="field inline"><span>Statut</span><select [(ngModel)]="status" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of ['EN_ATTENTE', 'ENVOYE', 'ECHOUE', 'LIVRE']; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
      <label class="field inline"><span>Canal</span><select [(ngModel)]="channel" (ngModelChange)="go(0)"><option value="">Tous</option>
        @for (s of ['EMAIL', 'SMS', 'IN_APP']; track s) { <option [value]="s">{{ s | label }}</option> }</select></label>
    </div>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="File vide." (retry)="list.reload()">
      <div class="table-wrap"><table>
        <thead><tr><th>Créée</th><th>Canal</th><th>Modèle</th><th>Objet</th><th>Statut</th><th class="num">Essais</th></tr></thead>
        <tbody>@for (q of list.data()?.content; track q.id) {
          <tr><td data-label="Créée">{{ q.createdAt | when }}</td><td data-label="Canal">{{ q.channel | label }}</td><td data-label="Modèle">{{ q.templateCode }}</td>
            <td data-label="Objet">{{ q.subject }}</td>
            <td data-label="Statut"><app-badge [value]="q.status" /><br /><small class="muted">{{ q.statusDetail }}</small></td>
            <td data-label="Essais" class="num"><button type="button" class="link" (click)="attemptsOf(q)">{{ q.attempts }}</button></td></tr>
          @if (open() === q.id) { <tr class="sub"><td colspan="6">@for (a of attempts(); track a.id) { <div>{{ a.attemptedAt | when }} — {{ a.outcome }} : {{ a.detail }}</div> } @empty { Aucune tentative. }</td></tr> }
        }</tbody>
      </table></div>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class BureauNotifications implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly list = new Loader<PageResponseQueueItem>();
  protected readonly channels = signal<ChannelState[]>([]);
  protected readonly stats = signal<Stat[]>([]);
  protected readonly attempts = signal<NotificationAttempt[]>([]);
  protected readonly open = signal<number | null>(null);
  protected readonly page = signal(0);
  protected readonly busy = new Busy();
  protected status = '';
  protected channel = '';

  ngOnInit(): void {
    this.go(0);
    this.meta();
  }

  meta(): void {
    this.api.get<ChannelState[]>('/notifications/channels').subscribe((c) => this.channels.set(c));
    this.api.get<Stat[]>('/notifications/stats').subscribe((s) => this.stats.set(s));
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseQueueItem>('/notifications/queue', { status: this.status, channel: this.channel, page: p, size: 30 }));
  }

  attemptsOf(q: QueueItem): void {
    if (this.open() === q.id) { this.open.set(null); return; }
    this.open.set(q.id ?? null);
    this.api.get<NotificationAttempt[]>(`/notifications/queue/${q.id}/attempts`).subscribe((a) => this.attempts.set(a));
  }

  dispatch(): void {
    this.busy.run(this.api.post<Record<string, number>>('/notifications/dispatch'), (r) => {
      this.toasts.info(`File traitée : ${JSON.stringify(r)}`);
      this.list.reload();
      this.meta();
    }, (e) => this.toasts.error(e));
  }
}
