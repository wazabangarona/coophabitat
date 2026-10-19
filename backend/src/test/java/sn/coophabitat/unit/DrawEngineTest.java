package sn.coophabitat.unit;

import org.junit.jupiter.api.Test;
import sn.coophabitat.allocation.DrawEngine;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DrawEngineTest {

    private static final List<String> PARTICIPANTS = IntStream.rangeClosed(1, 12)
            .mapToObj(i -> String.format("CH-2025-%04d", i)).toList();

    @Test
    void shuffleIsDeterministicForTheSameInputs() {
        String seed = DrawEngine.newSeed();
        String pd = DrawEngine.participantsDigest("ATT-1", PARTICIPANTS);
        String ld = DrawEngine.lotsDigest("ATT-1", List.of("L1", "L2"));
        byte[] fs = DrawEngine.finalSeed(seed, pd, ld, "entropie publique");
        assertThat(DrawEngine.shuffle(PARTICIPANTS, fs)).isEqualTo(DrawEngine.shuffle(PARTICIPANTS, fs));
    }

    @Test
    void shuffleIsAPermutationOfTheParticipants() {
        byte[] fs = DrawEngine.finalSeed(DrawEngine.newSeed(), "a", "b", "");
        List<String> order = DrawEngine.shuffle(PARTICIPANTS, fs);
        assertThat(order).hasSize(PARTICIPANTS.size()).containsExactlyInAnyOrderElementsOf(PARTICIPANTS);
    }

    @Test
    void publicEntropyChangesTheResult() {
        String seed = DrawEngine.newSeed();
        List<String> a = DrawEngine.shuffle(PARTICIPANTS, DrawEngine.finalSeed(seed, "p", "l", "A"));
        List<String> b = DrawEngine.shuffle(PARTICIPANTS, DrawEngine.finalSeed(seed, "p", "l", "B"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void commitmentBindsTheSeed() {
        String seed = DrawEngine.newSeed();
        assertThat(DrawEngine.commitment(seed)).isEqualTo(DrawEngine.commitment(seed))
                .isNotEqualTo(DrawEngine.commitment(DrawEngine.newSeed()))
                .hasSize(64);
        assertThat(seed).hasSize(64);
    }

    @Test
    void digestsDependOnContent() {
        assertThat(DrawEngine.participantsDigest("ATT-1", List.of("A", "B")))
                .isNotEqualTo(DrawEngine.participantsDigest("ATT-1", List.of("A", "C")))
                .isNotEqualTo(DrawEngine.participantsDigest("ATT-2", List.of("A", "B")));
    }

    @Test
    void firstPositionIsRoughlyUniform() {
        List<String> three = List.of("A", "B", "C");
        Map<String, Integer> firsts = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            firsts.merge(DrawEngine.shuffle(three, DrawEngine.finalSeed(DrawEngine.newSeed(), "p", "l", "")).get(0), 1, Integer::sum);
        }
        // Espérance 1000 par participant ; tolérance large pour éviter un test instable.
        firsts.values().forEach(n -> assertThat(n).isBetween(850, 1150));
    }

    /** Vecteur de référence partagé avec tools/test_verifier_tirage.py : les deux implémentations doivent concorder. */
    @Test
    void goldenVectorMatchesTheIndependentPythonVerifier() {
        String seed = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        List<String> parts = List.of("ADH-0001", "ADH-0002", "ADH-0003", "ADH-0004", "ADH-0005", "ADH-0006", "ADH-0007");
        List<String> lots = List.of("LOT-A1", "LOT-A2", "LOT-B1");
        String pd = DrawEngine.participantsDigest("ATT-TEST-000001", parts);
        String ld = DrawEngine.lotsDigest("ATT-TEST-000001", lots);
        assertThat(DrawEngine.commitment(seed)).isEqualTo("3df7dc6fac795849e3484fc8d9995fe158bf1aa1b11a9fa6b676d11328e51b54");
        assertThat(pd).isEqualTo("4841d3f7af88bfb7f336b7fef7c88218fd0b1f6f0d07ec78bb690e3b20f2700f");
        assertThat(ld).isEqualTo("11bf5ad6e9ac5ee3a3a9d3fe69c564eb9232dd1c7c7fc931abc1be2e1d62c94c");
        assertThat(DrawEngine.shuffle(parts, DrawEngine.finalSeed(seed, pd, ld, "17 42 08")))
                .containsExactly("ADH-0005", "ADH-0007", "ADH-0001", "ADH-0002", "ADH-0003", "ADH-0006", "ADH-0004");
    }
}
