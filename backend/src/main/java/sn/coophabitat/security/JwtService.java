package sn.coophabitat.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import sn.coophabitat.config.AppProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/** Émission et vérification des jetons d'accès (JWT HS256, durée courte, version de session embarquée). */
@Service
public class JwtService {

    public static final String ISSUER = "coophabitat";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AppProperties props;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        SecretKey key = secretKey(props);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
    }

    static SecretKey secretKey(AppProperties props) {
        return new SecretKeySpec(props.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String issueAccessToken(UserAccount user) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(props.security().accessTokenTtl()))
                .claim("tv", user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public long accessTokenTtlSeconds() {
        return props.security().accessTokenTtl().toSeconds();
    }
}
