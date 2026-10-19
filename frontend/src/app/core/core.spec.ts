import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { formatDate, formatMoney } from '../ui/pipes';
import { Auth } from './auth';
import { errorMessage } from './errors';
import { authInterceptor } from './interceptor';
import { label, tone } from './labels';
import { OfflineSnapshot } from './offline';

describe('formatage', () => {
  it('affiche les montants en francs CFA sans décimale', () => {
    const s = formatMoney(1250000);
    expect(s.replace(/\s/g, ' ')).toMatch(/1.250.000/);
    expect(s).toMatch(/F\s?CFA|XOF/);
    expect(formatMoney(null)).toBe('—');
  });

  it('affiche une date calendaire sans décalage de fuseau', () => {
    expect(formatDate('2026-01-31')).toContain('31');
    expect(formatDate(undefined)).toBe('—');
  });

  it('traduit les statuts et leur associe une tonalité', () => {
    expect(label('CONFIRME')).toBe('Confirmé');
    expect(label('VALEUR_INCONNUE')).toBe('Valeur inconnue');
    expect(tone('ECHOUE')).toBe('bad');
    expect(tone('EN_VERIFICATION')).toBe('warn');
  });

  it('produit un message français à partir des erreurs de l’API', () => {
    const e = new HttpErrorResponse({ status: 422, error: { message: 'Montant invalide', fieldErrors: [{ field: 'amount', message: 'doit être positif' }] } });
    expect(errorMessage(e)).toBe('Montant invalide (amount : doit être positif)');
    expect(errorMessage(new HttpErrorResponse({ status: 403 }))).toContain('autorisation');
  });
});

describe('copie hors ligne', () => {
  beforeEach(() => localStorage.clear());

  it('ne conserve rien sans l’accord explicite du membre', () => {
    const s = new OfflineSnapshot();
    s.save(1, { memberId: 1 }, []);
    expect(s.load(1)).toBeNull();
  });

  it('conserve une copie datée, propre à l’utilisateur, effacée à la demande', () => {
    const s = new OfflineSnapshot();
    s.setEnabled(true);
    s.save(7, { memberId: 3, outstanding: 25000 }, []);
    expect(s.load(7)?.summary.outstanding).toBe(25000);
    expect(s.load(7)?.savedAt).toBeTruthy();
    expect(s.load(8)).toBeNull();
    s.setEnabled(false);
    expect(s.load(7)).toBeNull();
  });
});

describe('session et intercepteur', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: Auth;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(Auth);
  });

  afterEach(() => ctrl.verify());

  async function login(): Promise<void> {
    const p = firstValueFrom(auth.login('a@b.sn', 'x'));
    ctrl.expectOne('/api/v1/auth/login').flush({ accessToken: 'jeton-1', user: { id: 1, permissions: ['SELF_SERVICE'], memberId: 4 } });
    await p;
  }

  it('garde le jeton en mémoire uniquement et l’ajoute aux appels de l’API', async () => {
    await login();
    expect(JSON.stringify(localStorage)).not.toContain('jeton-1');
    expect(JSON.stringify(sessionStorage)).not.toContain('jeton-1');
    http.get('/api/v1/me/summary').subscribe();
    const req = ctrl.expectOne('/api/v1/me/summary');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jeton-1');
    expect(req.request.headers.get('X-Requested-With')).toBe('CoopHabitat');
    req.flush({});
    expect(auth.isMember()).toBe(true);
    expect(auth.has('PAYMENT_REFUND')).toBe(false);
  });

  it('n’ajoute pas le jeton aux appels extérieurs', async () => {
    await login();
    http.get('https://exemple.invalid/x').subscribe();
    expect(ctrl.expectOne('https://exemple.invalid/x').request.headers.has('Authorization')).toBe(false);
  });

  it('renouvelle la session une seule fois sur une réponse 401 puis rejoue la requête', async () => {
    await login();
    let body: unknown;
    http.get('/api/v1/me/profile').subscribe((b) => (body = b));
    ctrl.expectOne('/api/v1/me/profile').flush({}, { status: 401, statusText: 'Unauthorized' });
    const refresh = ctrl.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.withCredentials).toBe(true);
    refresh.flush({ accessToken: 'jeton-2', user: { id: 1, permissions: [] } });
    const retry = ctrl.expectOne('/api/v1/me/profile');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer jeton-2');
    retry.flush({ ok: true });
    expect(body).toEqual({ ok: true });
  });

  it('ferme la session si le renouvellement échoue', async () => {
    await login();
    let failed = false;
    http.get('/api/v1/me/profile').subscribe({ error: () => (failed = true) });
    ctrl.expectOne('/api/v1/me/profile').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toBe(true);
    expect(auth.loggedIn()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });
});
