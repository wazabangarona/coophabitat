import { Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Dialogs } from '../core/dialog';
import { label, tone } from '../core/labels';
import { SIMULATION_NOTICE } from '../core/public-info';
import { Toasts } from '../core/toast';

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
  imports: [RouterLink],
  template: `
    <header class="page-header">
      <div>
        @if (back()) {
          <a class="back" [routerLink]="back()">← {{ backLabel() }}</a>
        }
        <h1>{{ title() }}</h1>
        @if (subtitle()) {
          <p class="muted">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly back = input<string>();
  readonly backLabel = input('Retour');
}

/** Gestion uniforme des états chargement / erreur / vide. */
@Component({
  selector: 'app-state',
  template: `
    @if (loading()) {
      <p class="state" role="status"><span class="spinner" aria-hidden="true"></span> Chargement…</p>
    } @else if (error()) {
      <div class="alert" data-tone="bad" role="alert">
        <p>{{ error() }}</p>
        @if (retryable()) {
          <button type="button" class="btn small" (click)="retry.emit()">Réessayer</button>
        }
      </div>
    } @else if (empty()) {
      <p class="state muted">{{ emptyText() }}</p>
    } @else {
      <ng-content />
    }
  `,
})
export class StateView {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyText = input('Aucun élément à afficher.');
  readonly retryable = input(true);
  readonly retry = output<void>();
}

@Component({
  selector: 'app-pager',
  template: `
    @if (totalPages() > 1) {
      <nav class="pager" aria-label="Pagination">
        <button type="button" class="btn small" [disabled]="page() === 0" (click)="go.emit(page() - 1)">Précédent</button>
        <span>Page {{ page() + 1 }} sur {{ totalPages() }} ({{ total() }} éléments)</span>
        <button type="button" class="btn small" [disabled]="page() + 1 >= totalPages()" (click)="go.emit(page() + 1)">
          Suivant
        </button>
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
  template: `
    <div class="kpi" [attr.data-tone]="tone()">
      <span class="kpi-label">{{ label() }}</span>
      <strong class="kpi-value">{{ value() }}</strong>
      @if (hint()) {
        <span class="kpi-hint">{{ hint() }}</span>
      }
    </div>
  `,
})
export class Kpi {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string>();
  readonly tone = input<string>('neutral');
}

@Component({
  selector: 'app-toasts',
  template: `
    <div class="toasts" aria-live="polite">
      @for (t of toasts.items(); track t.id) {
        <div class="toast" [attr.data-tone]="t.kind" [attr.role]="t.kind === 'erreur' ? 'alert' : 'status'">
          <span>{{ t.text }}</span>
          <button type="button" class="icon-btn" aria-label="Fermer" (click)="toasts.dismiss(t.id)">×</button>
        </div>
      }
    </div>
  `,
})
export class ToastHost {
  protected readonly toasts = inject(Toasts);
}

@Component({
  selector: 'app-dialog-host',
  imports: [FormsModule],
  template: `
    <dialog #dlg (cancel)="$event.preventDefault(); dialogs.close(null)" aria-labelledby="dlg-title">
      @if (dialogs.current(); as d) {
        <form method="dialog" (submit)="$event.preventDefault(); confirm()">
          <h2 id="dlg-title">{{ d.title }}</h2>
          @if (d.message) {
            <p class="pre">{{ d.message }}</p>
          }
          @if (d.reasonLabel) {
            <label class="field">
              <span>{{ d.reasonLabel }}</span>
              <textarea name="reason" rows="3" [(ngModel)]="reason" required [attr.minlength]="d.reasonMinLength ?? 5"></textarea>
              <small class="muted">Ce motif est enregistré dans le journal d’audit.</small>
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

export const UI = [Badge, SimBadge, PageHeader, StateView, Pager, Kpi] as const;
