import { Pipe, PipeTransform } from '@angular/core';
import { label } from '../core/labels';

const XOF = new Intl.NumberFormat('fr-SN', { style: 'currency', currency: 'XOF', maximumFractionDigits: 0 });
const NUM = new Intl.NumberFormat('fr-SN', { maximumFractionDigits: 2 });
const TZ = 'Africa/Dakar';
const DATE = new Intl.DateTimeFormat('fr-SN', { dateStyle: 'medium', timeZone: TZ });
const DATETIME = new Intl.DateTimeFormat('fr-SN', { dateStyle: 'medium', timeStyle: 'short', timeZone: TZ });

/**
 * Montants : l'API transmet des entiers en francs CFA (XOF, sans subdivision). L'interface ne fait que les
 * afficher ; aucun calcul financier n'est effectué dans le navigateur.
 */
export function formatMoney(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  return Number.isFinite(n) ? XOF.format(n) : String(v);
}

export function formatDate(v: string | null | undefined, withTime = false): string {
  if (!v) return '—';
  // Une date seule (AAAA-MM-JJ) est un jour calendaire : on l'affiche telle quelle, sans conversion de fuseau.
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) {
    const [y, m, d] = v.split('-').map(Number);
    return DATE.format(new Date(Date.UTC(y, m - 1, d, 12)));
  }
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : (withTime ? DATETIME : DATE).format(d);
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(v: number | string | null | undefined): string {
    return formatMoney(v);
  }
}

@Pipe({ name: 'num' })
export class NumPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return v === null || v === undefined ? '—' : NUM.format(v);
  }
}

@Pipe({ name: 'day' })
export class DayPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    return formatDate(v);
  }
}

@Pipe({ name: 'when' })
export class WhenPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    return formatDate(v, true);
  }
}

@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    return label(v);
  }
}

@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    if (v === null || v === undefined) return '—';
    if (v < 1024) return `${v} o`;
    if (v < 1024 * 1024) return `${NUM.format(v / 1024)} Ko`;
    return `${NUM.format(v / 1024 / 1024)} Mo`;
  }
}

export const PIPES = [MoneyPipe, NumPipe, DayPipe, WhenPipe, LabelPipe, BytesPipe] as const;
