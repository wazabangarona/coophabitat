package sn.coophabitat.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.security.AuthService;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Premier démarrage : si aucun administrateur technique n'existe et qu'une adresse est fournie
 * (COOPHABITAT_BOOTSTRAP_ADMIN_EMAIL), un compte est créé SANS mot de passe et une invitation est envoyée.
 * Aucun mot de passe par défaut n'existe.
 */
@Component
@Order(10)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserAccountRepository users;
    private final AuthService auth;
    private final AuditService audit;
    private final AppProperties props;

    public BootstrapAdmin(UserAccountRepository users, AuthService auth, AuditService audit, AppProperties props) {
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = props.security().bootstrapAdminEmail();
        if (email == null || email.isBlank()) {
            return;
        }
        if (!users.findActiveByRoles(List.of("ADMINISTRATEUR_TECHNIQUE")).isEmpty()) {
            return;
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            log.warn("Compte {} déjà présent sans rôle d'administrateur technique : aucun changement automatique", email);
            return;
        }
        UserAccount u = new UserAccount();
        u.setEmail(email.trim().toLowerCase(Locale.ROOT));
        u.setDisplayName("Administrateur technique");
        u.setRoles(new java.util.HashSet<>(Set.of("ADMINISTRATEUR_TECHNIQUE")));
        u = users.save(u);
        auth.invite(u);
        audit.recordAs(AuditCategory.SECURITE, "ADMINISTRATEUR_INITIAL_INVITE", "UserAccount", u.getId(),
                Map.of("email", u.getEmail()), null, "systeme");
        log.info("Administrateur technique initial invité : {}", u.getEmail());
    }
}
