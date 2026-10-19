package sn.coophabitat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Garde-fous de démarrage : l'application refuse de démarrer en production si la simulation
 * de paiement ou les données de démonstration sont activées, ou si les secrets sont faibles.
 */
@Component
public class ProductionSafetyCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    private final AppProperties props;
    private final Environment env;

    public ProductionSafetyCheck(AppProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        String secret = props.security().jwtSecret();
        if (secret == null || secret.length() < 32) {
            problems.add("COOPHABITAT_JWT_SECRET doit contenir au moins 32 caractères");
        }
        boolean demoProfile = Arrays.asList(env.getActiveProfiles()).contains("demo");
        if (props.isProduction()) {
            if (props.payment().mockEnabled()) {
                problems.add("Le fournisseur de paiement simulé ne peut pas être activé en production");
            }
            if (props.demo().enabled() || demoProfile) {
                problems.add("Les données de démonstration ne peuvent pas être chargées en production");
            }
            if (!props.security().cookieSecure()) {
                problems.add("Les cookies doivent être marqués Secure en production (HTTPS obligatoire)");
            }
            if (props.api().swaggerEnabled()) {
                log.warn("Swagger UI est exposé en production : à restreindre au réseau d'administration");
            }
            if (secret != null && secret.toLowerCase().contains("dev")) {
                problems.add("Le secret JWT ressemble à un secret de développement");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuration refusée : " + String.join(" ; ", problems));
        }
        if (props.payment().mockEnabled()) {
            log.warn("SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ : le fournisseur de paiement simulé est actif (environnement {})",
                    props.environment());
        }
    }
}
