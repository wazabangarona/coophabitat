package sn.coophabitat.common;

import java.math.BigDecimal;

/**
 * Conversion d'un montant entier en toutes lettres (français standard, règles de 1990 non appliquées :
 * orthographe traditionnelle avec traits d'union entre dizaines et unités).
 * Limité à 999 999 999 999 ; au-delà la méthode lève une exception plutôt que d'écrire un texte faux.
 */
public final class AmountInWords {

    private static final String[] UNITS = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit",
            "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] TENS = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante"};

    private AmountInWords() {
    }

    public static String toWords(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.scale() > 0 && amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Montant entier positif attendu");
        }
        long n = amount.longValueExact();
        if (n > 999_999_999_999L) {
            throw new IllegalArgumentException("Montant trop élevé pour la conversion en lettres");
        }
        return toWords(n);
    }

    public static String toWords(long n) {
        if (n == 0) {
            return UNITS[0];
        }
        StringBuilder sb = new StringBuilder();
        long billions = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long thousands = (n / 1000L) % 1000;
        int rest = (int) (n % 1000);
        if (billions > 0) {
            sb.append(below1000((int) billions, false)).append(billions > 1 ? " milliards" : " milliard");
        }
        if (millions > 0) {
            appendSpace(sb).append(below1000((int) millions, false)).append(millions > 1 ? " millions" : " million");
        }
        if (thousands > 0) {
            appendSpace(sb);
            if (thousands == 1) {
                sb.append("mille");
            } else {
                sb.append(below1000((int) thousands, true)).append(" mille");
            }
        }
        if (rest > 0) {
            appendSpace(sb).append(below1000(rest, false));
        }
        return sb.toString();
    }

    private static StringBuilder appendSpace(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        return sb;
    }

    /** @param beforeMille « cent » et « quatre-vingt » ne prennent pas de « s » devant « mille ». */
    private static String below1000(int n, boolean beforeMille) {
        int hundreds = n / 100;
        int rest = n % 100;
        StringBuilder sb = new StringBuilder();
        if (hundreds > 0) {
            if (hundreds > 1) {
                sb.append(UNITS[hundreds]).append(' ');
            }
            sb.append("cent");
            if (hundreds > 1 && rest == 0 && !beforeMille) {
                sb.append('s');
            }
        }
        if (rest > 0) {
            appendSpace(sb).append(below100(rest, beforeMille));
        }
        return sb.toString();
    }

    private static String below100(int n, boolean beforeMille) {
        if (n < 20) {
            return UNITS[n];
        }
        int tens = n / 10;
        int unit = n % 10;
        if (tens == 7 || tens == 9) {
            String base = tens == 7 ? "soixante" : "quatre-vingt";
            int sub = 10 + unit;
            if (tens == 7 && unit == 1) {
                return base + " et onze";
            }
            return base + "-" + UNITS[sub];
        }
        if (tens == 8) {
            if (unit == 0) {
                return beforeMille ? "quatre-vingt" : "quatre-vingts";
            }
            return "quatre-vingt-" + UNITS[unit];
        }
        if (unit == 0) {
            return TENS[tens];
        }
        if (unit == 1) {
            return TENS[tens] + " et un";
        }
        return TENS[tens] + "-" + UNITS[unit];
    }
}
