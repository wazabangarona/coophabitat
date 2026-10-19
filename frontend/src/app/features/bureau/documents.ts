import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Api, openBlob } from '../../core/api';
import { DocumentItem, PageResponseDocumentItem } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { PageHeader, Pager, StateView } from '../../ui/components';
import { BytesPipe, DayPipe, LabelPipe } from '../../ui/pipes';
import { CATEGORIES } from '../member/my-documents';

const VISIBILITIES = ['PUBLIC', 'MEMBRES', 'BUREAU', 'PRIVE_MEMBRE'];

@Component({
  selector: 'app-bureau-documents',
  imports: [FormsModule, PageHeader, StateView, Pager, LabelPipe, DayPipe, BytesPipe],
  template: `
    <app-page-header title="Documents" subtitle="Les fichiers sont conservés avec leur empreinte SHA-256 ; un document publié n’est jamais modifié, il est archivé et remplacé." />
    @if (auth.has('DOCUMENT_MANAGE')) {
      <form class="card grid-2" (ngSubmit)="upload()" #f="ngForm">
        <h2 class="span-2">Déposer un document</h2>
        <label class="field span-2"><span>Titre</span><input name="t" [(ngModel)]="u.title" required /></label>
        <label class="field"><span>Catégorie</span><select name="c" [(ngModel)]="u.category">@for (c of categories; track c) { <option [value]="c">{{ c | label }}</option> }</select></label>
        <label class="field"><span>Visibilité initiale</span><select name="v" [(ngModel)]="u.visibility">@for (v of visibilities; track v) { <option [value]="v">{{ v | label }}</option> }</select></label>
        @if (u.visibility === 'PRIVE_MEMBRE') { <label class="field"><span>Identifiant du membre concerné</span><input type="number" name="o" [(ngModel)]="u.ownerMemberId" required /></label> }
        <label class="field span-2"><span>Fichier (PDF, JPEG, PNG)</span><input type="file" accept="application/pdf,image/jpeg,image/png" (change)="pick($event)" required /></label>
        <p class="span-2 small muted">Ne déposez pas de document officiel dont l’authenticité n’est pas établie. Les statuts et règlements doivent être les versions adoptées.</p>
        <div class="span-2"><button class="btn primary" [disabled]="f.invalid || !file || busy.on()">Déposer</button></div>
      </form>
    }
    <div class="filters">
      <label class="field inline"><span>Catégorie</span><select [(ngModel)]="category" (ngModelChange)="go(0)"><option value="">Toutes</option>
        @for (c of categories; track c) { <option [value]="c">{{ c | label }}</option> }</select></label>
      <label class="field inline"><span>Visibilité</span><select [(ngModel)]="visibility" (ngModelChange)="go(0)"><option value="">Toutes</option>
        @for (v of visibilities; track v) { <option [value]="v">{{ v | label }}</option> }</select></label>
    </div>
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.content?.length" emptyText="Aucun document." (retry)="list.reload()">
      <ul class="doc-list">@for (d of list.data()?.content; track d.id) {
        <li class="card"><div class="row between"><strong>{{ d.title }}</strong><span class="badge" data-tone="info">{{ d.visibility | label }}</span></div>
          <p class="small muted">{{ d.reference }} · {{ d.category | label }} · {{ d.createdAt | day }} · {{ d.sizeBytes | bytes }}{{ d.generated ? ' · généré par l’application' : '' }}
            @if (d.archivedAt) { · archivé le {{ d.archivedAt | day }} }</p>
          <p class="small muted mono">SHA-256 : {{ d.sha256 }}</p>
          <div class="row"><button type="button" class="btn small" (click)="open(d)">Ouvrir</button>
            @if (auth.has('DOCUMENT_MANAGE') && !d.archivedAt) {
              <select [(ngModel)]="publishTo[d.id!]" [attr.aria-label]="'Nouvelle visibilité pour ' + d.title"><option [ngValue]="undefined">Publier pour…</option>
                @for (v of visibilities; track v) { @if (v !== d.visibility && v !== 'PRIVE_MEMBRE') { <option [value]="v">{{ v | label }}</option> } }</select>
              <button type="button" class="btn small" [disabled]="!publishTo[d.id!]" (click)="publish(d)">Publier</button>
              <button type="button" class="btn small" (click)="archive(d)">Archiver</button>
            }</div></li>
      }</ul>
      <app-pager [page]="page()" [totalPages]="list.data()?.totalPages ?? 0" [total]="list.data()?.totalElements ?? 0" (go)="go($event)" />
    </app-state>
  `,
})
export class BureauDocuments implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly list = new Loader<PageResponseDocumentItem>();
  protected readonly page = signal(0);
  protected readonly busy = new Busy();
  protected readonly categories = CATEGORIES;
  protected readonly visibilities = VISIBILITIES;
  protected category = '';
  protected visibility = '';
  protected file: File | null = null;
  protected publishTo: Record<number, string | undefined> = {};
  protected u = { title: '', category: 'AUTRE', visibility: 'BUREAU', ownerMemberId: null as number | null };

  ngOnInit(): void {
    this.go(0);
  }

  go(p: number): void {
    this.page.set(p);
    this.list.load(() => this.api.get<PageResponseDocumentItem>('/documents', { category: this.category, visibility: this.visibility, page: p, size: 20 }));
  }

  pick(ev: Event): void {
    this.file = (ev.target as HTMLInputElement).files?.[0] ?? null;
  }

  private run(obs: Observable<unknown>, msg: string): void {
    this.busy.run(obs, () => { this.toasts.success(msg); this.list.reload(); }, (e) => this.toasts.error(e));
  }

  upload(): void {
    if (!this.file) return;
    const fd = new FormData();
    fd.append('file', this.file);
    this.run(this.api.upload('/documents', fd, { ...this.u, ownerMemberId: this.u.visibility === 'PRIVE_MEMBRE' ? this.u.ownerMemberId : null }), 'Document déposé.');
    this.u = { title: '', category: 'AUTRE', visibility: 'BUREAU', ownerMemberId: null };
    this.file = null;
  }

  async publish(d: DocumentItem): Promise<void> {
    const v = this.publishTo[d.id!];
    if ((await this.dialogs.ask({ title: `Publier « ${d.title} » ?`, message: v === 'PUBLIC' ? 'Le document sera visible de tout internaute, sans connexion.' : 'Le document sera visible des personnes concernées.' })) === null) return;
    this.run(this.api.post(`/documents/${d.id}/publish`, { visibility: v }), 'Document publié.');
  }

  async archive(d: DocumentItem): Promise<void> {
    const reason = await this.dialogs.ask({ title: `Archiver « ${d.title} »`, message: 'Le document reste conservé mais n’est plus proposé.', reasonLabel: 'Motif' });
    if (reason !== null) this.run(this.api.post(`/documents/${d.id}/archive`, { reason }), 'Document archivé.');
  }

  open(d: DocumentItem): void {
    this.api.download(`/documents/${d.id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
