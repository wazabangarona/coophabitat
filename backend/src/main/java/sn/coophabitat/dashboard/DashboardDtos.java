package sn.coophabitat.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * Indicateur affiché : chaque valeur porte sa définition et sa nature, pour qu'aucun chiffre ne soit ambigu.
     * nature : REEL (fonds ou faits constatés), DECLARE (saisi par un responsable), EN_ATTENTE (non encore reçu),
     * SIMULATION (aucun argent réel), COMPTAGE (nombre d'éléments).
     */
    public record Indicator(String key, String label, Object value, String unit, String nature, String definition) {
    }

    public record Section(String key, String title, List<Indicator> indicators) {
    }

    public record Dashboard(String role, LocalDate asOf, Instant computedAt, List<Section> sections, List<String> notices) {
    }
}
