package sn.coophabitat.allocation;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AllocationDtos {

    private AllocationDtos() {
    }

    public record CampaignRequest(@NotBlank @Size(max = 300) String title, @Size(max = 4000) String description,
                                  @NotNull AllocationMethod method, @Size(max = 4000) String eligibilityText,
                                  @Size(max = 4000) String rulesText, @Min(0) @Max(600) int minSeniorityMonths,
                                  boolean requireUpToDate, @NotNull @PositiveOrZero BigDecimal maxArrearsAmount,
                                  @NotEmpty List<String> allowedMemberTypes, LocalDate referenceDate,
                                  Instant applicationClosesAt, @Min(1) @Max(5) int requiredApprovals,
                                  Instant sessionDate, @Size(max = 300) String sessionLocation) {
    }

    public record LotsRequest(@NotNull List<Long> lotIds) {
    }

    public record CommissionRequest(@NotNull List<CommissionEntry> members) {
    }

    public record CommissionEntry(@NotNull Long userId, @Size(max = 100) String role, boolean conflictDeclared,
                                  @Size(max = 1000) String conflictDetails) {
    }

    public record ApprovalRequest(@NotNull AllocationApproval.Step step, @NotNull AllocationApproval.Decision decision,
                                  @Size(max = 2000) String comment, Long targetId) {
    }

    public record ExecuteRequest(@Size(max = 200) String publicEntropy) {
    }

    public record CommissionDecisionRequest(@NotNull Long candidateId, Long lotId,
                                            @NotBlank @Size(min = 10, max = 2000) String justification) {
    }

    public record ContestRequest(Long resultId, @NotBlank @Size(min = 20, max = 4000) String reason) {
    }

    public record ContestDecisionRequest(boolean accept, @NotBlank @Size(min = 10, max = 4000) String decision) {
    }

    public record CancelRequest(@NotBlank @Size(min = 10, max = 2000) String reason) {
    }

    public record CampaignResponse(Long id, String reference, String title, String description, AllocationMethod method,
                                   CampaignStatus status, String eligibilityText, String rulesText,
                                   int minSeniorityMonths, boolean requireUpToDate, BigDecimal maxArrearsAmount,
                                   List<String> allowedMemberTypes, LocalDate referenceDate,
                                   Instant applicationOpensAt, Instant applicationClosesAt, int requiredApprovals,
                                   Instant sessionDate, String sessionLocation, Instant criteriaPublishedAt,
                                   Instant listPublishedAt, Instant frozenAt, String participantsDigest,
                                   String lotsDigest, String seedCommitment, String revealedSeed,
                                   String publicEntropy, String algorithmVersion, Instant executedAt,
                                   Instant resultsPublishedAt, String cancelReason, List<Long> lotIds,
                                   long candidateCount, long eligibleCount, boolean demo, long version) {
    }

    public record CandidateResponse(Long id, Long memberId, String memberNumber, String memberName, Instant appliedAt,
                                    AllocationCandidate.Eligibility eligibilityStatus, String eligibilityReason,
                                    Instant checkedAt, Instant withdrawnAt) {
    }

    public record ResultResponse(Long id, Long candidateId, String memberNumber, String memberName, Long lotId,
                                 String lotReference, Integer drawRank, String justification,
                                 AllocationResult.Status status, String cancelReason, Instant createdAt) {
    }

    public record ApprovalResponse(Long id, AllocationApproval.Step step, Long approverId, String approverName,
                                   AllocationApproval.Decision decision, String comment, Long targetId,
                                   Instant decidedAt) {
    }

    public record MinutesResponse(Long id, int versionNo, Long documentId, AllocationMinutes.Status status,
                                  Instant generatedAt, Instant validatedAt) {
    }

    public record ContestResponse(Long id, String reference, Long campaignId, Long memberId, String memberNumber,
                                  Long resultId, Long lotId, String reason, AllocationContest.Status status,
                                  String decision, Instant decidedAt, Instant createdAt) {
    }

    public record DrawVerification(boolean valid, String algorithmVersion, boolean commitmentMatches,
                                   boolean participantsDigestMatches, boolean lotsDigestMatches,
                                   boolean resultsMatch, List<String> recomputedOrder, List<String> recordedOrder,
                                   String message) {
    }

    /** Vue membre : sa candidature et les résultats publiés (numéros d'adhérent et lots, sans noms). */
    public record MemberCampaignView(Long id, String reference, String title, AllocationMethod method,
                                     CampaignStatus status, String eligibilityText, String rulesText,
                                     LocalDate referenceDate, Instant applicationClosesAt, String seedCommitment,
                                     String participantsDigest, boolean applied, CandidateResponse myCandidacy,
                                     ResultResponse myResult, List<PublishedResult> publishedResults) {
    }

    public record PublishedResult(String memberNumber, String lotReference, Integer drawRank, AllocationResult.Status status) {
    }
}
