package sn.coophabitat.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * États d'une intention de paiement. Seul CONFIRME signifie qu'un paiement a été reçu,
 * et uniquement après une confirmation authentifiée (webhook signé ou vérification auprès du fournisseur,
 * ou validation d'un encaissement manuel par un second responsable).
 */
public enum PaymentStatus {
    INITIE, EN_ATTENTE, CONFIRME, ECHOUE, EXPIRE, REMBOURSE, EN_VERIFICATION;

    public static final Set<PaymentStatus> OPEN = EnumSet.of(INITIE, EN_ATTENTE, EN_VERIFICATION);

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
