import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin } from 'rxjs';
import { Api, openBlob } from '../../core/api';
import { AssemblyResponse, AttendanceResponse, MemberListItem, MinutesResponse } from '../../core/api-types';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, Kpi, PageHeader, StateView } from '../../ui/components';
import { LabelPipe, WhenPipe } from '../../ui/pipes';

interface Data { a: AssemblyResponse; attendance: AttendanceResponse[]; minutes: MinutesResponse[] }

@Component({
  selector: 'app-assembly-detail',
  imports: [FormsModule, PageHeader, StateView, Badge, Kpi, LabelPipe, WhenPipe],
  template: `
    <app-state [loading]="d.loading() && !d.data()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as x) {
        <app-page-header [title]="x.a.title ?? ''" [subtitle]="x.a.reference + ' · ' + (x.a.scheduledAt | when)" back="/bureau/assemblees" backLabel="Assemblées" />
        <section class="card">
          <p><app-badge [value]="x.a.status" /> {{ x.a.type | label }} — {{ x.a.modality | label }}{{ x.a.location ? ', ' + x.a.location : '' }}</p>
          @if (x.a.cancelReason) { <p class="alert" data-tone="bad">Annulée : {{ x.a.cancelReason }}</p> }
          <div class="row">
            @if (x.a.status === 'PROGRAMMEE') { <button type="button" class="btn primary" (click)="act('convene', 'Convoquer l’assemblée ?', 'La convocation PDF est publiée aux membres et chaque membre actif ou suspendu est notifié. L’ordre du jour sera figé.')">Convoquer</button> }
            @if (x.a.status === 'CONVOQUEE') { <button type="button" class="btn primary" (click)="act('held', 'Déclarer l’assemblée tenue ?', 'Possible à partir du jour de la séance. Le quorum constaté sera enregistré.')">Déclarer tenue</button> }
            @if (x.a.status === 'TENUE') { <button type="button" class="btn primary" (click)="act('close', 'Clôturer l’assemblée ?', 'Exige un compte rendu publié.')">Clôturer</button> }
            @if (x.a.status === 'PROGRAMMEE' || x.a.status === 'CONVOQUEE') { <button type="button" class="btn danger" (click)="cancel()">Annuler</button> }
            @if (x.a.convocationDocumentId) { <button type="button" class="btn" (click)="doc(x.a.convocationDocumentId)">Convocation (PDF)</button> }
            @if (x.a.publishedMinutesDocumentId) { <button type="button" class="btn" (click)="doc(x.a.publishedMinutesDocumentId)">Compte rendu (PDF)</button> }
          </div>
        </section>

        <section class="card"><h2>Ordre du jour</h2>
          @if (x.a.status === 'PROGRAMMEE') {
            @for (it of agenda; track $index; let i = $index) {
              <div class="grid-2"><label class="field"><span>Point {{ i + 1 }}</span><input [(ngModel)]="it.title" [name]="'t' + i" /></label>
                <label class="field"><span>Précisions</span><input [(ngModel)]="it.description" [name]="'d' + i" /></label></div>
            }
            <div class="row"><button type="button" class="btn small" (click)="agenda.push({ title: '', description: '' })">Ajouter un point</button>
              <button type="button" class="btn small primary" (click)="saveAgenda()">Enregistrer l’ordre du jour</button></div>
          } @else {
            <ol>@for (i of x.a.agenda; track i.id) { <li><strong>{{ i.title }}</strong>@if (i.description) { — {{ i.description }} }</li> }</ol>
          }
        </section>

        @if (x.a.status === 'CONVOQUEE' || x.a.status === 'TENUE') {
          <section class="card"><h2>Présences</h2>
            @if (x.a.quorum; as q) {
              <div class="kpis"><app-kpi label="Membres actifs" [value]="'' + q.activeMembers" /><app-kpi label="Présents" [value]="'' + q.present" />
                <app-kpi label="Représentés" [value]="'' + q.represented" /><app-kpi label="Participation" [value]="q.participationPercent + ' %'" /></div>
            }
            <p class="small muted">Une procuration doit désigner un membre présent. Les règles de quorum des statuts s’appliquent : {{ x.a.quorumRule || 'non précisées' }}.</p>
            <div class="table-wrap"><table>
              <thead><tr><th>Membre</th><th>Présence</th><th>Mandataire</th></tr></thead>
              <tbody>@for (m of members(); track m.id) {
                <tr><td data-label="Membre">{{ m.memberNumber }} — {{ m.fullName }}</td>
                  <td data-label="Présence"><select [(ngModel)]="att[m.id!].status" [name]="'s' + m.id" [attr.aria-label]="'Présence de ' + m.fullName">
                    <option value="">—</option>@for (s of attStatuses; track s) { <option [value]="s">{{ s | label }}</option> }</select></td>
                  <td data-label="Mandataire">@if (att[m.id!].status === 'REPRESENTE') {
                    <select [(ngModel)]="att[m.id!].proxyMemberId" [name]="'p' + m.id" aria-label="Mandataire"><option [ngValue]="null">Choisir</option>
                      @for (o of members(); track o.id) { @if (o.id !== m.id) { <option [ngValue]="o.id">{{ o.memberNumber }}</option> } }</select> }</td></tr>
              }</tbody>
            </table></div>
            <button type="button" class="btn primary" [disabled]="busy.on()" (click)="saveAttendance()">Enregistrer les présences</button>
          </section>
        }

        <section class="card"><h2>Résolutions</h2>
          <ul class="plain">@for (r of x.a.resolutions; track r.id) {
            <li><strong>{{ r.number }}</strong> — {{ r.text }} <app-badge [value]="r.outcome" /> <small class="muted">(pour {{ r.votesFor }}, contre {{ r.votesAgainst }}, abstentions {{ r.abstentions }})</small></li>
          } @empty { <li class="muted">Aucune résolution enregistrée.</li> }</ul>
          @if (x.a.status === 'TENUE') {
            <form class="grid-2" (ngSubmit)="addResolution()" #rf="ngForm">
              <label class="field"><span>Numéro</span><input name="n" [(ngModel)]="res.number" required /></label>
              <label class="field"><span>Point de l’ordre du jour</span><select name="ai" [(ngModel)]="res.agendaItemId"><option [ngValue]="null">—</option>
                @for (i of x.a.agenda; track i.id) { <option [ngValue]="i.id">{{ i.position }}. {{ i.title }}</option> }</select></label>
              <label class="field span-2"><span>Texte de la résolution</span><textarea name="t" [(ngModel)]="res.text" required></textarea></label>
              <label class="field"><span>Pour</span><input type="number" name="f" [(ngModel)]="res.votesFor" min="0" /></label>
              <label class="field"><span>Contre</span><input type="number" name="c" [(ngModel)]="res.votesAgainst" min="0" /></label>
              <label class="field"><span>Abstentions</span><input type="number" name="a" [(ngModel)]="res.abstentions" min="0" /></label>
              <label class="field"><span>Issue</span><select name="o" [(ngModel)]="res.outcome"><option value="ADOPTEE">Adoptée</option><option value="REJETEE">Rejetée</option><option value="AJOURNEE">Ajournée</option></select></label>
              <div class="span-2"><button class="btn" [disabled]="rf.invalid || busy.on()">Enregistrer la résolution</button></div>
            </form>
          }
        </section>

        <section class="card"><h2>Comptes rendus</h2>
          <ul class="plain">@for (m of x.minutes; track m.id) {
            <li class="card"><div class="row between"><span>Version {{ m.versionNo }} — <app-badge [value]="m.status" /> {{ m.createdAt | when }}</span>
              @if (m.status === 'BROUILLON') { <button type="button" class="btn small primary" (click)="publishMinutes(m)">Publier</button> }</div>
              <p class="pre small">{{ m.content }}</p></li>
          } @empty { <li class="muted">Aucun compte rendu.</li> }</ul>
          @if (x.a.status === 'TENUE' || x.a.status === 'CLOTUREE') {
            <label class="field"><span>Nouvelle version du compte rendu</span><textarea rows="8" [(ngModel)]="minutesText"></textarea></label>
            <button type="button" class="btn" [disabled]="minutesText.trim().length < 20 || busy.on()" (click)="draftMinutes()">Enregistrer en brouillon</button>
            <p class="muted small">Publier une nouvelle version remplace la précédente, qui reste archivée et consultable.</p>
          }
        </section>
      }
    </app-state>
  `,
})
export class AssemblyDetail implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly busy = new Busy();
  protected readonly members = signal<MemberListItem[]>([]);
  protected readonly attStatuses = ['PRESENT', 'REPRESENTE', 'ABSENT', 'EXCUSE'];
  protected att: Record<number, { status: string; proxyMemberId: number | null }> = {};
  protected agenda: { title: string; description: string }[] = [];
  protected minutesText = '';
  protected res = { number: '', agendaItemId: null as number | null, text: '', votesFor: 0, votesAgainst: 0, abstentions: 0, outcome: 'ADOPTEE' };
  protected readonly d = new Loader<Data>((x) => {
    this.agenda = (x.a.agenda ?? []).map((i) => ({ title: i.title ?? '', description: i.description ?? '' }));
    if (!this.agenda.length) this.agenda.push({ title: '', description: '' });
    for (const m of this.members()) this.att[m.id!] ??= { status: '', proxyMemberId: null };
    for (const r of x.attendance) this.att[r.memberId!] = { status: r.status ?? '', proxyMemberId: r.proxyMemberId ?? null };
  });

  ngOnInit(): void {
    this.api.getAll<MemberListItem>('/members').subscribe((all) => {
      const list = all.filter((m) => m.status === 'ACTIF' || m.status === 'SUSPENDU');
      for (const m of list) this.att[m.id!] ??= { status: '', proxyMemberId: null };
      this.members.set(list);
    });
    this.load();
  }

  load(): void {
    const id = this.id();
    this.d.load(() => forkJoin({
      a: this.api.get<AssemblyResponse>(`/assemblies/${id}`),
      attendance: this.api.get<AttendanceResponse[]>(`/assemblies/${id}/attendance`),
      minutes: this.api.get<MinutesResponse[]>(`/assemblies/${id}/minutes`),
    }));
  }

  private run(obs: Observable<unknown>, msg: string, after?: () => void): void {
    this.busy.run(obs, () => { this.toasts.success(msg); after?.(); this.load(); }, (e) => this.toasts.error(e));
  }

  async act(path: string, title: string, message: string): Promise<void> {
    if ((await this.dialogs.ask({ title, message })) === null) return;
    this.run(this.api.post(`/assemblies/${this.id()}/${path}`), 'Étape enregistrée.');
  }

  async cancel(): Promise<void> {
    const reason = await this.dialogs.ask({ title: 'Annuler l’assemblée', reasonLabel: 'Motif (communiqué aux membres)', danger: true, reasonMinLength: 10 });
    if (reason !== null) this.run(this.api.post(`/assemblies/${this.id()}/cancel`, { reason }), 'Assemblée annulée.');
  }

  saveAgenda(): void {
    const items = this.agenda.filter((i) => i.title.trim()).map((i) => ({ title: i.title.trim(), description: i.description.trim() || null }));
    this.run(this.api.put(`/assemblies/${this.id()}/agenda`, { items }), 'Ordre du jour enregistré.');
  }

  saveAttendance(): void {
    const entries = Object.entries(this.att).filter(([, v]) => v.status)
      .map(([k, v]) => ({ memberId: Number(k), status: v.status, proxyMemberId: v.status === 'REPRESENTE' ? v.proxyMemberId : null }));
    this.run(this.api.put(`/assemblies/${this.id()}/attendance`, { entries }), 'Présences enregistrées.');
  }

  addResolution(): void {
    this.run(this.api.post(`/assemblies/${this.id()}/resolutions`, this.res), 'Résolution enregistrée.',
      () => (this.res = { number: '', agendaItemId: null, text: '', votesFor: 0, votesAgainst: 0, abstentions: 0, outcome: 'ADOPTEE' }));
  }

  draftMinutes(): void {
    this.run(this.api.post(`/assemblies/${this.id()}/minutes`, { content: this.minutesText }), 'Brouillon enregistré.', () => (this.minutesText = ''));
  }

  async publishMinutes(m: MinutesResponse): Promise<void> {
    if ((await this.dialogs.ask({ title: `Publier la version ${m.versionNo} ?`, message: 'Le compte rendu PDF sera publié aux membres.' })) === null) return;
    this.run(this.api.post(`/assemblies/${this.id()}/minutes/${m.id}/publish`), 'Compte rendu publié.');
  }

  doc(id: number): void {
    this.api.download(`/documents/${id}/content`).subscribe({ next: (r) => openBlob(r.blob), error: (e) => this.toasts.error(e) });
  }
}
