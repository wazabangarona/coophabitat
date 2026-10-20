import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { errorMessage } from '../../core/errors';
import { PublicInfoStore } from '../../core/public-info';
import { Icon, Photo } from '../../ui/components';

const PASSWORD_MIN = 12;

function samePassword(group: AbstractControl): ValidationErrors | null {
  const { password, confirm } = group.value as { password: string; confirm: string };
  return confirm && password !== confirm ? { mismatch: true } : null;
}

/** Demande d'adhésion en ligne : création du compte par la personne, validation de l'adhésion par le bureau. */
@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink, Icon, Photo],
  template: `
    <div class="auth">
      <section class="auth-panel">
        <div class="auth-card">
          <p class="eyebrow">Adhésion</p>
          <h1>Demander mon adhésion</h1>
          @if (done()) {
            <div class="alert" data-tone="ok" role="status">
              <strong>Demande enregistrée.</strong> Vous pouvez vous connecter dès maintenant avec votre adresse et votre
              mot de passe. Votre adhésion sera active après validation par le bureau : en attendant, les paiements et
              les candidatures restent fermés.</div>
            <p class="muted small">Si cette adresse avait déjà un compte, la connexion se fait avec son mot de passe
              habituel ; utilisez « Mot de passe oublié » au besoin.</p>
            <a class="btn primary block" routerLink="/connexion">Se connecter</a>
          } @else if (info.info() && !info.registrationOpen()) {
            <p class="alert" data-tone="info">Les demandes d’adhésion en ligne ne sont pas ouvertes pour cette coopérative.
              Adressez-vous à son bureau : il crée votre dossier et vous envoie un lien d’activation.</p>
            <a routerLink="/connexion">Retour à la connexion</a>
          } @else {
            <p class="lead-sm">Créez votre compte. Le bureau de la coopérative examine ensuite votre demande et active
              votre adhésion.</p>
            <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
              <div class="grid-2">
                <label class="field"><span>Prénom</span>
                  <input formControlName="firstName" autocomplete="given-name" required /></label>
                <label class="field"><span>Nom</span>
                  <input formControlName="lastName" autocomplete="family-name" required /></label>
              </div>
              <label class="field"><span>Adresse électronique</span>
                <input type="email" formControlName="email" autocomplete="email" inputmode="email" required />
                @if (form.controls.email.touched && form.controls.email.invalid) {
                  <small class="field-error">Saisissez une adresse électronique valide.</small>
                }</label>
              <div class="grid-2">
                <label class="field"><span>Téléphone <small class="muted">(facultatif)</small></span>
                  <input type="tel" formControlName="phone" autocomplete="tel" inputmode="tel" placeholder="+221 77 000 00 00" />
                  @if (form.controls.phone.touched && form.controls.phone.invalid) {
                    <small class="field-error">Numéro invalide : chiffres et espaces, 9 à 20 caractères.</small>
                  }</label>
                <label class="field"><span>Ville <small class="muted">(facultatif)</small></span>
                  <input formControlName="city" autocomplete="address-level2" /></label>
              </div>
              <label class="field"><span>Mot de passe</span>
                <input type="password" formControlName="password" autocomplete="new-password" required />
                <small class="muted">{{ min }} caractères minimum. Une phrase de plusieurs mots est plus sûre et plus facile à retenir.</small></label>
              <label class="field"><span>Confirmer le mot de passe</span>
                <input type="password" formControlName="confirm" autocomplete="new-password" required />
                @if (form.controls.confirm.touched && form.hasError('mismatch')) {
                  <small class="field-error">Les deux mots de passe ne sont pas identiques.</small>
                }</label>
              <label class="check"><input type="checkbox" formControlName="consent" />
                <span>J’accepte que la coopérative enregistre ces informations pour traiter ma demande d’adhésion.</span></label>
              @if (error()) { <p class="alert" data-tone="bad" role="alert">{{ error() }}</p> }
              <button class="btn primary block" [disabled]="form.invalid || busy()">
                @if (busy()) { <span class="spinner" aria-hidden="true"></span> }
                {{ busy() ? 'Envoi…' : 'Créer mon compte' }}</button>
            </form>
            <div class="auth-links">
              <a routerLink="/connexion">J’ai déjà un compte</a>
            </div>
          }
          <p class="auth-foot"><app-icon name="shield-check" />Votre mot de passe est chiffré ; personne au bureau ne peut le lire.</p>
        </div>
      </section>
      <div class="auth-visual"><app-photo slot="auth" sizes="50vw" /></div>
    </div>
  `,
})
export class Register {
  private readonly api = inject(Api);
  protected readonly info = inject(PublicInfoStore);
  protected readonly min = PASSWORD_MIN;
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      firstName: ['', [Validators.required, Validators.maxLength(100)]],
      lastName: ['', [Validators.required, Validators.maxLength(100)]],
      email: ['', [Validators.required, Validators.email, Validators.maxLength(200)]],
      phone: ['', Validators.pattern(/^\+?[0-9 ]{9,20}$/)],
      city: ['', Validators.maxLength(100)],
      password: ['', [Validators.required, Validators.minLength(PASSWORD_MIN), Validators.maxLength(128)]],
      confirm: ['', Validators.required],
      consent: [false, Validators.requiredTrue],
    },
    { validators: samePassword },
  );

  submit(): void {
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    this.api.post('/public/registration', {
      firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(),
      phone: v.phone.trim() || null, city: v.city.trim() || null, password: v.password, consent: v.consent,
    }).subscribe({
      next: () => this.done.set(true),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
