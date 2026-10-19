import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { errorMessage } from '../../core/errors';

@Component({
  selector: 'app-forgot',
  imports: [FormsModule, RouterLink],
  template: `
    <section class="card narrow">
      <h1>Mot de passe oublié</h1>
      @if (sent()) {
        <p class="alert" data-tone="ok" role="status">Si un compte correspond à cette adresse, un lien de réinitialisation
          vient d’être envoyé. Il est valable pour une durée limitée et ne peut servir qu’une fois.</p>
        <a routerLink="/connexion">Retour à la connexion</a>
      } @else {
        <form (ngSubmit)="send()" #f="ngForm">
          <label class="field"><span>Adresse électronique du compte</span>
            <input type="email" name="email" [(ngModel)]="email" required email autocomplete="email" /></label>
          @if (error()) { <p class="alert" data-tone="bad" role="alert">{{ error() }}</p> }
          <button class="btn primary" [disabled]="f.invalid || busy()">Recevoir un lien</button>
        </form>
      }
    </section>
  `,
})
export class Forgot {
  private readonly api = inject(Api);
  protected email = '';
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

  send(): void {
    this.busy.set(true);
    this.api.post('/auth/password-reset/request', { email: this.email.trim() }).subscribe({
      next: () => this.sent.set(true),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
