import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Banners } from '../layout/banners';
import { PublicInfoStore } from '../core/public-info';
import { ReceiptVerify } from '../features/public/receipt-verify';
import { Badge } from './components';

describe('composants', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  it('la pastille affiche le statut en texte', async () => {
    const f = TestBed.createComponent(Badge);
    f.componentRef.setInput('value', 'EN_VERIFICATION');
    await f.whenStable();
    expect(f.nativeElement.textContent).toContain('En vérification');
  });

  it('affiche les bandeaux démonstration et simulation selon la configuration du serveur', async () => {
    const store = TestBed.inject(PublicInfoStore);
    store.info.set({ demoData: true, paymentSimulationEnabled: true });
    const f = TestBed.createComponent(Banners);
    await f.whenStable();
    const text = f.nativeElement.textContent as string;
    expect(text).toContain('fictifs');
    expect(text).toContain('SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ');
    store.info.set({ demoData: false, paymentSimulationEnabled: false });
    await f.whenStable();
    expect(f.nativeElement.textContent).not.toContain('SIMULATION');
  });

  it('signale un reçu simulé comme sans valeur et ne prétend pas à une signature', async () => {
    const f = TestBed.createComponent(ReceiptVerify);
    await f.whenStable();
    const c = f.componentInstance as unknown as { number: string; code: string; verify(): void };
    c.number = 'REC-1';
    c.code = 'ABC';
    c.verify();
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/v1/public/receipts/verify')
      .flush({ valid: true, receiptNumber: 'REC-1', amount: 1000, simulated: true });
    await f.whenStable();
    const text = f.nativeElement.textContent as string;
    expect(text).toContain('SIMULATION');
    expect(text).toContain('aucune valeur');
    expect(text).toContain('ne constitue pas une signature');
  });
});
