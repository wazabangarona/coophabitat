package sn.coophabitat.security;

import org.springframework.stereotype.Component;
import sn.coophabitat.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * Politique de mot de passe inspirée des recommandations NIST SP 800-63B :
 * longueur minimale élevée, refus des mots de passe triviaux, pas de règles de composition arbitraires.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> FORBIDDEN = Set.of("motdepasse123", "password1234", "123456789012", "azertyuiop12",
            "coophabitat123", "coophabitat2026", "senegal123456", "dakar1234567", "qwertyuiop12", "000000000000");

    public void validate(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw BusinessException.badRequest("MOT_DE_PASSE_FAIBLE",
                    "Le mot de passe doit contenir au moins " + MIN_LENGTH + " caractères");
        }
        if (password.length() > MAX_LENGTH) {
            throw BusinessException.badRequest("MOT_DE_PASSE_TROP_LONG", "Le mot de passe est trop long");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(lower) || password.chars().distinct().count() < 5) {
            throw BusinessException.badRequest("MOT_DE_PASSE_FAIBLE", "Ce mot de passe est trop prévisible");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) {
                throw BusinessException.badRequest("MOT_DE_PASSE_FAIBLE",
                        "Le mot de passe ne doit pas contenir votre identifiant");
            }
        }
    }
}
