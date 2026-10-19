package sn.coophabitat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /** Fuseau de référence de la coopérative : toutes les échéances sont calculées à Dakar (UTC+0, sans heure d'été). */
    public static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");

    @Bean
    Clock clock() {
        return Clock.system(DAKAR);
    }
}
