import { Injectable, signal } from '@angular/core';
import { errorMessage } from './errors';

export interface Toast {
  id: number;
  kind: 'succes' | 'erreur' | 'info';
  text: string;
}

@Injectable({ providedIn: 'root' })
export class Toasts {
  private seq = 0;
  readonly items = signal<Toast[]>([]);

  success(text: string): void {
    this.push('succes', text);
  }

  info(text: string): void {
    this.push('info', text);
  }

  error(err: unknown): void {
    this.push('erreur', typeof err === 'string' ? err : errorMessage(err), 9000);
  }

  dismiss(id: number): void {
    this.items.update((l) => l.filter((t) => t.id !== id));
  }

  private push(kind: Toast['kind'], text: string, ms = 5000): void {
    const id = ++this.seq;
    this.items.update((l) => [...l, { id, kind, text }]);
    setTimeout(() => this.dismiss(id), ms);
  }
}
