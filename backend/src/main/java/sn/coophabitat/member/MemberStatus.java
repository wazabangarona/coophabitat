package sn.coophabitat.member;

import java.util.EnumSet;
import java.util.Set;

public enum MemberStatus {
    EN_ATTENTE, ACTIF, SUSPENDU, RADIE;

    /** Transitions autorisées ; la radiation est définitive (une réadmission crée un nouveau dossier). */
    public Set<MemberStatus> allowedTargets() {
        return switch (this) {
            case EN_ATTENTE -> EnumSet.of(ACTIF, RADIE);
            case ACTIF -> EnumSet.of(SUSPENDU, RADIE);
            case SUSPENDU -> EnumSet.of(ACTIF, RADIE);
            case RADIE -> EnumSet.noneOf(MemberStatus.class);
        };
    }
}
