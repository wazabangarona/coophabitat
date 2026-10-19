/* Fichier généré par scripts/generate-api-types.mjs : ne pas modifier à la main. */
/* eslint-disable */

export interface AccountBalance {
  code?: string;
  label?: string;
  type?: string;
  treasury?: boolean;
  debit?: number;
  credit?: number;
  balance?: number;
}

export interface AdjustmentRequest {
  type: "REMISE_PENALITE" | "ANNULATION" | "CORRECTION_MONTANT";
  newAmount: number;
  reason: string;
  decisionReference?: string;
}

export interface AdjustmentResponse {
  id?: number;
  obligationId?: number;
  type?: "REMISE_PENALITE" | "ANNULATION" | "CORRECTION_MONTANT";
  previousAmount?: number;
  newAmount?: number;
  reason?: string;
  decisionReference?: string;
  performedBy?: number;
  performedAt?: string;
}

export interface AdoptRequest {
  adoptedReference: string;
}

export interface AgendaEntry {
  title: string;
  description?: string;
}

export interface AgendaItemResponse {
  id?: number;
  position?: number;
  title?: string;
  description?: string;
}

export interface AgendaRequest {
  items: AgendaEntry[];
}

export interface AllocationResponse {
  obligationId?: number;
  periodLabel?: string;
  amount?: number;
  createdAt?: string;
  reversal?: boolean;
}

export interface ApprovalItem {
  approverId?: number;
  approverName?: string;
  decision?: "APPROUVEE" | "REJETEE";
  comment?: string;
  decidedAt?: string;
}

export interface ApprovalRequest {
  step: "VALIDATION_LISTE" | "VALIDATION_PV" | "DECISION_COMMISSION" | "ANNULATION_RESULTAT";
  decision: "APPROUVE" | "REFUSE";
  comment?: string;
  targetId?: number;
}

export interface ApprovalResponse {
  id?: number;
  step?: "VALIDATION_LISTE" | "VALIDATION_PV" | "DECISION_COMMISSION" | "ANNULATION_RESULTAT";
  approverId?: number;
  approverName?: string;
  decision?: "APPROUVE" | "REFUSE";
  comment?: string;
  targetId?: number;
  decidedAt?: string;
}

export interface ArchivePlanRequest {
  validTo: string;
  reason: string;
}

export interface ArchiveRequest {
  reason: string;
}

export interface AssemblyRequest {
  title: string;
  type: "ORDINAIRE" | "EXTRAORDINAIRE";
  scheduledAt: string;
  location?: string;
  modality: "PRESENTIEL" | "EN_LIGNE" | "MIXTE";
  quorumRule?: string;
  convocationText?: string;
}

export interface AssemblyResponse {
  id?: number;
  reference?: string;
  title?: string;
  type?: "ORDINAIRE" | "EXTRAORDINAIRE";
  scheduledAt?: string;
  location?: string;
  modality?: "PRESENTIEL" | "EN_LIGNE" | "MIXTE";
  status?: "PROGRAMMEE" | "CONVOQUEE" | "TENUE" | "CLOTUREE" | "ANNULEE";
  quorumRule?: string;
  convocationText?: string;
  convenedAt?: string;
  heldAt?: string;
  cancelReason?: string;
  agenda?: AgendaItemResponse[];
  resolutions?: ResolutionResponse[];
  convocationDocumentId?: number;
  publishedMinutesDocumentId?: number;
  quorum?: QuorumInfo;
  demo?: boolean;
  version?: number;
}

export interface Assessment {
  sequence?: number;
  assessmentDate?: string;
  base?: number;
  amount?: number;
}

export interface AssignPlanRequest {
  planId: number;
}

export interface AttendanceEntry {
  memberId: number;
  status: "PRESENT" | "REPRESENTE" | "ABSENT" | "EXCUSE";
  proxyMemberId?: number;
}

export interface AttendanceRequest {
  entries: AttendanceEntry[];
}

export interface AttendanceResponse {
  memberId?: number;
  memberNumber?: string;
  memberName?: string;
  status?: "PRESENT" | "REPRESENTE" | "ABSENT" | "EXCUSE";
  proxyMemberId?: number;
  proxyMemberNumber?: string;
  recordedAt?: string;
}

export interface AuditItem {
  id?: number;
  occurredAt?: string;
  category?: "SECURITE" | "FINANCE" | "MEMBRE" | "ATTRIBUTION" | "LOT" | "DOCUMENT" | "ASSEMBLEE" | "ADMIN" | "NOTIFICATION" | "DONNEES";
  action?: string;
  actorUserId?: number;
  actorLabel?: string;
  entityType?: string;
  entityId?: string;
  details?: string;
  ipAddress?: string;
  hash?: string;
}

export interface BudgetExecutionLine {
  category?: string;
  label?: string;
  direction?: "RECETTE" | "DEPENSE";
  planned?: number;
  realized?: number;
  committed?: number;
  remaining?: number;
  executionRatePercent?: number;
}

export interface BudgetLineRequest {
  category: string;
  direction: "RECETTE" | "DEPENSE";
  plannedAmount: number;
}

export interface BudgetRequest {
  fiscalYear?: number;
  label: string;
  lines: BudgetLineRequest[];
}

export interface BudgetResponse {
  id?: number;
  fiscalYear?: number;
  label?: string;
  status?: "BROUILLON" | "ADOPTE" | "CLOTURE";
  adoptedReference?: string;
  lines?: BudgetExecutionLine[];
  plannedRevenue?: number;
  realizedRevenue?: number;
  plannedExpense?: number;
  realizedExpense?: number;
  committedExpense?: number;
}

export interface CampaignRequest {
  title: string;
  description?: string;
  method: "TIRAGE_AU_SORT" | "COMMISSION";
  eligibilityText?: string;
  rulesText?: string;
  minSeniorityMonths?: number;
  requireUpToDate?: boolean;
  maxArrearsAmount: number;
  allowedMemberTypes: string[];
  referenceDate?: string;
  applicationClosesAt?: string;
  requiredApprovals?: number;
  sessionDate?: string;
  sessionLocation?: string;
}

export interface CampaignResponse {
  id?: number;
  reference?: string;
  title?: string;
  description?: string;
  method?: "TIRAGE_AU_SORT" | "COMMISSION";
  status?: "BROUILLON" | "CRITERES_PUBLIES" | "CANDIDATURES_OUVERTES" | "CANDIDATURES_CLOSES" | "LISTE_PUBLIEE" | "VALIDEE" | "EXECUTEE" | "PV_VALIDE" | "RESULTATS_PUBLIES" | "ARCHIVEE" | "ANNULEE";
  eligibilityText?: string;
  rulesText?: string;
  minSeniorityMonths?: number;
  requireUpToDate?: boolean;
  maxArrearsAmount?: number;
  allowedMemberTypes?: string[];
  referenceDate?: string;
  applicationOpensAt?: string;
  applicationClosesAt?: string;
  requiredApprovals?: number;
  sessionDate?: string;
  sessionLocation?: string;
  criteriaPublishedAt?: string;
  listPublishedAt?: string;
  frozenAt?: string;
  participantsDigest?: string;
  lotsDigest?: string;
  seedCommitment?: string;
  revealedSeed?: string;
  publicEntropy?: string;
  algorithmVersion?: string;
  executedAt?: string;
  resultsPublishedAt?: string;
  cancelReason?: string;
  lotIds?: number[];
  candidateCount?: number;
  eligibleCount?: number;
  demo?: boolean;
  version?: number;
}

export interface CancelRequest {
  reason: string;
}

export interface CandidateResponse {
  id?: number;
  memberId?: number;
  memberNumber?: string;
  memberName?: string;
  appliedAt?: string;
  eligibilityStatus?: "A_VERIFIER" | "ELIGIBLE" | "NON_ELIGIBLE";
  eligibilityReason?: string;
  checkedAt?: string;
  withdrawnAt?: string;
}

export interface ChainVerification {
  valid?: boolean;
  checkedEvents?: number;
  firstInvalidEventId?: number;
  message?: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface ChannelState {
  channel?: "EMAIL" | "SMS" | "IN_APP";
  configured?: boolean;
  message?: string;
}

export interface CommissionCandidate {
  userId?: number;
  displayName?: string;
  roles?: string[];
}

export interface CommissionDecisionRequest {
  candidateId: number;
  lotId?: number;
  justification: string;
}

export interface CommissionEntry {
  userId: number;
  role?: string;
  conflictDeclared?: boolean;
  conflictDetails?: string;
}

export interface CommissionMember {
  campaignId?: number;
  userId?: number;
  roleInCommission?: string;
  conflictDeclared?: boolean;
  conflictDetails?: string;
}

export interface CommissionRequest {
  members: CommissionEntry[];
}

export interface ContactUpdateRequest {
  email?: string;
  phone?: string;
  address?: string;
  city?: string;
}

export interface ContestDecisionRequest {
  accept?: boolean;
  decision: string;
}

export interface ContestRequest {
  resultId?: number;
  reason: string;
}

export interface ContestResponse {
  id?: number;
  reference?: string;
  campaignId?: number;
  memberId?: number;
  memberNumber?: string;
  resultId?: number;
  lotId?: number;
  reason?: string;
  status?: "DEPOSEE" | "EN_EXAMEN" | "REJETEE" | "ACCEPTEE";
  decision?: string;
  decidedAt?: string;
  createdAt?: string;
}

export interface CoopSettings {
  id?: number;
  name?: string;
  legalForm?: string;
  registrationNumber?: string;
  address?: string;
  city?: string;
  phone?: string;
  email?: string;
  escrowStatement?: string;
  escrowIntegrated?: boolean;
  publishPublicStats?: boolean;
  demo?: boolean;
  updatedAt?: string;
}

export interface CreatedMember {
  member?: MemberResponse;
  invitationSent?: boolean;
}

export interface CreateIntentRequest {
  amount: number;
  obligationId?: number;
  provider: "MOCK" | "WAVE" | "SENDFLASH" | "MANUEL";
}

export interface CreateMemberRequest {
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  address?: string;
  city?: string;
  membershipDate: string;
  memberType: "FONDATEUR" | "ORDINAIRE" | "BENEFICIAIRE";
  contributionPlanId?: number;
  notes?: string;
  createAccount?: boolean;
}

export interface Dashboard {
  role?: string;
  asOf?: string;
  computedAt?: string;
  sections?: Section[];
  notices?: string[];
}

export interface DataRequestCreate {
  type: "ACCES" | "RECTIFICATION" | "OPPOSITION" | "SUPPRESSION" | "PORTABILITE";
  details: string;
}

export interface DataRequestDecision {
  status: "RECUE" | "EN_COURS" | "TRAITEE" | "REJETEE";
  response: string;
}

export interface DataRequestResponse {
  id?: number;
  reference?: string;
  memberId?: number;
  memberNumber?: string;
  type?: "ACCES" | "RECTIFICATION" | "OPPOSITION" | "SUPPRESSION" | "PORTABILITE";
  details?: string;
  status?: "RECUE" | "EN_COURS" | "TRAITEE" | "REJETEE";
  response?: string;
  createdAt?: string;
  dueAt?: string;
  handledAt?: string;
}

export interface DataRetentionPolicy {
  dataCategory?: string;
  retentionMonths?: number;
  legalBasis?: string;
  description?: string;
}

export interface DeclarationRequest {
  accountCode: string;
  balanceDate: string;
  balance: number;
  statementReference?: string;
}

export interface DeclarationResponse {
  id?: number;
  accountCode?: string;
  balanceDate?: string;
  balance?: number;
  source?: "DECLARATION" | "RELEVE_IMPORTE";
  statementReference?: string;
  declaredBy?: number;
  declaredAt?: string;
}

export interface DocumentItem {
  id?: number;
  reference?: string;
  title?: string;
  category?: "STATUTS" | "REGLEMENT" | "CONVOCATION" | "COMPTE_RENDU_AG" | "PV_ATTRIBUTION" | "RECU" | "RAPPORT_FINANCIER" | "JUSTIFICATIF_DEPENSE" | "DOCUMENT_FONCIER" | "DOCUMENT_MEMBRE" | "AUTRE";
  visibility?: "PUBLIC" | "MEMBRES" | "BUREAU" | "PRIVE_MEMBRE";
  ownerMemberId?: number;
  relatedType?: string;
  relatedId?: number;
  originalFilename?: string;
  contentType?: string;
  sizeBytes?: number;
  sha256?: string;
  generated?: boolean;
  publishedAt?: string;
  createdAt?: string;
  archivedAt?: string;
  demo?: boolean;
}

export interface DrawVerification {
  valid?: boolean;
  algorithmVersion?: string;
  commitmentMatches?: boolean;
  participantsDigestMatches?: boolean;
  lotsDigestMatches?: boolean;
  resultsMatch?: boolean;
  recomputedOrder?: string[];
  recordedOrder?: string[];
  message?: string;
}

export interface EnabledRequest {
  enabled?: boolean;
  reason: string;
}

export interface EntryResponse {
  id?: number;
  reference?: string;
  entryDate?: string;
  label?: string;
  sourceType?: string;
  sourceId?: number;
  reversalOfId?: number;
  reversedById?: number;
  simulated?: boolean;
  createdAt?: string;
  lines?: LineResponse[];
}

export interface ExecuteRequest {
  publicEntropy?: string;
}

export interface ExpenseDecisionRequest {
  decision: "APPROUVEE" | "REJETEE";
  comment?: string;
}

export interface ExpensePayRequest {
  paymentAccount: string;
  paymentDate?: string;
}

export interface ExpenseRequest {
  category: "FOURNITURES" | "DEPLACEMENT" | "HONORAIRES_ETUDES" | "FRAIS_ADMINISTRATIFS" | "FRAIS_BANCAIRES" | "ACQUISITION_TERRAIN" | "CONSTRUCTION" | "DIVERS";
  label: string;
  supplier?: string;
  amount: number;
  expenseDate: string;
  justificationDocumentId?: number;
}

export interface ExpenseResponse {
  id?: number;
  reference?: string;
  category?: "FOURNITURES" | "DEPLACEMENT" | "HONORAIRES_ETUDES" | "FRAIS_ADMINISTRATIFS" | "FRAIS_BANCAIRES" | "ACQUISITION_TERRAIN" | "CONSTRUCTION" | "DIVERS";
  categoryLabel?: string;
  label?: string;
  supplier?: string;
  amount?: number;
  expenseDate?: string;
  status?: "BROUILLON" | "SOUMISE" | "APPROUVEE" | "REJETEE" | "PAYEE" | "ANNULEE";
  paymentAccount?: string;
  justificationDocumentId?: number;
  requiredApprovals?: number;
  approvalsCount?: number;
  createdBy?: number;
  createdAt?: string;
  submittedAt?: string;
  paidAt?: string;
  paidBy?: number;
  accountingEntryId?: number;
  cancelReason?: string;
  demo?: boolean;
  approvals?: ApprovalItem[];
}

export interface FinancialAccount {
  code?: string;
  label?: string;
  accountType?: string;
  treasury?: boolean;
  active?: boolean;
}

export interface GenerationResult {
  asOf?: string;
  obligationsCreated?: number;
  penaltiesCreated?: number;
  membersProcessed?: number;
}

export interface ImportResult {
  imported?: number;
  duplicates?: number;
  errors?: string[];
}

export interface InAppItem {
  id?: number;
  subject?: string;
  body?: string;
  createdAt?: string;
  readAt?: string;
  relatedType?: string;
  relatedId?: number;
}

export interface Indicator {
  key?: string;
  label?: string;
  value?: unknown;
  unit?: string;
  nature?: string;
  definition?: string;
}

export interface IntentResponse {
  id?: number;
  reference?: string;
  memberId?: number;
  obligationId?: number;
  amount?: number;
  currency?: string;
  provider?: "MOCK" | "WAVE" | "SENDFLASH" | "MANUEL";
  channel?: string;
  status?: "INITIE" | "EN_ATTENTE" | "CONFIRME" | "ECHOUE" | "EXPIRE" | "REMBOURSE" | "EN_VERIFICATION";
  simulated?: boolean;
  launchUrl?: string;
  externalReference?: string;
  failureReason?: string;
  createdBy?: number;
  verifiedBy?: number;
  createdAt?: string;
  expiresAt?: string;
  confirmedAt?: string;
  transactionId?: number;
  receiptNumber?: string;
}

export interface LineResponse {
  accountCode?: string;
  accountLabel?: string;
  debit?: number;
  credit?: number;
  memberId?: number;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LotHistoryItem {
  id?: number;
  fromStatus?: "EN_PREPARATION" | "DISPONIBLE" | "RESERVE" | "ATTRIBUE" | "LITIGIEUX" | "INDISPONIBLE";
  toStatus?: "EN_PREPARATION" | "DISPONIBLE" | "RESERVE" | "ATTRIBUE" | "LITIGIEUX" | "INDISPONIBLE";
  reason?: string;
  sourceType?: string;
  sourceId?: number;
  changedBy?: number;
  changedAt?: string;
}

export interface LotRequest {
  reference: string;
  projectId: number;
  lotType: "TERRAIN" | "VILLA" | "APPARTEMENT" | "DUPLEX" | "LOCAL";
  region: string;
  departement: string;
  commune: string;
  locality?: string;
  areaM2: number;
  latitude?: number;
  longitude?: number;
  coordinatesSource?: string;
  coordinatesVerified?: boolean;
  description?: string;
}

export interface LotResponse {
  id?: number;
  reference?: string;
  projectId?: number;
  projectName?: string;
  lotType?: "TERRAIN" | "VILLA" | "APPARTEMENT" | "DUPLEX" | "LOCAL";
  region?: string;
  departement?: string;
  commune?: string;
  locality?: string;
  areaM2?: number;
  latitude?: number;
  longitude?: number;
  coordinatesSource?: string;
  coordinatesVerified?: boolean;
  status?: "EN_PREPARATION" | "DISPONIBLE" | "RESERVE" | "ATTRIBUE" | "LITIGIEUX" | "INDISPONIBLE";
  description?: string;
  createdAt?: string;
  updatedAt?: string;
  demo?: boolean;
  version?: number;
}

export interface LotsRequest {
  lotIds: number[];
}

export interface LotStatusRequest {
  status: "EN_PREPARATION" | "DISPONIBLE" | "RESERVE" | "ATTRIBUE" | "LITIGIEUX" | "INDISPONIBLE";
  reason: string;
}

export interface ManualEntryRequest {
  entryDate: string;
  label: string;
  justification: string;
  lines: ManualLine[];
}

export interface ManualLine {
  accountCode: string;
  debit: number;
  credit: number;
  memberId?: number;
}

export interface ManualPaymentRequest {
  memberId: number;
  amount: number;
  obligationId?: number;
  method: string;
  externalReference?: string;
  receivedOn: string;
  comment?: string;
}

export interface MatchRequest {
  entryId: number;
}

export interface MemberCampaignView {
  id?: number;
  reference?: string;
  title?: string;
  method?: "TIRAGE_AU_SORT" | "COMMISSION";
  status?: "BROUILLON" | "CRITERES_PUBLIES" | "CANDIDATURES_OUVERTES" | "CANDIDATURES_CLOSES" | "LISTE_PUBLIEE" | "VALIDEE" | "EXECUTEE" | "PV_VALIDE" | "RESULTATS_PUBLIES" | "ARCHIVEE" | "ANNULEE";
  eligibilityText?: string;
  rulesText?: string;
  referenceDate?: string;
  applicationClosesAt?: string;
  seedCommitment?: string;
  participantsDigest?: string;
  lotsDigest?: string;
  algorithmVersion?: string;
  revealedSeed?: string;
  publicEntropy?: string;
  applied?: boolean;
  myCandidacy?: CandidateResponse;
  myResult?: ResultResponse;
  publishedResults?: PublishedResult[];
  participantNumbers?: string[];
  lotReferences?: string[];
}

export interface MemberFinancialSummary {
  memberId?: number;
  memberNumber?: string;
  planName?: string;
  planAmount?: number;
  totalDue?: number;
  totalPaid?: number;
  outstanding?: number;
  overdue?: number;
  penaltiesOutstanding?: number;
  credit?: number;
  overdueCount?: number;
  oldestOverdueDueDate?: string;
  upToDate?: boolean;
  upcoming?: ObligationResponse[];
  asOf?: string;
}

export interface MemberListItem {
  id?: number;
  memberNumber?: string;
  fullName?: string;
  city?: string;
  memberType?: "FONDATEUR" | "ORDINAIRE" | "BENEFICIAIRE";
  status?: "EN_ATTENTE" | "ACTIF" | "SUSPENDU" | "RADIE";
  membershipDate?: string;
  demo?: boolean;
}

export interface MemberResponse {
  id?: number;
  memberNumber?: string;
  firstName?: string;
  lastName?: string;
  email?: string;
  phone?: string;
  address?: string;
  city?: string;
  membershipDate?: string;
  memberType?: "FONDATEUR" | "ORDINAIRE" | "BENEFICIAIRE";
  status?: "EN_ATTENTE" | "ACTIF" | "SUSPENDU" | "RADIE";
  contributionPlanId?: number;
  notes?: string;
  demo?: boolean;
  hasAccount?: boolean;
  createdAt?: string;
  updatedAt?: string;
  version?: number;
}

export interface MeResponse {
  id?: number;
  email?: string;
  displayName?: string;
  memberId?: number;
  roles?: string[];
  permissions?: string[];
}

export interface MinutesRequest {
  content: string;
}

export interface MinutesResponse {
  id?: number;
  versionNo?: number;
  status?: "BROUILLON" | "PUBLIE" | "REMPLACE";
  content?: string;
  createdAt?: string;
  publishedAt?: string;
  documentId?: number;
}

export interface NotificationAttempt {
  id?: number;
  notificationId?: number;
  attemptedAt?: string;
  outcome?: string;
  detail?: string;
}

export interface ObligationResponse {
  id?: number;
  memberId?: number;
  planId?: number;
  kind?: "COTISATION" | "PENALITE";
  parentId?: number;
  penaltySequence?: number;
  periodLabel?: string;
  periodStart?: string;
  periodEnd?: string;
  dueDate?: string;
  amountDue?: number;
  amountPaid?: number;
  outstanding?: number;
  status?: "A_PAYER" | "PARTIELLE" | "PAYEE" | "ANNULEE";
  overdue?: boolean;
  daysLate?: number;
  cancelReason?: string;
}

export interface PageResponseAuditItem {
  content?: AuditItem[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseDocumentItem {
  content?: DocumentItem[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseEntryResponse {
  content?: EntryResponse[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseExpenseResponse {
  content?: ExpenseResponse[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseInAppItem {
  content?: InAppItem[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseIntentResponse {
  content?: IntentResponse[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseMemberListItem {
  content?: MemberListItem[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseObject {
  content?: unknown[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseQueueItem {
  content?: QueueItem[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseTransactionResponse {
  content?: TransactionResponse[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PageResponseUserResponse {
  content?: UserResponse[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
}

export interface PasswordResetConfirm {
  token: string;
  newPassword: string;
}

export interface PasswordResetRequest {
  email: string;
}

export interface PenaltySimulationRequest {
  dueDate: string;
  principal: number;
  graceDays?: number;
  method: "AUCUNE" | "FORFAIT" | "POURCENTAGE_MENSUEL";
  value: number;
  cap?: number;
  rounding: "UP" | "DOWN" | "CEILING" | "FLOOR" | "HALF_UP" | "HALF_DOWN" | "HALF_EVEN" | "UNNECESSARY";
  asOf: string;
}

export interface PenaltySimulationResponse {
  assessments?: Assessment[];
  total?: number;
}

export interface PlanRequest {
  code: string;
  name: string;
  description?: string;
  amount: number;
  periodicity: "MENSUELLE" | "TRIMESTRIELLE" | "SEMESTRIELLE" | "ANNUELLE";
  dueDayOfMonth?: number;
  validFrom: string;
  validTo?: string;
  graceDays?: number;
  penaltyMethod: "AUCUNE" | "FORFAIT" | "POURCENTAGE_MENSUEL";
  penaltyValue: number;
  penaltyCap?: number;
  roundingMode: "UP" | "DOWN" | "CEILING" | "FLOOR" | "HALF_UP" | "HALF_DOWN" | "HALF_EVEN" | "UNNECESSARY";
  adoptedReference?: string;
}

export interface PlanResponse {
  id?: number;
  code?: string;
  name?: string;
  description?: string;
  amount?: number;
  currency?: string;
  periodicity?: "MENSUELLE" | "TRIMESTRIELLE" | "SEMESTRIELLE" | "ANNUELLE";
  dueDayOfMonth?: number;
  validFrom?: string;
  validTo?: string;
  graceDays?: number;
  penaltyMethod?: "AUCUNE" | "FORFAIT" | "POURCENTAGE_MENSUEL";
  penaltyValue?: number;
  penaltyCap?: number;
  roundingMode?: "UP" | "DOWN" | "CEILING" | "FLOOR" | "HALF_UP" | "HALF_DOWN" | "HALF_EVEN" | "UNNECESSARY";
  status?: "BROUILLON" | "ACTIF" | "ARCHIVE";
  adoptedReference?: string;
  createdAt?: string;
  memberCount?: number;
}

export interface ProjectRequest {
  code: string;
  name: string;
  description?: string;
  region?: string;
  departement?: string;
  commune?: string;
  status: "ETUDE" | "EN_COURS" | "LIVRE" | "SUSPENDU";
}

export interface ProjectResponse {
  id?: number;
  code?: string;
  name?: string;
  description?: string;
  region?: string;
  departement?: string;
  commune?: string;
  status?: "ETUDE" | "EN_COURS" | "LIVRE" | "SUSPENDU";
  lotCount?: number;
  demo?: boolean;
}

export interface ProviderInfo {
  code?: "MOCK" | "WAVE" | "SENDFLASH" | "MANUEL";
  available?: boolean;
  simulated?: boolean;
  message?: string;
}

export interface PublicInfo {
  name?: string;
  legalForm?: string;
  registrationNumber?: string;
  address?: string;
  city?: string;
  phone?: string;
  email?: string;
  escrowStatement?: string;
  publicStatsAvailable?: boolean;
  environment?: string;
  paymentSimulationEnabled?: boolean;
  demoData?: boolean;
}

export interface PublicProject {
  code?: string;
  name?: string;
  region?: string;
  departement?: string;
  commune?: string;
  status?: string;
  lots?: number;
}

export interface PublicStats {
  activeMembers?: number;
  lotsByStatus?: Record<string, number>;
  projects?: number;
  note?: string;
}

export interface PublishedResult {
  memberNumber?: string;
  lotReference?: string;
  drawRank?: number;
  status?: "PROVISOIRE" | "DEFINITIF" | "LISTE_ATTENTE" | "ANNULE";
}

export interface PublishRequest {
  visibility?: "PUBLIC" | "MEMBRES" | "BUREAU" | "PRIVE_MEMBRE";
}

export interface PvResponse {
  id?: number;
  versionNo?: number;
  documentId?: number;
  status?: "BROUILLON" | "VALIDE" | "REMPLACE";
  generatedAt?: string;
  validatedAt?: string;
}

export interface QueueItem {
  id?: number;
  memberId?: number;
  channel?: "EMAIL" | "SMS" | "IN_APP";
  templateCode?: string;
  recipient?: string;
  subject?: string;
  status?: "EN_ATTENTE" | "ENVOYE" | "ECHOUE" | "LIVRE";
  statusDetail?: string;
  attempts?: number;
  createdAt?: string;
  sentAt?: string;
  nextAttemptAt?: string;
}

export interface QuorumInfo {
  activeMembers?: number;
  present?: number;
  represented?: number;
  absent?: number;
  excused?: number;
  participationPercent?: number;
}

export interface ReasonRequest {
  reason: string;
}

export interface ReceiptItem {
  id?: number;
  receiptNumber?: string;
  transactionId?: number;
  issuedAt?: string;
  simulated?: boolean;
}

export interface ReconciliationAccount {
  accountCode?: string;
  label?: string;
  accountingBalance?: number;
  declaredBalance?: number;
  declaredAt?: string;
  declaredSource?: "DECLARATION" | "RELEVE_IMPORTE";
  gap?: number;
  unmatchedStatementLines?: number;
  status?: string;
}

export interface RefundRequest {
  reason: string;
}

export interface ReminderRun {
  runDate?: string;
  dueDate?: string;
  obligations?: number;
  notificationsCreated?: number;
}

export interface ReportRequest {
  type: "SEMESTRIEL" | "ANNUEL" | "PERIODE";
  periodStart: string;
  periodEnd: string;
}

export interface ReportResponse {
  id?: number;
  reference?: string;
  type?: "SEMESTRIEL" | "ANNUEL" | "PERIODE";
  periodStart?: string;
  periodEnd?: string;
  status?: "BROUILLON" | "PUBLIE";
  documentId?: number;
  generatedAt?: string;
  publishedAt?: string;
  snapshot?: unknown;
}

export interface ResolutionRequest {
  agendaItemId?: number;
  number: string;
  text: string;
  votesFor?: number;
  votesAgainst?: number;
  abstentions?: number;
  outcome: "ADOPTEE" | "REJETEE" | "AJOURNEE";
}

export interface ResolutionResponse {
  id?: number;
  agendaItemId?: number;
  number?: string;
  text?: string;
  votesFor?: number;
  votesAgainst?: number;
  abstentions?: number;
  outcome?: "ADOPTEE" | "REJETEE" | "AJOURNEE";
  recordedAt?: string;
}

export interface ResultResponse {
  id?: number;
  candidateId?: number;
  memberNumber?: string;
  memberName?: string;
  lotId?: number;
  lotReference?: string;
  drawRank?: number;
  justification?: string;
  status?: "PROVISOIRE" | "DEFINITIF" | "LISTE_ATTENTE" | "ANNULE";
  cancelReason?: string;
  createdAt?: string;
}

export interface RoleResponse {
  code?: string;
  label?: string;
  description?: string;
  technical?: boolean;
  permissions?: string[];
}

export interface RolesRequest {
  roles: string[];
}

export interface Section {
  key?: string;
  title?: string;
  indicators?: Indicator[];
}

export interface SessionInfo {
  id?: number;
  createdAt?: string;
  expiresAt?: string;
  userAgent?: string;
  ipAddress?: string;
}

export interface SettingsRequest {
  name: string;
  legalForm?: string;
  registrationNumber?: string;
  address?: string;
  city?: string;
  phone?: string;
  email?: string;
  publishPublicStats?: boolean;
}

export interface SimulationRequest {
  success?: boolean;
}

export interface StatementLineResponse {
  id?: number;
  accountCode?: string;
  operationDate?: string;
  label?: string;
  amount?: number;
  bankReference?: string;
  matchedEntryId?: number;
  matchedEntryReference?: string;
  matchedAt?: string;
}

export interface StatusChangeRequest {
  status: "EN_ATTENTE" | "ACTIF" | "SUSPENDU" | "RADIE";
  reason: string;
}

export interface StatusHistoryItem {
  id?: number;
  fromStatus?: "EN_ATTENTE" | "ACTIF" | "SUSPENDU" | "RADIE";
  toStatus?: "EN_ATTENTE" | "ACTIF" | "SUSPENDU" | "RADIE";
  reason?: string;
  changedBy?: number;
  changedAt?: string;
}

export interface TokenResponse {
  accessToken?: string;
  tokenType?: string;
  expiresIn?: number;
  user?: MeResponse;
}

export interface TransactionResponse {
  id?: number;
  reference?: string;
  intentId?: number;
  memberId?: number;
  amount?: number;
  currency?: string;
  provider?: "MOCK" | "WAVE" | "SENDFLASH" | "MANUEL";
  providerTransactionId?: string;
  simulated?: boolean;
  status?: "CONFIRME" | "REMBOURSE";
  confirmedAt?: string;
  confirmationSource?: string;
  refundedAt?: string;
  refundReason?: string;
  receiptNumber?: string;
  receiptId?: number;
}

export interface TransparencySummary {
  asOf?: string;
  treasury?: ReconciliationAccount[];
  accountingTreasuryTotal?: number;
  declaredTreasuryTotal?: number;
  confirmedPaymentsTotal?: number;
  confirmedPaymentsCount?: number;
  pendingPaymentsTotal?: number;
  pendingPaymentsCount?: number;
  simulatedPaymentsTotal?: number;
  simulatedPaymentsCount?: number;
  simulationAccountBalance?: number;
  memberFundsHeld?: number;
  penaltiesIncome?: number;
  expensesPaidTotal?: number;
  expensesCommittedTotal?: number;
  expensesAwaitingApproval?: number;
  escrowStatement?: string;
  escrowIntegrated?: boolean;
  lastEntryAt?: string;
  definitions?: Record<string, string>;
}

export interface UpdateMemberRequest {
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  address?: string;
  city?: string;
  memberType: "FONDATEUR" | "ORDINAIRE" | "BENEFICIAIRE";
  notes?: string;
  version?: number;
}

export interface UserCreateRequest {
  email: string;
  displayName: string;
  roles: string[];
  memberId?: number;
}

export interface UserResponse {
  id?: number;
  email?: string;
  displayName?: string;
  enabled?: boolean;
  activated?: boolean;
  roles?: string[];
  memberId?: number;
  lastLoginAt?: string;
  lockedUntil?: string;
  demo?: boolean;
  createdAt?: string;
}

export interface Verification {
  valid?: boolean;
  receiptNumber?: string;
  issuedAt?: string;
  amount?: number;
  transactionStatus?: string;
  simulated?: boolean;
  message?: string;
}

export interface VerifyManualRequest {
  approve?: boolean;
  comment?: string;
}

export interface WebhookResult {
  result?: string;
}

