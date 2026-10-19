import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { errorMessage } from '../../core/errors';

/** Définition du mot de passe : activation d'un compte invité ou réinitialisation. */
@Component({
  selector: 'app-set-password',
  imports: [FormsModule, RouterLink],
  template: `
    <section class="card narrow">
      <h1>{{ invitation ? 'Activer mon compte' : 'Choisir un nouveau mot de passe' }}</h1>
      @if (!token) {
        <p class="alert" data-tone="bad">Lien incomplet : ouvrez le lien reçu par courriel.</p>
      } @else if (done()) {
        <p class="alert" data-tone="ok" role="status">Mot de passe enregistré. Vous pouvez vous connecter.</p>
        <a class="btn primary" routerLink="/connexion">Se connecter</a>
      } @else {
        <form (ngSubmit)="save()" #f="ngForm">
          <label class="field"><span>Nouveau mot de passe</span>
            <input type="password" name="pwd" [(ngModel)]="pwd" required minlength="12" autocomplete="new-password" />
            <small class="muted">Au moins 12 caractères. Évitez une phrase contenant votre nom ou votre adresse.</small></label>
          <label class="field"><span>Confirmation</span>
            <input type="password" name="pwd2" [(ngModel)]="pwd2" required autocomplete="new-password" /></label>
          @if (pwd2 && pwd !== pwd2) { <p class="alert" data-tone="warn">Les deux saisies diffèrent.</p> }
          @if (error()) { <p class="alert" data-tone="bad" role="alert">{{ error() }}</p> }
          <button class="btn primary" [disabled]="f.invalid || pwd !== pwd2 || busy()">Enregistrer</button>
        </form>
      }
    </section>
  `,
})
export class SetPassword {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  protected readonly invitation = this.route.snapshot.data['mode'] === 'invitation';
  protected readonly token = this.route.snapshot.queryParamMap.get('jeton');
  protected pwd = '';
  protected pwd2 = '';
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<string | null>(null);

  save(): void {
    this.busy.set(true);
    this.error.set(null);
    const path = this.invitation ? '/auth/invitation/accept' : '/auth/password-reset/confirm';
    this.api.post(path, { token: this.token, newPassword: this.pwd }).subscribe({
      next: () => this.done.set(true),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
