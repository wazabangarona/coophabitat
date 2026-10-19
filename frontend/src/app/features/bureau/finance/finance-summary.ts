import { Component } from '@angular/core';
import { Transparency } from '../../member/transparency';

/** Même synthèse que celle publiée aux membres : le bureau voit exactement ce que les membres voient. */
@Component({
  selector: 'app-finance-summary',
  imports: [Transparency],
  template: `<app-transparency />`,
})
export class FinanceSummary {}
