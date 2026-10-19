package sn.coophabitat.allocation;

import sn.coophabitat.common.Hashing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Tirage au sort vérifiable, version {@value #VERSION}.
 * <ol>
 *   <li>Avant le tirage : la liste des participants admissibles (numéros d'adhérent triés) et la liste des lots
 *       (références triées) sont figées et résumées par des empreintes SHA-256 ; une graine secrète de 256 bits
 *       est tirée par un générateur cryptographiquement sûr et seul son engagement SHA-256 est publié.</li>
 *   <li>Au tirage : la graine finale = SHA-256(version | graine | empreinte participants | empreinte lots |
 *       entropie publique). L'entropie publique (par exemple des numéros tirés en séance) empêche quiconque de
 *       connaître le résultat à l'avance, y compris l'administrateur de la base.</li>
 *   <li>Permutation de Fisher-Yates, alimentée par HMAC-SHA256(graine finale, compteur), avec rejet pour
 *       éviter tout biais de modulo.</li>
 *   <li>Le rang i (à partir de 0) reçoit le lot i de la liste triée ; les rangs suivants forment la liste d'attente.</li>
 * </ol>
 * Après le tirage, la graine est révélée : tout auditeur peut recalculer l'engagement et la permutation.
 * Ce mécanisme prouve la reproductibilité du tirage ; il ne prouve pas à lui seul l'équité juridique des critères.
 */
public final class DrawEngine {

    public static final String VERSION = "CH-TIRAGE-v1";

    private DrawEngine() {
    }

    public static String participantsDigest(String campaignReference, List<String> sortedMemberNumbers) {
        return Hashing.sha256Hex(VERSION + "|participants|" + campaignReference + "|" + String.join(";", sortedMemberNumbers));
    }

    public static String lotsDigest(String campaignReference, List<String> sortedLotReferences) {
        return Hashing.sha256Hex(VERSION + "|lots|" + campaignReference + "|" + String.join(";", sortedLotReferences));
    }

    public static String newSeed() {
        return HexFormat.of().formatHex(Hashing.randomBytes(32));
    }

    public static String commitment(String seedHex) {
        return Hashing.sha256Hex(VERSION + "|engagement|" + seedHex);
    }

    public static byte[] finalSeed(String seedHex, String participantsDigest, String lotsDigest, String publicEntropy) {
        String material = String.join("|", VERSION, seedHex, participantsDigest, lotsDigest,
                publicEntropy == null ? "" : publicEntropy.trim());
        return HexFormat.of().parseHex(Hashing.sha256Hex(material));
    }

    /** Permutation déterministe de la liste (non modifiée) à partir de la graine finale. */
    public static List<String> shuffle(List<String> sortedParticipants, byte[] finalSeed) {
        List<String> list = new ArrayList<>(sortedParticipants);
        Stream stream = new Stream(finalSeed);
        for (int i = list.size() - 1; i > 0; i--) {
            int j = stream.nextInt(i + 1);
            String tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
        return list;
    }

    /** Flux pseudo-aléatoire déterministe : HMAC-SHA256(graine, compteur). */
    static final class Stream {
        private final Mac mac;
        private long counter;
        private ByteBuffer block = ByteBuffer.allocate(0);

        Stream(byte[] seed) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        long nextLong63() {
            if (block.remaining() < 8) {
                byte[] out = mac.doFinal(ByteBuffer.allocate(8).putLong(counter++).array());
                block = ByteBuffer.wrap(out);
            }
            return block.getLong() & Long.MAX_VALUE;
        }

        int nextInt(int bound) {
            long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound);
            long v;
            do {
                v = nextLong63();
            } while (v >= limit);
            return (int) (v % bound);
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
