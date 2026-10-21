package sn.coophabitat.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreateIntentRequest(@NotNull @Positive BigDecimal amount, Long obligationId,
                                      @NotNull ProviderCode provider) {
    }

    public record ManualPaymentRequest(@NotNull Long memberId, @NotNull @Positive BigDecimal amount, Long obligationId,
                                       @NotNull @Pattern(regexp = "ESPECES|VIREMENT|CHEQUE|WAVE|ORANGE_MONEY") String method,
                                       @Size(max = 200) String externalReference,
                                       @NotNull LocalDate receivedOn,
                                       @Size(max = 1000) String comment) {
    }

    /** Paiement fait par le membre lui-même, à vérifier par le bureau ; la référence de transaction est obligatoire. */
    public record PaymentDeclarationRequest(@NotNull @Positive BigDecimal amount, Long obligationId,
                                            @NotNull @Pattern(regexp = "WAVE|ORANGE_MONEY|VIREMENT|CHEQUE") String method,
                                            @NotBlank @Size(min = 4, max = 200) String externalReference,
                                            @NotNull LocalDate paidOn,
                                            @Size(max = 1000) String comment) {
    }

    /** Où et comment payer la coopérative (texte saisi par le bureau) et moyens de déclaration acceptés. */
    public record PaymentInstructions(String instructions, List<String> declarationMethods, boolean onlinePaymentAvailable) {
    }

    public record PaymentInstructionsRequest(@Size(max = 2000) String instructions) {
    }

    public record VerifyManualRequest(boolean approve, @Size(max = 1000) String comment) {
    }

    public record RefundRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    public record SimulationRequest(boolean success) {
    }

    public record IntentResponse(Long id, String reference, Long memberId, Long obligationId, BigDecimal amount,
                                 String currency, ProviderCode provider, String channel, PaymentStatus status,
                                 boolean simulated, String launchUrl, String externalReference, String failureReason,
                                 Long createdBy, Long verifiedBy, Instant createdAt, Instant expiresAt,
                                 Instant confirmedAt, Long transactionId, String receiptNumber) {
    }

    public record TransactionResponse(Long id, String reference, Long intentId, Long memberId, BigDecimal amount,
                                      String currency, ProviderCode provider, String providerTransactionId,
                                      boolean simulated, PaymentTransaction.Status status, Instant confirmedAt,
                                      String confirmationSource, Instant refundedAt, String refundReason,
                                      String receiptNumber, Long receiptId) {
    }

    public record ProviderInfo(ProviderCode code, boolean available, boolean simulated, String message) {
    }

    public record AllocationResponse(Long obligationId, String periodLabel, BigDecimal amount, Instant createdAt,
                                     boolean reversal) {
    }

    public record WebhookResult(String result) {
    }
}
