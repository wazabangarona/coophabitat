import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { DialogHost, ToastHost } from './ui/components';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHost, DialogHost],
  template: `<router-outlet /><app-toasts /><app-dialog-host />`,
})
export class App {}
