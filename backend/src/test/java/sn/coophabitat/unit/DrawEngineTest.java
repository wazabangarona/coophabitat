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
}
