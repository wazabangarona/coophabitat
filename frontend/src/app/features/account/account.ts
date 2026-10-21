import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, saveBlob } from '../../core/api';
import { DataRequestResponse, MemberResponse, SessionInfo } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Busy, Loader } from '../../core/loader';
import { OfflineSnapshot } from '../../core/offline';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, WhenPipe } from '../../ui/pipes';

@Component({
  selector: 'app-account',
  imports: [FormsModule, PageHeader, StateView, Badge, LabelPipe, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Mon compte" [subtitle]="auth.user()?.email" />

    @if (auth.isMember()) {
      <section class="card">
        <h2>Mes coordonnées</h2>
        <app-state [loading]="profile.loading()" [error]="profile.error()" (retry)="profile.reload()">
          @if (profile.data(); as p) {
            <dl class="dl">
              <dt>Numéro de membre</dt><dd>{{ p.memberNumber }}</dd>
              <dt>Nom</dt><dd>{{ p.firstName }} {{ p.lastName }}</dd>
              <dt>Statut</dt><dd><app-badge [value]="p.status" /></dd>
              <dt>Adhésion</dt><dd>{{ p.membershipDate | day }} — {{ p.memberType | label }}</dd>
            </dl>
            <form class="grid-2" (ngSubmit)="saveContact()" #cf="ngForm">
              <label class="field"><span>Adresse électronique</span><input type="email" name="email" [(ngModel)]="contact.email" email /></label>
              <label class="field"><span>Téléphone</span><input type="tel" name="phone" [(ngModel)]="contact.phone" autocomplete="tel" /></label>
              <label class="field"><span>Adresse</span><input name="address" [(ngModel)]="contact.address" /></label>
              <label class="field"><span>Ville</span><input name="city" [(ngModel)]="contact.city" /></label>
              <div class="span-2"><button class="btn primary" [disabled]="cf.invalid || busy.on()">Mettre à jour mes coordonnées</button></div>
            </form>
            <p class="muted small">Pour corriger votre nom ou votre type d’adhésion, déposez une demande de rectification ci-dessous.</p>
          }
        </app-state>
      </section>
    }

    <section class="card">
      <h2>Mot de passe</h2>
      <form class="grid-2" (ngSubmit)="changePassword()" #pf="ngForm">
        <label class="field"><span>Mot de passe actuel</span>
          <input type="password" name="cur" [(ngModel)]="pwd.current" required autocomplete="current-password" /></label>
        <label class="field"><span>Nouveau mot de passe (12 caractères minimum)</span>
          <input type="password" name="new" [(ngModel)]="pwd.next" required minlength="12" autocomplete="new-password" /></label>
        <div class="span-2"><button class="btn" [disabled]="pf.invalid || busy.on()">Changer le mot de passe</button></div>
      </form>
      <p class="muted small">Le changement ferme toutes vos sessions ouvertes.</p>
    </section>

    <section class="card">
      <div class="row between"><h2>Sessions ouvertes</h2>
        <button type="button" class="btn small danger" (click)="logoutAll()">Fermer toutes les sessions</button></div>
      <app-state [loading]="sessions.loading()" [error]="sessions.error()" (retry)="sessions.reload()">
        <ul class="plain">
          @for (s of sessions.data(); track s.id) {
            <li>{{ s.userAgent || 'Navigateur inconnu' }} — ouverte le {{ s.createdAt | when }}, expire le {{ s.expiresAt | when }}</li>
          }
        </ul>
      </app-state>
    </section>

    @if (auth.isMember()) {
      <section class="card">
        <h2>Notifications</h2>
        <p class="muted small">Les messages de sécurité (réinitialisation, activation) sont toujours envoyés.</p>
        @for (c of channels; track c) {
          <label class="check"><input type="checkbox" [checked]="prefs()[c]" (change)="setPref(c, $any($event.target).checked)" />
            {{ c | label }}</label>
        }
      </section>

      <section class="card">
        <h2>Consultation hors ligne</h2>
        <label class="check"><input type="checkbox" [checked]="offlineOn()" (change)="toggleOffline($any($event.target).checked)" />
          Conserver sur cet appareil une copie de ma situation de cotisations</label>
        <p class="muted small">La copie est datée, effacée à la déconnexion, et ne remplace jamais la situation à jour.
          Ne l’activez pas sur un appareil partagé.</p>
      </section>
    }

    @if (auth.isMember()) {
      <section class="card">
        <h2>Mes données personnelles</h2>
        <p>Vous pouvez obtenir une copie de vos données, demander leur rectification, vous opposer à un traitement ou
          demander leur suppression lorsque la loi le permet.</p>
        <div class="row"><button type="button" class="btn" (click)="exportData()">Télécharger mes données (JSON)</button></div>
        <form class="grid-2" (ngSubmit)="fileRequest()" #rf="ngForm">
          <label class="field"><span>Type de demande</span>
            <select name="type" [(ngModel)]="request.type" required>
              @for (t of requestTypes; track t) { <option [value]="t">{{ t | label }}</option> }
            </select></label>
          <label class="field span-2"><span>Précisions</span>
            <textarea name="details" rows="3" [(ngModel)]="request.details" required minlength="10"></textarea></label>
          <div class="span-2"><button class="btn" [disabled]="rf.invalid || busy.on()">Envoyer la demande</button></div>
        </form>
        <app-state [loading]="requests.loading()" [error]="requests.error()" [empty]="!requests.data()?.length"
          emptyText="Aucune demande déposée." (retry)="requests.reload()">
          <div class="table-wrap"><table>
            <thead><tr><th>Référence</th><th>Type</th><th>Statut</th><th>Déposée</th><th>Échéance de réponse</th><th>Réponse</th></tr></thead>
            <tbody>
              @for (r of requests.data(); track r.id) {
                <tr><td data-label="Référence">{{ r.reference }}</td><td data-label="Type">{{ r.type | label }}</td>
                  <td data-label="Statut"><app-badge [value]="r.status" /></td><td data-label="Déposée">{{ r.createdAt | day }}</td>
                  <td data-label="Échéance">{{ r.dueAt | day }}</td><td data-label="Réponse">{{ r.response || '—' }}</td></tr>
              }
            </tbody>
          </table></div>
        </app-state>
      </section>
    }
  `,
})
export class Account implements OnInit {
  protected readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  private readonly offline = inject(OfflineSnapshot);
  protected readonly busy = new Busy();
  protected readonly profile = new Loader<MemberResponse>((p) =>
    Object.assign(this.contact, { email: p.email ?? '', phone: p.phone ?? '', address: p.address ?? '', city: p.city ?? '' }),
  );
  protected readonly sessions = new Loader<SessionInfo[]>();
  protected readonly requests = new Loader<DataRequestResponse[]>();
  protected readonly prefs = signal<Record<string, boolean>>({});
  protected readonly offlineOn = signal(this.offline.enabled());
  /** Aucun fournisseur SMS n'est intégré : le canal n'est pas proposé. */
  protected readonly channels = ['EMAIL', 'IN_APP'];
  protected readonly requestTypes = ['ACCES', 'RECTIFICATION', 'OPPOSITION', 'SUPPRESSION', 'PORTABILITE'];
  protected contact = { email: '', phone: '', address: '', city: '' };
  protected pwd = { current: '', next: '' };
  protected request = { type: 'ACCES', details: '' };

  ngOnInit(): void {
    if (this.auth.isMember()) {
      this.profile.load(() => this.api.get<MemberResponse>('/me/profile'));
      this.requests.load(() => this.api.get<DataRequestResponse[]>('/me/data-requests'));
    }
    this.sessions.load(() => this.api.get<SessionInfo[]>('/auth/sessions'));
    if (this.auth.isMember()) {
      this.api.get<Record<string, boolean>>('/notifications/preferences').subscribe({ next: (p) => this.prefs.set(p) });
    }
  }

  saveContact(): void {
    this.busy.run(this.api.put<MemberResponse>('/me/contact', this.contact), (p) => {
      this.profile.data.set(p);
      this.toasts.success('Coordonnées mises à jour.');
    }, (e) => this.toasts.error(e));
  }

  changePassword(): void {
    this.busy.run(this.api.post('/auth/password', { currentPassword: this.pwd.current, newPassword: this.pwd.next }), () => {
      this.toasts.success('Mot de passe modifié. Reconnectez-vous.');
      this.auth.expire();
    }, (e) => this.toasts.error(e));
  }

  async logoutAll(): Promise<void> {
    const ok = await this.dialogs.ask({ title: 'Fermer toutes les sessions ?', message: 'Vous serez déconnecté de tous vos appareils, y compris celui-ci.', confirmLabel: 'Tout fermer', danger: true });
    if (ok !== null) this.auth.logout(true);
  }

  setPref(channel: string, enabled: boolean): void {
    this.api.put<Record<string, boolean>>(`/notifications/preferences/${channel}`, {}, { enabled }).subscribe({
      next: (p) => this.prefs.set(p),
      error: (e) => this.toasts.error(e),
    });
  }

  toggleOffline(on: boolean): void {
    this.offline.setEnabled(on);
    this.offlineOn.set(on);
    this.toasts.info(on ? 'La copie locale sera enregistrée à votre prochaine consultation.' : 'Copie locale effacée.');
  }

  exportData(): void {
    this.api.download('/me/export').subscribe({
      next: (r) => saveBlob(r.blob, r.filename === 'document' ? 'mes-donnees.json' : r.filename),
      error: (e) => this.toasts.error(e),
    });
  }

  fileRequest(): void {
    this.busy.run(this.api.post('/me/data-requests', this.request), () => {
      this.toasts.success('Demande enregistrée. Le bureau doit y répondre dans le délai indiqué.');
      this.request = { type: 'ACCES', details: '' };
      this.requests.reload();
    }, (e) => this.toasts.error(e));
  }
}
