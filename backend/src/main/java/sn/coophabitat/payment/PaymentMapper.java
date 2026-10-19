package sn.coophabitat.payment;

import org.springframework.stereotype.Component;
import sn.coophabitat.payment.PaymentDtos.IntentResponse;
import sn.coophabitat.payment.PaymentDtos.TransactionResponse;
import sn.coophabitat.receipt.Receipt;
import sn.coophabitat.receipt.ReceiptRepository;

@Component
public class PaymentMapper {

    private final PaymentTransactionRepository transactions;
    private final ReceiptRepository receipts;

    public PaymentMapper(PaymentTransactionRepository transactions, ReceiptRepository receipts) {
        this.transactions = transactions;
        this.receipts = receipts;
    }

    public IntentResponse intent(PaymentIntent i) {
        var tx = transactions.findByIntentId(i.getId());
        String receipt = tx.flatMap(t -> receipts.findByTransactionId(t.getId())).map(Receipt::getReceiptNumber).orElse(null);
        // L'URL du fournisseur n'est utile que tant que le paiement est en attente.
        String url = i.getStatus() == PaymentStatus.EN_ATTENTE ? i.getProviderLaunchUrl() : null;
        return new IntentResponse(i.getId(), i.getReference(), i.getMemberId(), i.getObligationId(), i.getAmount(),
                i.getCurrency(), i.getProvider(), i.getChannel(), i.getStatus(), i.isSimulated(), url,
                i.getExternalReference(), i.getFailureReason(), i.getCreatedBy(), i.getVerifiedBy(), i.getCreatedAt(),
                i.getExpiresAt(), i.getConfirmedAt(), tx.map(PaymentTransaction::getId).orElse(null), receipt);
    }

    public TransactionResponse transaction(PaymentTransaction t) {
        var r = receipts.findByTransactionId(t.getId());
        return new TransactionResponse(t.getId(), t.getReference(), t.getIntentId(), t.getMemberId(), t.getAmount(),
                t.getCurrency(), t.getProvider(), t.getProviderTransactionId(), t.isSimulated(), t.getStatus(),
                t.getConfirmedAt(), t.getConfirmationSource(), t.getRefundedAt(), t.getRefundReason(),
                r.map(Receipt::getReceiptNumber).orElse(null), r.map(Receipt::getId).orElse(null));
    }
}
