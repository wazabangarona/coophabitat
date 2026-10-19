package sn.coophabitat.finance;

/** Catégories de dépenses et compte de charge (ou d'immobilisation) débité au paiement. */
public enum ExpenseCategory {
    FOURNITURES("604100", "Achats de fournitures"),
    DEPLACEMENT("618100", "Frais de déplacement"),
    HONORAIRES_ETUDES("622600", "Honoraires et études"),
    FRAIS_ADMINISTRATIFS("628100", "Frais administratifs"),
    FRAIS_BANCAIRES("631100", "Frais bancaires"),
    ACQUISITION_TERRAIN("222100", "Acquisition de terrains"),
    CONSTRUCTION("231100", "Travaux de construction"),
    DIVERS("658100", "Charges diverses");

    private final String accountCode;
    private final String label;

    ExpenseCategory(String accountCode, String label) {
        this.accountCode = accountCode;
        this.label = label;
    }

    public String accountCode() {
        return accountCode;
    }

    public String label() {
        return label;
    }
}
