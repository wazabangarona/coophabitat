package sn.coophabitat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI coopHabitatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CoopHabitat API")
                        .version("v1")
                        .description("API REST de gestion d'une coopérative d'habitat. Montants en francs CFA (XOF), "
                                + "entiers. Les opérations financières sont idempotentes (en-tête Idempotency-Key).")
                        .license(new License().name("Usage universitaire — licence à définir")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
