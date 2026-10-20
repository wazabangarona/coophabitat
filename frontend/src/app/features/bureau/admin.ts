import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Api } from '../../core/api';
import { BureauHolder, BureauSetup, CoopSettings, DataRequestResponse, DataRetentionPolicy, PageResponseUserResponse, RoleResponse, UserResponse } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { ROLE_LABELS, label } from '../../core/labels';
import { Busy, Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, Pager, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, WhenPipe } from '../../ui/pipes';

type Tab = 'comptes' | 'parametres' | 'donnees';

@Component({
  selector: 'app-admin',
  imports: [FormsModule, PageHeader, StateView, Pager, Badge, LabelPipe, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Administration" />
    <nav class="tabs" aria-label="Sections d’administration">
      @for (t of tabs(); track t.key) { <a href="" [class.active]="tab() === t.key" (click)="$event.preventDefault(); tab.set(t.key)">{{ t.label }}</a> }
    </nav>

    @switch (tab()) {
      @case ('comptes') {
        @if (setup.data(); as b) {
          <section class="card setup">
            <h2>Mise en route du bureau</h2>
            <p class="small muted">Les adhésions sont validées par la présidence ou le secrétariat, la trésorerie gère les plans de cotisation.
              En tant qu’administrateur technique, vous désignez ces personnes ; vous ne validez pas vous-même les adhésions.</p>
            <ol class="checklist">
              <li [class.done]="b.presidents?.length || b.secretaries?.length || b.treasurers?.length">
                <strong>Les futurs responsables déposent leur demande d’adhésion</strong> avec le bouton « Adhérer » du site
                (ou vous leur créez un compte ci-dessous).</li>
              @for (f of functions; track f.key) {
                <li [class.done]="holders(b, f.key).length">
                  <strong>{{ f.label }} :</strong>{{ ' ' }}
                  @for (h of holders(b, f.key); track h.userId) {
                    <span class="chip">{{ h.displayName }} · {{ holderState(h) }}</span>
                  } @empty { <span class="muted">non désigné — recherchez la personne ci-dessous puis « Modifier les rôles ».</span> }
                </li>
              }
              <li [class.done]="(b.activePlans ?? 0) > 0"><strong>Plan de cotisation actif :</strong>{{ ' ' }}
                {{ (b.activePlans ?? 0) > 0 ? b.activePlans + ' plan(s) actif(s)' : 'aucun — la trésorerie le crée dans Cotisations.' }}</li>
              <li [class.done]="b.approverInPlace"><strong>Validation des adhésions :</strong>{{ ' ' }}
                @if (b.approverInPlace) { un responsable est en place ; {{ b.pendingApplications }} demande(s) en attente. }
                @else { le premier président ou secrétaire désigné valide sa propre adhésion, puis celles des autres
                  (page « Demandes d’adhésion »). }</li>
            </ol>
          </section>
        }
        <form class="card grid-2" (ngSubmit)="createUser()" #uf="ngForm">
          <h2 class="span-2">Créer un compte</h2>
          <label class="field"><span>Adresse électronique</span><input type="email" name="e" [(ngModel)]="nu.email" required email /></label>
          <label class="field"><span>Nom affiché</span><input name="n" [(ngModel)]="nu.displayName" required /></label>
          <label class="field"><span>Identifiant du membre lié (facultatif)</span><input type="number" name="m" [(ngModel)]="nu.memberId" /></label>
          <fieldset class="field"><legend>Rôles</legend>@for (r of roles(); track r.code) {
            <label class="check"><input type="checkbox" [checked]="nu.roles.includes(r.code!)" (change)="toggle(nu.roles, r.code!)" /> {{ roleLabel(r.code) }}</label> }</fieldset>
          <p class="span-2 small muted">Aucun mot de passe n’est défini ici : la personne reçoit une invitation à usage unique et choisit le sien.
            Certains cumuls sont interdits (président et trésorier, administrateur technique et fonction du bureau).</p>
          <div class="span-2"><button class="btn primary" [disabled]="uf.invalid || !nu.roles.length || busy.on()">Créer et inviter</button></div>
        </form>
        <form class="filters" (ngSubmit)="goUsers(0)"><label class="field inline"><span>Recherche</span><input type="search" name="q" [(ngModel)]="q" /></label><button class="btn">Rechercher</button></form>
        <app-state [loading]="users.loading()" [error]="users.error()" [empty]="!users.data()?.content?.length" emptyText="Aucun compte." (retry)="users.reload()">
          @for (u of users.data()?.content; track u.id) {
            <article class="card">
              <div class="row between"><div><strong>{{ u.displayName }}</strong> <small class="muted">{{ u.email }}</small>
                @if (u.demo) { <span class="badge" data-tone="demo">Fictif</span> }</div>
                <div class="row"><span class="badge" [attr.data-tone]="u.enabled ? 'ok' : 'bad'">{{ u.enabled ? 'Actif' : 'Désactivé' }}</span>
                  @if (!u.activated) { <span class="badge" data-tone="warn">Invitation non acceptée</span> }
                  @if (u.lockedUntil) { <span class="badge" data-tone="warn">Verrouillé jusqu’au {{ u.lockedUntil | when }}</span> }</div></div>
              <p class="small">Rôles : @for (r of u.roles; track r) { <span class="chip">{{ roleLabel(r) }}</span> } — dernière connexion {{ u.lastLoginAt | when }}
                @if (u.memberStatus) { — adhésion {{ u.memberNumber }} : <app-badge [value]="u.memberStatus" /> }</p>
              @if (u.id !== auth.user()?.id) {
                @if (editing() === u.id) {
                  <fieldset class="field"><legend>Rôles</legend>@for (r of roles(); track r.code) {
                    <label class="check"><input type="checkbox" [checked]="editRoles.includes(r.code!)" (change)="toggle(editRoles, r.code!)" /> {{ roleLabel(r.code) }}</label> }</fieldset>
                  <div class="row"><button type="button" class="btn small" (click)="editing.set(null)">Annuler</button><button type="button" class="btn small primary" (click)="saveRoles(u)">Enregistrer les rôles</button></div>
                } @else {
                  <div class="row">
                    <button type="button" class="btn small" (click)="editRoles = [...(u.roles ?? [])]; editing.set(u.id ?? null)">Modifier les rôles</button>
                    <button type="button" class="btn small" [class.danger]="u.enabled" (click)="setEnabled(u, !u.enabled)">{{ u.enabled ? 'Désactiver' : 'Réactiver' }}</button>
                    @if (!u.activated) { <button type="button" class="btn small" (click)="simple('/admin/users/' + u.id + '/invitation', 'Invitation renvoyée.')">Renvoyer l’invitation</button> }
                    @if (u.lockedUntil) { <button type="button" class="btn small" (click)="simple('/admin/users/' + u.id + '/unlock', 'Compte déverrouillé.')">Déverrouiller</button> }
                  </div>
                }
              } @else { <p class="small muted">Vous ne pouvez pas modifier vos propres rôles.</p> }
            </article>
          }
          <app-pager [page]="upage()" [totalPages]="users.data()?.totalPages ?? 0" [total]="users.data()?.totalElements ?? 0" (go)="goUsers($event)" />
        </app-state>
      }
      @case ('parametres') {
        <app-state [loading]="settings.loading()" [error]="settings.error()" (retry)="settings.reload()">
          @if (settings.data(); as s) {
            <form class="card grid-2" (ngSubmit)="saveSettings()" #sf="ngForm">
              <h2 class="span-2">Identité de la coopérative</h2>
              <label class="field span-2"><span>Nom</span><input name="n" [(ngModel)]="st.name" required /></label>
              <label class="field"><span>Forme juridique (selon les statuts)</span><input name="l" [(ngModel)]="st.legalForm" /></label>
              <label class="field"><span>Numéro d’immatriculation</span><input name="r" [(ngModel)]="st.registrationNumber" /></label>
              <label class="field"><span>Adresse</span><input name="a" [(ngModel)]="st.address" /></label>
              <label class="field"><span>Ville</span><input name="c" [(ngModel)]="st.city" /></label>
              <label class="field"><span>Téléphone</span><input name="p" [(ngModel)]="st.phone" /></label>
              <label class="field"><span>Courriel de contact</span><input type="email" name="e" [(ngModel)]="st.email" email /></label>
              <label class="check span-2"><input type="checkbox" name="ps" [(ngModel)]="st.publishPublicStats" /> Publier sur le site public le nombre de membres et de lots</label>
              <div class="span-2 alert" data-tone="info"><strong>Garde des fonds :</strong> {{ s.escrowStatement }}<br />
                <small>Cette mention ne peut pas être modifiée depuis l’interface : un séquestre ne peut être déclaré qu’après mise en place réelle d’un mécanisme juridique et bancaire et
                  une intégration vérifiée.</small></div>
              <div class="span-2"><button class="btn primary" [disabled]="sf.invalid || busy.on()">Enregistrer</button></div>
            </form>
          }
        </app-state>
        <section class="card"><h2>Durées de conservation</h2>
          <div class="table-wrap"><table><thead><tr><th>Données</th><th class="num">Durée</th><th>Fondement</th></tr></thead>
            <tbody>@for (p of retention(); track p.dataCategory) { <tr><td data-label="Données">{{ p.description || p.dataCategory }}</td><td data-label="Durée" class="num">{{ p.retentionMonths }} mois</td><td data-label="Fondement">{{ p.legalBasis }}</td></tr> }</tbody></table></div>
          <p class="small muted">Durées indicatives à faire valider au regard de la loi sénégalaise n° 2008-12 sur les données personnelles et des obligations comptables.</p>
        </section>
      }
      @case ('donnees') {
        <app-state [loading]="requests.loading()" [error]="requests.error()" [empty]="!requests.data()?.length" emptyText="Aucune demande." (retry)="requests.reload()">
          @for (r of requests.data(); track r.id) {
            <article class="card"><div class="row between"><strong>{{ r.reference }} — {{ r.type | label }}</strong><app-badge [value]="r.status" /></div>
              <p class="small muted">Membre {{ r.memberNumber }} · déposée le {{ r.createdAt | day }} · réponse attendue avant le {{ r.dueAt | day }}</p>
              <p class="pre">{{ r.details }}</p>
              @if (r.response) { <p class="small"><strong>Réponse :</strong> {{ r.response }}</p> }
              @if (r.status === 'RECUE' || r.status === 'EN_COURS') {
                <div class="row"><button type="button" class="btn small" (click)="decide(r, 'EN_COURS')">Prendre en charge</button>
                  <button type="button" class="btn small primary" (click)="decide(r, 'TRAITEE')">Clore (traitée)</button>
                  <button type="button" class="btn small danger" (click)="decide(r, 'REJETEE')">Rejeter</button></div>
              }</article>
          }
        </app-state>
      }
    }
  `,
  styles: `
    .checklist { margin: 0; padding-left: 1.25rem; display: grid; gap: .45rem; }
    .checklist li.done::marker { content: '✓  '; color: var(--ok, #2f7d4f); }
  `,
})
export class Admin implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly tabs = computed(() => [
    ...(this.auth.has('USER_ADMIN') ? [{ key: 'comptes' as Tab, label: 'Comptes et rôles' }] : []),
    ...(this.auth.has('SETTINGS_ADMIN') ? [{ key: 'parametres' as Tab, label: 'Paramètres' }] : []),
    ...(this.auth.has('DATA_REQUEST_HANDLE') ? [{ key: 'donnees' as Tab, label: 'Demandes sur les données' }] : []),
  ]);
  protected readonly tab = signal<Tab>('comptes');
  protected readonly users = new Loader<PageResponseUserResponse>();
  protected readonly settings = new Loader<CoopSettings>((s) => (this.st = { name: s.name ?? '', legalForm: s.legalForm ?? '', registrationNumber: s.registrationNumber ?? '',
    address: s.address ?? '', city: s.city ?? '', phone: s.phone ?? '', email: s.email ?? '', publishPublicStats: !!s.publishPublicStats }));
  protected readonly requests = new Loader<DataRequestResponse[]>();
  protected readonly setup = new Loader<BureauSetup>();
  protected readonly functions = [
    { key: 'presidents', label: 'Présidence' },
    { key: 'secretaries', label: 'Secrétariat' },
    { key: 'treasurers', label: 'Trésorerie' },
  ] as const;
  protected readonly roles = signal<RoleResponse[]>([]);
  protected readonly retention = signal<DataRetentionPolicy[]>([]);
  protected readonly editing = signal<number | null>(null);
  protected readonly upage = signal(0);
  protected readonly busy = new Busy();
  protected q = '';
  protected editRoles: string[] = [];
  protected nu = { email: '', displayName: '', memberId: null as number | null, roles: [] as string[] };
  protected st = { name: '', legalForm: '', registrationNumber: '', address: '', city: '', phone: '', email: '', publishPublicStats: false };

  ngOnInit(): void {
    this.tab.set(this.tabs()[0]?.key ?? 'comptes');
    if (this.auth.has('USER_ADMIN')) {
      this.goUsers(0);
      this.setup.load(() => this.api.get<BureauSetup>('/admin/bureau-setup'));
      this.api.get<RoleResponse[]>('/admin/roles').subscribe((r) => this.roles.set(r));
    }
    if (this.auth.has('SETTINGS_ADMIN')) this.settings.load(() => this.api.get<CoopSettings>('/admin/settings'));
    if (this.auth.hasAny('SETTINGS_ADMIN', 'DATA_REQUEST_HANDLE')) this.api.get<DataRetentionPolicy[]>('/admin/retention-policies').subscribe((r) => this.retention.set(r));
    if (this.auth.has('DATA_REQUEST_HANDLE')) this.requests.load(() => this.api.get<DataRequestResponse[]>('/admin/data-requests'));
  }

  holders(b: BureauSetup, key: 'presidents' | 'secretaries' | 'treasurers'): BureauHolder[] {
    return b[key] ?? [];
  }

  holderState(h: BureauHolder): string {
    if (!h.activated) return 'invitation non acceptée';
    if (!h.memberStatus) return 'compte du bureau';
    return h.memberStatus === 'ACTIF' ? 'adhésion active' : 'adhésion ' + label(h.memberStatus).toLowerCase();
  }

  roleLabel(code?: string): string {
    return code ? (ROLE_LABELS[code] ?? code) : '';
  }

  toggle(list: string[], code: string): void {
    const i = list.indexOf(code);
    if (i >= 0) list.splice(i, 1);
    else list.push(code);
  }

  goUsers(p: number): void {
    this.upage.set(p);
    this.users.load(() => this.api.get<PageResponseUserResponse>('/admin/users', { q: this.q.trim(), page: p, size: 25 }));
  }

  private run(obs: Observable<unknown>, msg: string, after?: () => void): void {
    this.busy.run(obs, () => { this.toasts.success(msg); after?.(); }, (e) => this.toasts.error(e));
  }

  createUser(): void {
    this.run(this.api.post('/admin/users', this.nu), 'Compte créé, invitation envoyée.', () => {
      this.nu = { email: '', displayName: '', memberId: null, roles: [] };
      this.users.reload();
    });
  }

  saveRoles(u: UserResponse): void {
    this.run(this.api.put(`/admin/users/${u.id}/roles`, { roles: this.editRoles }), 'Rôles modifiés ; les sessions de la personne sont fermées.', () => {
      this.editing.set(null);
      this.users.reload();
      this.setup.reload();
    });
  }

  async setEnabled(u: UserResponse, enabled: boolean): Promise<void> {
    const reason = await this.dialogs.ask({ title: enabled ? `Réactiver ${u.displayName}` : `Désactiver ${u.displayName}`, message: enabled ? undefined : 'Toutes ses sessions seront fermées immédiatement.', reasonLabel: 'Motif', danger: !enabled });
    if (reason === null) return;
    this.run(this.api.put(`/admin/users/${u.id}/enabled`, { enabled, reason }), enabled ? 'Compte réactivé.' : 'Compte désactivé.', () => this.users.reload());
  }

  simple(path: string, msg: string): void {
    this.run(this.api.post(path), msg, () => this.users.reload());
  }

  saveSettings(): void {
    this.run(this.api.put<CoopSettings>('/admin/settings', this.st), 'Paramètres enregistrés.', () => this.settings.reload());
  }

  async decide(r: DataRequestResponse, status: string): Promise<void> {
    const response = await this.dialogs.ask({ title: `Demande ${r.reference}`, reasonLabel: status === 'EN_COURS' ? 'Message au membre' : 'Réponse motivée au membre', reasonMinLength: 5, danger: status === 'REJETEE' });
    if (response === null) return;
    this.run(this.api.post(`/admin/data-requests/${r.id}/decision`, { status, response }), 'Réponse enregistrée.', () => this.requests.reload());
  }
}
