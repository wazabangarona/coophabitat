import { Routes } from '@angular/router';
import { authGuard, guestGuard, memberGuard, permissionGuard } from './core/guards';
import { AppShell } from './layout/app-shell';
import { PublicLayout } from './layout/public-layout';

const t = (s: string) => `${s} · CoopHabitat`;

export const routes: Routes = [
  {
    path: '',
    component: PublicLayout,
    children: [
      { path: '', title: t('Accueil'), loadComponent: () => import('./features/public/home').then((m) => m.Home) },
      { path: 'projets', title: t('Projets'), loadComponent: () => import('./features/public/projects').then((m) => m.PublicProjects) },
      { path: 'documents-publics', title: t('Documents publics'), loadComponent: () => import('./features/public/public-documents').then((m) => m.PublicDocuments) },
      { path: 'verification-recu', title: t('Vérifier un reçu'), loadComponent: () => import('./features/public/receipt-verify').then((m) => m.ReceiptVerify) },
      { path: 'credits-photographiques', title: t('Crédits photographiques'), loadComponent: () => import('./features/public/photo-credits').then((m) => m.PhotoCredits) },
      { path: 'connexion', title: t('Connexion'), canActivate: [guestGuard], loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
      { path: 'inscription', title: t('Demande d’adhésion'), canActivate: [guestGuard], loadComponent: () => import('./features/auth/register').then((m) => m.Register) },
      { path: 'mot-de-passe-oublie', title: t('Mot de passe oublié'), loadComponent: () => import('./features/auth/forgot').then((m) => m.Forgot) },
      { path: 'reinitialisation', title: t('Nouveau mot de passe'), data: { mode: 'reset' }, loadComponent: () => import('./features/auth/set-password').then((m) => m.SetPassword) },
      { path: 'activation', title: t('Activation du compte'), data: { mode: 'invitation' }, loadComponent: () => import('./features/auth/set-password').then((m) => m.SetPassword) },
      { path: 'acces-refuse', title: t('Accès refusé'), loadComponent: () => import('./features/public/not-found').then((m) => m.AccessDenied) },
    ],
  },
  {
    path: '',
    component: AppShell,
    canActivate: [authGuard],
    children: [
      {
        path: 'compte',
        children: [
          { path: '', title: t('Mon compte'), loadComponent: () => import('./features/account/account').then((m) => m.Account) },
          { path: 'notifications', title: t('Notifications'), loadComponent: () => import('./features/account/inbox').then((m) => m.Inbox) },
        ],
      },
      {
        path: 'espace',
        canActivate: [memberGuard],
        children: [
          { path: '', title: t('Ma situation'), loadComponent: () => import('./features/member/member-home').then((m) => m.MemberHome) },
          { path: 'cotisations', title: t('Mes cotisations'), loadComponent: () => import('./features/member/my-contributions').then((m) => m.MyContributions) },
          { path: 'paiements', title: t('Paiements et reçus'), loadComponent: () => import('./features/member/my-payments').then((m) => m.MyPayments) },
          { path: 'paiements/simulation/:reference', title: t('Simulation de paiement'), loadComponent: () => import('./features/member/payment-simulation').then((m) => m.PaymentSimulation) },
          { path: 'paiements/suivi/:reference', title: t('Suivi du paiement'), loadComponent: () => import('./features/member/payment-status').then((m) => m.PaymentStatus) },
          { path: 'attributions', title: t('Lots et attributions'), loadComponent: () => import('./features/member/my-allocations').then((m) => m.MyAllocations) },
          { path: 'assemblees', title: t('Assemblées'), loadComponent: () => import('./features/member/my-assemblies').then((m) => m.MyAssemblies) },
          { path: 'assemblees/:id', title: t('Assemblée'), loadComponent: () => import('./features/member/my-assemblies').then((m) => m.MyAssemblyDetail) },
          { path: 'transparence', title: t('Transparence financière'), loadComponent: () => import('./features/member/transparency').then((m) => m.Transparency) },
          { path: 'documents', title: t('Documents'), loadComponent: () => import('./features/member/my-documents').then((m) => m.MyDocuments) },
        ],
      },
      {
        path: 'bureau',
        children: [
          { path: '', title: t('Tableau de bord'), canActivate: [permissionGuard('DASHBOARD_BUREAU')], loadComponent: () => import('./features/bureau/dashboard').then((m) => m.BureauDashboard) },
          { path: 'membres', title: t('Membres'), canActivate: [permissionGuard('MEMBER_READ_ALL')], loadComponent: () => import('./features/bureau/members').then((m) => m.Members) },
          { path: 'adhesions', title: t('Demandes d’adhésion'), canActivate: [permissionGuard('MEMBER_READ_ALL')], loadComponent: () => import('./features/bureau/applications').then((m) => m.Applications) },
          { path: 'membres/:id', title: t('Fiche membre'), canActivate: [permissionGuard('MEMBER_READ_ALL')], loadComponent: () => import('./features/bureau/member-detail').then((m) => m.MemberDetail) },
          { path: 'cotisations', title: t('Cotisations'), canActivate: [permissionGuard('PLAN_READ')], loadComponent: () => import('./features/bureau/plans').then((m) => m.Plans) },
          { path: 'paiements', title: t('Paiements'), canActivate: [permissionGuard('PAYMENT_READ_ALL')], loadComponent: () => import('./features/bureau/payments').then((m) => m.BureauPayments) },
          { path: 'lots', title: t('Projets et lots'), canActivate: [permissionGuard('LOT_READ_ALL')], loadComponent: () => import('./features/bureau/lots').then((m) => m.Lots) },
          { path: 'lots/:id', title: t('Lot'), canActivate: [permissionGuard('LOT_READ_ALL')], loadComponent: () => import('./features/bureau/lots').then((m) => m.LotDetail) },
          { path: 'attributions', title: t('Attributions'), canActivate: [permissionGuard('ALLOCATION_READ_ALL')], loadComponent: () => import('./features/bureau/allocations').then((m) => m.Allocations) },
          { path: 'attributions/:id', title: t('Procédure d’attribution'), canActivate: [permissionGuard('ALLOCATION_READ_ALL')], loadComponent: () => import('./features/bureau/allocation-detail').then((m) => m.AllocationDetail) },
          { path: 'assemblees', title: t('Assemblées'), canActivate: [permissionGuard('ASSEMBLY_MANAGE')], loadComponent: () => import('./features/bureau/assemblies').then((m) => m.Assemblies) },
          { path: 'assemblees/:id', title: t('Assemblée'), canActivate: [permissionGuard('ASSEMBLY_MANAGE')], loadComponent: () => import('./features/bureau/assembly-detail').then((m) => m.AssemblyDetail) },
          { path: 'finances', canActivate: [permissionGuard('ACCOUNTING_READ', 'EXPENSE_CREATE')], loadChildren: () => import('./features/bureau/finance/finance.routes').then((m) => m.FINANCE_ROUTES) },
          { path: 'documents', title: t('Documents'), canActivate: [permissionGuard('DOCUMENT_MANAGE', 'DOCUMENT_READ_BUREAU')], loadComponent: () => import('./features/bureau/documents').then((m) => m.BureauDocuments) },
          { path: 'notifications', title: t('Notifications'), canActivate: [permissionGuard('NOTIFICATION_MANAGE')], loadComponent: () => import('./features/bureau/notifications').then((m) => m.BureauNotifications) },
          { path: 'audit', title: t('Journal d’audit'), canActivate: [permissionGuard('AUDIT_READ')], loadComponent: () => import('./features/bureau/audit').then((m) => m.Audit) },
          { path: 'administration', title: t('Administration'), canActivate: [permissionGuard('USER_ADMIN', 'SETTINGS_ADMIN', 'DATA_REQUEST_HANDLE')], loadComponent: () => import('./features/bureau/admin').then((m) => m.Admin) },
        ],
      },
    ],
  },
  { path: '**', title: t('Page introuvable'), component: PublicLayout, children: [{ path: '', loadComponent: () => import('./features/public/not-found').then((m) => m.NotFound) }] },
];
