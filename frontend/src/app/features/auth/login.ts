import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Auth } from '../../core/auth';
import { errorMessage } from '../../core/errors';
import { PublicInfoStore } from '../../core/public-info';
import { Icon, Photo } from '../../ui/components';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, Icon, Photo],
  template: `
    <div class="auth">
      <section class="auth-panel">
        <div class="auth-card">
          <p class="eyebrow">Espace membre</p>
          <h1>Connexion</h1>
          <p class="lead-sm">Accédez à votre situation, à vos reçus et aux documents de la coopérative.</p>
          @if (expired) { <p class="alert" data-tone="warn">Votre session a expiré. Reconnectez-vous.</p> }
          @if (info.demo()) {
            <p class="alert" data-tone="demo">Environnement de démonstration : les comptes fictifs utilisent le domaine
              <code>demo.coophabitat.invalid</code> et le mot de passe défini par l’administrateur de la démonstration.</p>
          }
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <label class="field"><span>Adresse électronique</span>
              <input type="email" formControlName="email" autocomplete="username" inputmode="email" required />
              @if (form.controls.email.touched && form.controls.email.invalid) {
                <small class="field-error">Saisissez une adresse électronique valide, par exemple nom&#64;domaine.sn.</small>
              }</label>
            <label class="field"><span>Mot de passe</span>
              <input type="password" formControlName="password" autocomplete="current-password" required /></label>
            @if (error()) { <p class="alert" data-tone="bad" role="alert">{{ error() }}</p> }
            <button class="btn primary block" [disabled]="form.invalid || busy()">
              @if (busy()) { <span class="spinner" aria-hidden="true"></span> }
              {{ busy() ? 'Connexion…' : 'Se connecter' }}</button>
          </form>
          <div class="auth-links">
            <a routerLink="/mot-de-passe-oublie">Mot de passe oublié ?</a>
            <a routerLink="/verification-recu">Vérifier un reçu</a>
          </div>
          <p class="auth-foot"><app-icon name="lock" />Après plusieurs échecs, le compte est temporairement verrouillé pour le protéger.</p>
        </div>
      </section>
      <div class="auth-visual"><app-photo slot="auth" sizes="50vw" /></div>
    </div>
  `,
})
export class Login {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly info = inject(PublicInfoStore);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly expired = this.route.snapshot.queryParamMap.get('session') === 'expiree';
  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email.trim(), password).subscribe({
      next: () => {
        const back = this.route.snapshot.queryParamMap.get('retour');
        void this.router.navigateByUrl(back && back.startsWith('/') && !back.startsWith('//') ? back : this.auth.homeUrl());
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
