package sn.coophabitat.member;

import java.util.EnumSet;
import java.util.Set;

public enum MemberStatus {
    EN_ATTENTE, ACTIF, SUSPENDU, RADIE, REFUSE;

    /**
     * Transitions autorisées. Une demande en attente est soit approuvée (ACTIF), soit refusée (REFUSE) ;
     * un refus peut être réexaminé (retour en attente). La radiation est définitive (une réadmission crée un
     * nouveau dossier).
     */
    public Set<MemberStatus> allowedTargets() {
        return switch (this) {
            case EN_ATTENTE -> EnumSet.of(ACTIF, REFUSE);
            case ACTIF -> EnumSet.of(SUSPENDU, RADIE);
            case SUSPENDU -> EnumSet.of(ACTIF, RADIE);
            case REFUSE -> EnumSet.of(EN_ATTENTE);
            case RADIE -> EnumSet.noneOf(MemberStatus.class);
        };
    }
}
