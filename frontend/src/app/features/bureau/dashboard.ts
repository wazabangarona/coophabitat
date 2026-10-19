import { Component, OnInit, inject } from '@angular/core';
import { Api } from '../../core/api';
import { Dashboard, Indicator } from '../../core/api-types';
import { Loader } from '../../core/loader';
import { PageHeader, StateView } from '../../ui/components';
import { DayPipe, WhenPipe, formatMoney } from '../../ui/pipes';

const NATURE: Record<string, string> = {
  REEL: 'Réel', DECLARE: 'Déclaré', SIMULATION: 'Simulation', EN_ATTENTE: 'En attente de confirmation', COMPTAGE: '',
};

@Component({
  selector: 'app-bureau-dashboard',
  imports: [PageHeader, StateView, DayPipe, WhenPipe],
  template: `
    <app-page-header title="Tableau de bord" subtitle="Indicateurs calculés par le serveur, limités à ce que votre rôle permet de voir.">
      <button type="button" class="btn small" (click)="d.reload()">Actualiser</button>
    </app-page-header>
    <app-state [loading]="d.loading()" [error]="d.error()" (retry)="d.reload()">
      @if (d.data(); as db) {
        @for (n of db.notices; track $index) { <p class="alert" [attr.data-tone]="n.includes('SIMULATION') ? 'sim' : 'info'">{{ n }}</p> }
        @for (s of db.sections; track s.key) {
          <section aria-labelledby="sec-{{ s.key }}">
            <h2 id="sec-{{ s.key }}">{{ s.title }}</h2>
            <div class="kpis">
              @for (i of s.indicators; track i.key) {
                <div class="kpi" [attr.data-tone]="tone(i)" [title]="i.definition ?? ''">
                  <span class="kpi-label">{{ i.label }}</span>
                  <strong class="kpi-value">{{ value(i) }}</strong>
                  @if (nature(i)) { <span class="kpi-hint">{{ nature(i) }}</span> }
                  @if (i.definition) { <span class="kpi-hint">{{ i.definition }}</span> }
                </div>
              }
            </div>
          </section>
        }
        <p class="muted small">Situation au {{ db.asOf | day }}, calculée le {{ db.computedAt | when }}.</p>
      }
    </app-state>
  `,
})
export class BureauDashboard implements OnInit {
  private readonly api = inject(Api);
  protected readonly d = new Loader<Dashboard>();

  ngOnInit(): void {
    this.d.load(() => this.api.get<Dashboard>('/dashboard/bureau'));
  }

  value(i: Indicator): string {
    if (i.value === null || i.value === undefined) return '—';
    if (i.unit === 'XOF') return formatMoney(i.value as number);
    if (i.unit === '%') return `${i.value} %`;
    return typeof i.value === 'number' ? `${i.value}` : String(i.value);
  }

  nature(i: Indicator): string {
    return NATURE[i.nature ?? ''] ?? '';
  }

  tone(i: Indicator): string {
    if (i.nature === 'SIMULATION') return 'sim';
    if (i.nature === 'EN_ATTENTE') return 'warn';
    if (i.nature === 'DECLARE') return 'info';
    return 'neutral';
  }
}
