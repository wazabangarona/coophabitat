import { Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Dialogs } from '../core/dialog';
import { label, tone } from '../core/labels';
import { SIMULATION_NOTICE } from '../core/public-info';
import { Toasts } from '../core/toast';
import { ICONS } from './icons-data';
import { PHOTOS, PhotoCredit, SLOTS } from './photos';
import { formatMoney } from './pipes';

/** Icône vectorielle (Lucide). Purement décorative : le texte voisin porte toujours le sens. */
@Component({
  selector: 'app-icon',
  host: { class: 'icon', 'aria-hidden': 'true', '[innerHTML]': 'svg()' },
  template: '',
})
export class Icon {
  readonly name = input.required<string>();
  private readonly sanitizer = inject(DomSanitizer);
  // Tracés constants embarqués dans l'application : aucune donnée externe n'est injectée ici.
  protected readonly svg = computed(() =>
    this.sanitizer.bypassSecurityTrustHtml(
      `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">${ICONS[this.name()] ?? ''}</svg>`,
    ),
  );
}

/** Monogramme CoopHabitat : deux volumes sous une même toiture. */
@Component({
  selector: 'app-brand-mark',
  host: { class: 'brand-mark', 'aria-hidden': 'true' },
  template: `
    <svg viewBox="0 0 32 32" width="32" height="32">
      <rect width="32" height="32" rx="8" fill="#152B3C" />
      <path d="M7 15.5 16 8l9 7.5" fill="none" stroke="#E8F1EB" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M10 14v10h4.6v-5.2h2.8V24H22V14" fill="none" stroke="#E8F1EB" stroke-width="1.8" stroke-linejoin="round" />
      <path d="M10 24h12" stroke="#7CC3A6" stroke-width="1.8" stroke-linecap="round" />
    </svg>
  `,
})
export class BrandMark {}

/** Pastille de statut : le texte porte toujours l'information (la couleur n'est qu'un complément). */
@Component({
  selector: 'app-badge',
  template: `<span class="badge" [attr.data-tone]="t()">{{ text() }}</span>`,
})
export class Badge {
  readonly value = input<string | null | undefined>();
  readonly text = computed(() => label(this.value()));
  readonly t = computed(() => tone(this.value()));
}

@Component({
  selector: 'app-sim-badge',
  template: `<span class="badge" data-tone="sim" title="${SIMULATION_NOTICE}">Simulation</span>`,
})
export class SimBadge {}

@Component({
  selector: 'app-page-header',
  imports: [RouterLink, Icon],
  template: `
    <header class="page-header">
      <div>
        @if (back()) {
          <a class="back" [routerLink]="back()"><app-icon name="arrow-left" />{{ backLabel() }}</a>
        }
        @if (eyebrow()) {
          <p class="eyebrow">{{ eyebrow() }}</p>
        }
        <h1>{{ title() }}</h1>
        @if (subtitle()) {
          <p>{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly eyebrow = input<string>();
  readonly back = input<string>();
  readonly backLabel = input('Retour');
}

/** Gestion uniforme des états chargement / erreur / vide. */
@Component({
  selector: 'app-state',
  imports: [Icon],
  template: `
    @if (loading()) {
      <div class="loading" role="status">
        <p class="loading-label"><span class="spinner" aria-hidden="true"></span> Chargement…</p>
        <span class="skeleton w-80" aria-hidden="true"></span>
        <span class="skeleton w-60" aria-hidden="true"></span>
        <span class="skeleton w-40" aria-hidden="true"></span>
      </div>
    } @else if (error()) {
      <div class="alert" data-tone="bad" role="alert">
        <div class="error-box">
          <app-icon name="circle-x" />
          <div>
            <p>{{ error() }}</p>
            @if (retryable()) {
              <button type="button" class="btn small" (click)="retry.emit()"><app-icon name="refresh-cw" />Réessayer</button>
            }
          </div>
        </div>
      </div>
    } @else if (empty()) {
      <div class="empty">
        <span class="empty-icon"><app-icon [name]="emptyIcon()" /></span>
        <p class="empty-title">{{ emptyText() }}</p>
        @if (emptyHint()) {
          <p class="small">{{ emptyHint() }}</p>
        }
      </div>
    } @else {
      <div class="fade-in"><ng-content /></div>
    }
  `,
})
export class StateView {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyText = input('Aucun élément à afficher.');
  readonly emptyHint = input<string>();
  readonly emptyIcon = input('inbox');
  readonly retryable = input(true);
  readonly retry = output<void>();
}

@Component({
  selector: 'app-pager',
  imports: [Icon],
  template: `
    @if (totalPages() > 1) {
      <nav class="pager" aria-label="Pagination">
        <span class="pager-info">Page {{ page() + 1 }} sur {{ totalPages() }} · {{ total() }} éléments</span>
        <span class="pager-actions">
          <button type="button" class="btn small" [disabled]="page() === 0" (click)="go.emit(page() - 1)">
            <app-icon name="chevron-left" />Précédent
          </button>
          <button type="button" class="btn small" [disabled]="page() + 1 >= totalPages()" (click)="go.emit(page() + 1)">
            Suivant<app-icon name="chevron-right" />
          </button>
        </span>
      </nav>
    }
  `,
})
export class Pager {
  readonly page = input(0);
  readonly totalPages = input(0);
  readonly total = input(0);
  readonly go = output<number>();
}

/** Carte d'indicateur avec sa définition (aucun chiffre sans explication de ce qu'il mesure). */
@Component({
  selector: 'app-kpi',
  host: { class: 'kpi', '[attr.data-tone]': 'tone()' },
  template: `
    <span class="kpi-label">{{ label() }}</span>
    <strong class="kpi-value">{{ value() }}</strong>
    @if (hint()) {
      <span class="kpi-hint">{{ hint() }}</span>
    }
  `,
})
export class Kpi {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string>();
  readonly tone = input<string>('neutral');
}

/** Montant en francs CFA : chiffres tabulaires, devise discrète, jamais coupé. */
@Component({
  selector: 'app-amount',
  template: `<span class="amount">{{ figure() }}@if (currency()) {<span class="cur">FCFA</span>}</span>`,
})
export class Amount {
  readonly value = input<number | string | null | undefined>();
  protected readonly figure = computed(() => formatMoney(this.value()).replace(/\s?F\s?CFA$/u, '').replace(/\s?XOF$/u, ''));
  protected readonly currency = computed(() => this.figure() !== '—');
}

/** Jauge horizontale pour un taux réellement calculé par le serveur. */
@Component({
  selector: 'app-meter',
  template: `
    <div class="meter">
      <div class="meter-track" role="img" [attr.aria-label]="label() + ' : ' + pct() + ' %'">
        <div class="meter-fill" [style.width.%]="pct()"></div>
      </div>
      <div class="meter-legend"><span>{{ label() }}</span><strong>{{ pct() }} %</strong></div>
    </div>
  `,
})
export class Meter {
  readonly value = input<number | null | undefined>(0);
  readonly label = input('');
  protected readonly pct = computed(() => Math.max(0, Math.min(100, Math.round(Number(this.value() ?? 0) * 10) / 10)));
}

export interface DistributionItem {
  label: string;
  value: number;
  tone: string;
}

/** Répartition d'un effectif réel (lots, adhérents) en barre empilée avec légende chiffrée. */
@Component({
  selector: 'app-distribution',
  template: `
    <div class="distribution">
      @if (total() > 0) {
        <div class="dist-bar" role="img" [attr.aria-label]="summary()">
          @for (i of visible(); track i.label) {
            <span class="tone" [attr.data-tone]="i.tone" [style.flex-grow]="i.value"></span>
          }
        </div>
      }
      <ul class="dist-legend">
        @for (i of items(); track i.label) {
          <li class="tone" [attr.data-tone]="i.tone"><span>{{ i.label }}</span><strong>{{ i.value }}</strong></li>
        }
      </ul>
    </div>
  `,
})
export class Distribution {
  readonly items = input<DistributionItem[]>([]);
  protected readonly total = computed(() => this.items().reduce((s, i) => s + i.value, 0));
  protected readonly visible = computed(() => this.items().filter((i) => i.value > 0));
  protected readonly summary = computed(() => this.items().map((i) => `${i.label} : ${i.value}`).join(', '));
}

/**
 * Photographie d'illustration avec ses crédits. Sans fichier disponible (ou en cas d'échec de chargement),
 * un visuel de remplacement sobre est affiché : jamais d'image cassée.
 */
@Component({
  selector: 'app-photo',
  template: `
    <figure class="photo" [class]="extraClass()" [class.whole]="photo()?.kind === 'plan' || photo()?.kind === 'maquette'"
      [class.plan]="photo()?.kind === 'plan'">
      @if (photo(); as p) {
        @if (!failed()) {
          <picture>
            <source type="image/webp" [attr.srcset]="srcset()" [attr.sizes]="sizes()" />
            <img [src]="'/images/' + key() + '-1400.jpg'" [alt]="p.alt" [attr.width]="p.width" [attr.height]="p.height"
              [attr.loading]="priority() ? 'eager' : 'lazy'" [attr.fetchpriority]="priority() ? 'high' : null" decoding="async"
              [style.object-position]="p.focus ?? '50% 50%'" (error)="failed.set(true)" />
          </picture>
          <figcaption>{{ p.kind === 'plan' ? 'Plan d’illustration' : p.kind === 'maquette' ? 'Maquette d’illustration' : 'Image d’illustration' }} · {{ p.credit }}</figcaption>
        }
      }
      @if (!photo() || failed()) {
        <div class="photo-fallback" aria-hidden="true">
          <svg viewBox="0 0 240 160" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round">
            <path d="M14 140h212" stroke-linecap="round" />
            <path d="M30 140V78l46-30 46 30v62" />
            <path d="M122 140V96h86v44" />
            <path d="M116 96h98" stroke-linecap="round" />
            <path d="M58 140v-30h36v30M136 112h20v16h-20zM172 112h20v16h-20zM60 82h32" />
          </svg>
        </div>
        @if (fallbackLabel()) {
          <span class="photo-tag">{{ fallbackLabel() }}</span>
        }
      }
    </figure>
  `,
})
export class Photo {
  /** Emplacement de l'application (voir SLOTS) ou nom de photographie. */
  readonly slot = input.required<string>();
  readonly priority = input(false);
  readonly sizes = input('(min-width: 900px) 50vw, 100vw');
  readonly fallbackLabel = input('Visuel d’illustration');
  readonly extraClass = input('');
  protected readonly failed = signal(false);
  protected readonly key = computed(() => (SLOTS as Record<string, string | null>)[this.slot()] ?? this.slot());
  protected readonly photo = computed<PhotoCredit | null>(() => PHOTOS[this.key()] ?? null);
  protected readonly srcset = computed(() =>
    [800, 1400, 2000].map((w) => `/images/${this.key()}-${w}.webp ${w}w`).join(', '),
  );
}

const TOAST_ICON: Record<string, string> = { succes: 'circle-check', erreur: 'circle-x', info: 'info' };

@Component({
  selector: 'app-toasts',
  imports: [Icon],
  template: `
    <div class="toasts" aria-live="polite">
      @for (t of toasts.items(); track t.id) {
        <div class="toast" [attr.data-tone]="t.kind" [attr.role]="t.kind === 'erreur' ? 'alert' : 'status'">
          <app-icon [name]="icon(t.kind)" />
          <span>{{ t.text }}</span>
          <button type="button" class="icon-btn" aria-label="Fermer" (click)="toasts.dismiss(t.id)"><app-icon name="x" /></button>
        </div>
      }
    </div>
  `,
})
export class ToastHost {
  protected readonly toasts = inject(Toasts);
  protected icon(kind: string): string {
    return TOAST_ICON[kind] ?? 'info';
  }
}

@Component({
  selector: 'app-dialog-host',
  imports: [FormsModule, Icon],
  template: `
    <dialog #dlg (cancel)="$event.preventDefault(); dialogs.close(null)" aria-labelledby="dlg-title">
      @if (dialogs.current(); as d) {
        <form method="dialog" (submit)="$event.preventDefault(); confirm()">
          <span class="dialog-icon" [class.danger]="d.danger"><app-icon [name]="d.danger ? 'triangle-alert' : 'circle-help'" /></span>
          <h2 id="dlg-title">{{ d.title }}</h2>
          @if (d.message) {
            <p class="pre">{{ d.message }}</p>
          }
          @if (d.reasonLabel) {
            <label class="field">
              <span>{{ d.reasonLabel }}</span>
              <textarea name="reason" rows="3" [(ngModel)]="reason" required [attr.minlength]="d.reasonMinLength ?? 5"></textarea>
              <small>Ce motif est enregistré dans le journal d’audit ({{ d.reasonMinLength ?? 5 }} caractères minimum).</small>
            </label>
          }
          <div class="row end">
            <button type="button" class="btn" (click)="dialogs.close(null)">Annuler</button>
            <button type="submit" class="btn" [class.danger]="d.danger" [class.primary]="!d.danger" [disabled]="!valid()">
              {{ d.confirmLabel ?? 'Confirmer' }}
            </button>
          </div>
        </form>
      }
    </dialog>
  `,
})
export class DialogHost {
  protected readonly dialogs = inject(Dialogs);
  private readonly dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  protected reason = '';
  protected readonly tick = signal(0);

  constructor() {
    effect(() => {
      const open = this.dialogs.current() !== null;
      const el = this.dlg().nativeElement;
      if (open && !el.open) {
        this.reason = '';
        el.showModal();
      } else if (!open && el.open) el.close();
    });
  }

  valid(): boolean {
    const d = this.dialogs.current();
    if (!d?.reasonLabel) return true;
    return this.reason.trim().length >= (d.reasonMinLength ?? 5);
  }

  confirm(): void {
    if (this.valid()) this.dialogs.close(this.dialogs.current()?.reasonLabel ? this.reason.trim() : '');
  }
}

export const UI = [Badge, SimBadge, PageHeader, StateView, Pager, Kpi, Icon, Amount] as const;
