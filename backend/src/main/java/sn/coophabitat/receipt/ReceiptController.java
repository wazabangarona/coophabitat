package sn.coophabitat.receipt;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.document.DocumentService;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;
import sn.coophabitat.security.Permission;

import java.time.Instant;
import java.util.List;

@RestController
@Tag(name = "Reçus")
public class ReceiptController {

    private final ReceiptService service;
    private final ReceiptRepository receipts;
    private final DocumentService documents;

    public ReceiptController(ReceiptService service, ReceiptRepository receipts, DocumentService documents) {
        this.service = service;
        this.receipts = receipts;
        this.documents = documents;
    }

    public record ReceiptItem(Long id, String receiptNumber, Long transactionId, Instant issuedAt, boolean simulated) {
        static ReceiptItem of(Receipt r) {
            return new ReceiptItem(r.getId(), r.getReceiptNumber(), r.getTransactionId(), r.getIssuedAt(), r.isSimulated());
        }
    }

    @GetMapping("/api/v1/members/{memberId}/receipts")
    public List<ReceiptItem> list(@PathVariable Long memberId) {
        CurrentUserProvider.requireSelfOr(memberId, Permission.PAYMENT_READ_ALL);
        return receipts.findByMemberIdOrderByIssuedAtDesc(memberId).stream().map(ReceiptItem::of).toList();
    }

    @GetMapping("/api/v1/receipts/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Receipt r = service.get(id);
        CurrentUser user = CurrentUserProvider.require();
        if (!user.isMember(r.getMemberId()) && !user.has(Permission.PAYMENT_READ_ALL)) {
            throw BusinessException.notFound("Reçu");
        }
        var content = documents.download(r.getDocumentId(), user);
        return Downloads.file(content.bytes(), r.getReceiptNumber() + ".pdf", MediaType.APPLICATION_PDF, true);
    }

    @GetMapping("/api/v1/public/receipts/verify")
    public ReceiptService.Verification verify(@RequestParam String number, @RequestParam String code) {
        return service.verify(number, code);
    }
}
