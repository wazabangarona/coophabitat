package sn.coophabitat.contribution;

public enum Periodicity {
    MENSUELLE(1), TRIMESTRIELLE(3), SEMESTRIELLE(6), ANNUELLE(12);

    private final int months;

    Periodicity(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }
}
