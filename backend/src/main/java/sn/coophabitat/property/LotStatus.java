package sn.coophabitat.property;

import java.util.EnumSet;
import java.util.Set;

public enum LotStatus {
    EN_PREPARATION, DISPONIBLE, RESERVE, ATTRIBUE, LITIGIEUX, INDISPONIBLE;

    /** États qu'un gestionnaire peut atteindre manuellement : RESERVE et ATTRIBUE sont réservés à la procédure d'attribution. */
    public static final Set<LotStatus> PROCEDURE_ONLY = EnumSet.of(RESERVE, ATTRIBUE);

    public Set<LotStatus> manualTargets() {
        return switch (this) {
            case EN_PREPARATION -> EnumSet.of(DISPONIBLE, INDISPONIBLE);
            case DISPONIBLE -> EnumSet.of(EN_PREPARATION, INDISPONIBLE, LITIGIEUX);
            case INDISPONIBLE -> EnumSet.of(EN_PREPARATION, DISPONIBLE);
            case LITIGIEUX -> EnumSet.of(DISPONIBLE, INDISPONIBLE);
            case RESERVE, ATTRIBUE -> EnumSet.noneOf(LotStatus.class);
        };
    }
}
