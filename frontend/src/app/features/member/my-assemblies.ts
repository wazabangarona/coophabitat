import { Component, OnInit, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, openBlob } from '../../core/api';
import { AssemblyResponse, MinutesResponse } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { LabelPipe, WhenPipe } from '../../ui/pipes';

@Component({
  selector: 'app-my-assemblies',
  imports: [RouterLink, PageHeader, StateView, Badge, LabelPipe, WhenPipe],
  template: `
    <app-page-header title="Assemblées générales" />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucune assemblée convoquée." (retry)="list.reload()">
      <ul class="plain">
        @for (a of list.data(); track a.id) {
          <li class="card row between">
            <div><a [routerLink]="['/espace/assemblees', a.id]"><strong>{{ a.title }}</strong></a>
              <p class="muted small">{{ a.type | label }} · {{ a.scheduledAt | when }} · {{ a.location || (a.modality | label) }}</p></div>
            <app-badge [value]="a.status" />
          </li>
        }
      </ul>
    </app-state>
  `,
})
export class MyAssemblies implements OnInit {
  private readonly api = inject(Api);
  protected readonly list = new Loader<AssemblyResponse[]>();
  ngOnInit(): void {
    this.list.load(() => this.api.get<AssemblyResponse[]>('/assemblies'));
  }
}

@Component({
  selector: 'app-my-assembly',
  imports: [PageHeader, StateView, Badge, LabelPipe, WhenPipe],
  template: `
    <app-state [loading]="a.loading()" [error]="a.error()" (retry)="a.reload()">
      @if (a.data(); as ag) {
        <app-page-header [title]="ag.title ?? ''" [subtitle]="ag.reference + ' · ' + (ag.scheduledAt | when)" back="/espace/assemblees" backLabel="Assemblées" />
        <section class="card">
          <p><app-badge [value]="ag.status" /> {{ ag.type | label }} — {{ ag.modality | label }}{{ ag.location ? ', ' + ag.location : '' }}</p>
          @if (ag.convocationText) { <p class="pre">{{ ag.convocationText }}</p> }
          @if (ag.quorumRule) { <p class="small"><strong>Règle de quorum :</strong> {{ ag.quorumRule }}</p> }
          @if (ag.cancelReason) { <p class="alert" data-tone="bad">Annulée : {{ ag.cancelReason }}</p> }
          <div class="row">
            @if (ag.convocationDocumentId) { <button type="button" class="btn" (click)="doc(ag.convocationDocumentId)">Convocation (PDF)</button> }
            @if (ag.publishedMinutesDocumentId) { <button type="button" class="btn" (click)="doc(ag.publishedMinutesDocumentId)">Compte rendu (PDF)</button> }
          </div>
        </section>
        <section class="card"><h2>Ordre du jour</h2>
          <ol>@for (i of ag.agenda; track i.id) { <li><strong>{{ i.title }}</strong>@if (i.description) { — {{ i.description }} }</li> }</ol>
        </section>
        @if (ag.resolutions?.length) {
          <section class="card"><h2>Résolutions</h2>
            <div class="table-wrap"><table>
              <thead><tr><th>N°</th><th>Texte</th><th class="num">Pour</th><th class="num">Contre</th><th class="num">Abst.</th><th>Issue</th></tr></thead>
              <tbody>@for (r of ag.resolutions; track r.id) {
                <tr><td data-label="N°">{{ r.number }}</td><td data-label="Texte">{{ r.text }}</td><td data-label="Pour" class="num">{{ r.votesFor }}</td>
                  <td data-label="Contre" class="num">{{ r.votesAgainst }}</td><td data-label="Abstentions" class="num">{{ r.abstentions }}</td>
                  <td data-label="Issue"><app-badge [value]="r.outcome" /></td></tr>
              }</tbody>
            </table></div>
          </section>
        }
        @if (minutes.data()?.length) {
          <section class="card"><h2>Compte rendu publié</h2>
            @for (m of minutes.data(); track m.id) { <p class="muted small">Version {{ m.versionNo }} publiée le {{ m.publishedAt | when }}</p><p class="pre">{{ m.content }}</p> }
          </section>
        }
      }
    </app-state>
  `,
})
export class MyAssemblyDetail implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  protected readonly a = new Loader<AssemblyResponse>();
  protected readonly minutes = new Loader<MinutesResponse[]>();

  ngOnInit(): void {
    this.a.load(() => this.api.get<AssemblyResponse>(`/assemblies/${this.id()}`));
    this.minutes.load(() => this.api.get<MinutesResponse[]>(`/assemblies/${this.id()}/minutes`));
  }

  doc(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
