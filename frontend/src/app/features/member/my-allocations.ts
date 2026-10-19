import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api';
import { DrawVerification, MemberCampaignView } from '../../core/api-types';
import { Auth } from '../../core/auth';
import { Dialogs } from '../../core/dialog';
import { Loader } from '../../core/loader';
import { Toasts } from '../../core/toast';
import { Badge, PageHeader, StateView } from '../../ui/components';
import { DayPipe, LabelPipe, WhenPipe } from '../../ui/pipes';

@Component({
  selector: 'app-my-allocations',
  imports: [FormsModule, PageHeader, StateView, Badge, DayPipe, WhenPipe, LabelPipe],
  template: `
    <app-page-header title="Lots et attributions"
      subtitle="Critères, candidature, résultats publiés et vérification du tirage." />
    <app-state [loading]="list.loading()" [error]="list.error()" [empty]="!list.data()?.length"
      emptyText="Aucune procédure d’attribution publiée." (retry)="list.reload()">
      @for (c of list.data(); track c.id) {
        <article class="card">
          <div class="row between">
            <h2>{{ c.title }}</h2>
            <app-badge [value]="c.status" />
          </div>
          <p class="muted small">{{ c.reference }} · {{ c.method | label }} · date de référence {{ c.referenceDate | day }}</p>
          <h3>Critères d’éligibilité</h3>
          <p class="pre">{{ c.eligibilityText || '—' }}</p>
          <h3>Règles de la procédure</h3>
          <p class="pre">{{ c.rulesText || '—' }}</p>

          <h3>Ma candidature</h3>
          @if (c.myCandidacy && !c.myCandidacy.withdrawnAt) {
            <p>Déposée le {{ c.myCandidacy.appliedAt | when }} — éligibilité : <app-badge [value]="c.myCandidacy.eligibilityStatus" />
              @if (c.myCandidacy.eligibilityReason) { <br /><small>{{ c.myCandidacy.eligibilityReason }}</small> }</p>
          } @else if (c.myCandidacy?.withdrawnAt) {
            <p class="muted">Candidature retirée le {{ c.myCandidacy.withdrawnAt | when }}.</p>
          } @else {
            <p class="muted">Vous n’êtes pas candidat.</p>
          }
          @if (c.status === 'CANDIDATURES_OUVERTES') {
            <p class="small">Clôture des candidatures : {{ c.applicationClosesAt | when }}</p>
            @if (c.applied) {
              <button type="button" class="btn" (click)="withdraw(c)">Retirer ma candidature</button>
            } @else {
              <button type="button" class="btn primary" (click)="apply(c)">Déposer ma candidature</button>
            }
          }

          @if (c.myResult; as r) {
            <p class="alert" [attr.data-tone]="r.status === 'DEFINITIF' ? 'ok' : 'info'">
              Mon résultat : <strong>{{ r.lotReference ? 'lot ' + r.lotReference : 'liste d’attente' }}</strong>
              @if (r.drawRank) { (rang {{ r.drawRank }}) } — <app-badge [value]="r.status" />
            </p>
          }

          @if (c.seedCommitment) {
            <details>
              <summary>Empreintes publiées avant le tirage</summary>
              <dl class="dl mono small">
                <dt>Engagement sur la graine</dt><dd>{{ c.seedCommitment }}</dd>
                <dt>Empreinte des participants</dt><dd>{{ c.participantsDigest }}</dd>
                <dt>Empreinte des lots</dt><dd>{{ c.lotsDigest }}</dd>
                <dt>Algorithme</dt><dd>{{ c.algorithmVersion }}</dd>
                @if (c.revealedSeed) { <dt>Graine révélée</dt><dd>{{ c.revealedSeed }}</dd> }
                @if (c.publicEntropy) { <dt>Aléa public</dt><dd>{{ c.publicEntropy }}</dd> }
                @if (c.participantNumbers?.length) {
                  <dt>Participants retenus ({{ c.participantNumbers!.length }})</dt><dd>{{ c.participantNumbers!.join(' ; ') }}</dd>
                }
                @if (c.lotReferences?.length) { <dt>Lots</dt><dd>{{ c.lotReferences!.join(' ; ') }}</dd> }
              </dl>
              @if (c.revealedSeed) {
                <p class="small">Vérification indépendante, sans faire confiance au serveur, avec le script public
                  <span class="mono">tools/verifier_tirage.py</span> (Python 3, bibliothèque standard) :</p>
                <pre class="mono small code-block">{{ verifierCommand(c) }}</pre>
              }
            </details>
          }

          @if (c.publishedResults?.length) {
            <h3>Résultats publiés</h3>
            <p class="muted small">Seuls les numéros de membre sont publiés.</p>
            <div class="table-wrap"><table>
              <thead><tr><th>Rang</th><th>Membre</th><th>Lot</th><th>Statut</th></tr></thead>
              <tbody>
                @for (r of c.publishedResults; track $index) {
                  <tr><td data-label="Rang">{{ r.drawRank ?? '—' }}</td><td data-label="Membre">{{ r.memberNumber }}</td>
                    <td data-label="Lot">{{ r.lotReference || '—' }}</td><td data-label="Statut"><app-badge [value]="r.status" /></td></tr>
                }
              </tbody>
            </table></div>
            <div class="row">
              @if (c.method === 'TIRAGE_AU_SORT') {
                <button type="button" class="btn" (click)="verify(c)">Recalculer le tirage</button>
              }
              @if (c.applied) { <button type="button" class="btn" (click)="contest(c)">Contester</button> }
            </div>
            @if (verification()[c.id!]; as v) {
              <p class="alert" [attr.data-tone]="v.valid ? 'ok' : 'bad'" role="status">{{ v.message }}
                (engagement {{ v.commitmentMatches ? 'conforme' : 'non conforme' }}, participants
                {{ v.participantsDigestMatches ? 'conformes' : 'non conformes' }}, lots {{ v.lotsDigestMatches ? 'conformes' : 'non conformes' }})</p>
            }
          }
        </article>
      }
    </app-state>
  `,
})
export class MyAllocations implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly dialogs = inject(Dialogs);
  protected readonly auth = inject(Auth);
  protected readonly list = new Loader<MemberCampaignView[]>();
  protected readonly verification = signal<Record<number, DrawVerification>>({});

  ngOnInit(): void {
    this.list.load(() => this.api.get<MemberCampaignView[]>('/allocations/me'));
  }

  async apply(c: MemberCampaignView): Promise<void> {
    const ok = await this.dialogs.ask({ title: 'Déposer ma candidature ?', message: `Procédure « ${c.title} ». Votre éligibilité sera vérifiée par le bureau selon les critères publiés.`, confirmLabel: 'Déposer' });
    if (ok === null) return;
    this.api.post(`/allocations/${c.id}/apply`).subscribe({
      next: () => { this.toasts.success('Candidature enregistrée.'); this.list.reload(); },
      error: (e) => this.toasts.error(e),
    });
  }

  async withdraw(c: MemberCampaignView): Promise<void> {
    const ok = await this.dialogs.ask({ title: 'Retirer ma candidature ?', confirmLabel: 'Retirer', danger: true });
    if (ok === null) return;
    this.api.post(`/allocations/${c.id}/withdraw`).subscribe({
      next: () => { this.toasts.success('Candidature retirée.'); this.list.reload(); },
      error: (e) => this.toasts.error(e),
    });
  }

  async contest(c: MemberCampaignView): Promise<void> {
    const reason = await this.dialogs.ask({ title: 'Contester le résultat', message: 'Votre contestation sera examinée et tracée. Exposez précisément les faits.', reasonLabel: 'Motif de la contestation', reasonMinLength: 20, confirmLabel: 'Déposer la contestation' });
    if (reason === null) return;
    this.api.post(`/allocations/${c.id}/contests`, { resultId: c.myResult?.id ?? null, reason }).subscribe({
      next: () => this.toasts.success('Contestation déposée.'),
      error: (e) => this.toasts.error(e),
    });
  }

  verifierCommand(c: MemberCampaignView): string {
    const q = (v: string | null | undefined) => `"${(v ?? '').replace(/(["\\$`])/g, '\\$1')}"`;
    return ['python3 tools/verifier_tirage.py',
      `--campagne ${q(c.reference)}`, `--graine ${q(c.revealedSeed)}`, `--entropie ${q(c.publicEntropy)}`,
      `--participants ${q((c.participantNumbers ?? []).join(';'))}`, `--lots ${q((c.lotReferences ?? []).join(';'))}`,
      `--engagement ${q(c.seedCommitment)}`, `--empreinte-participants ${q(c.participantsDigest)}`,
      `--empreinte-lots ${q(c.lotsDigest)}`].join(' \\\n  ');
  }

  verify(c: MemberCampaignView): void {
    this.api.get<DrawVerification>(`/allocations/${c.id}/member-verification`).subscribe({
      next: (v) => this.verification.update((m) => ({ ...m, [c.id!]: v })),
      error: (e) => this.toasts.error(e),
    });
  }
}
