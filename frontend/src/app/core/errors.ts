import { HttpErrorResponse } from '@angular/common/http';

export interface ApiErrorBody {
  status?: number;
  code?: string;
  message?: string;
  fieldErrors?: { field: string; message: string }[];
}

/** Message lisible en français à partir d'une erreur HTTP de l'API. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return navigator.onLine
        ? 'Le serveur est injoignable. Réessayez dans un instant.'
        : 'Vous êtes hors ligne : cette action nécessite une connexion.';
    }
    const body = err.error as ApiErrorBody | null;
    if (body && typeof body === 'object' && body.message) {
      const fields = body.fieldErrors?.map((f) => `${f.field} : ${f.message}`).join(' ; ');
      return fields ? `${body.message} (${fields})` : body.message;
    }
    if (err.status === 403) return "Vous n'avez pas l'autorisation d'effectuer cette action.";
    if (err.status === 404) return 'Élément introuvable.';
    if (err.status === 429) return 'Trop de tentatives. Patientez avant de réessayer.';
    return `Erreur inattendue (code ${err.status}).`;
  }
  return err instanceof Error ? err.message : 'Erreur inattendue.';
}
