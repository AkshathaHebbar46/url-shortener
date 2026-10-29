package com.akshatha.urlshortener.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62CodecTest {

    /** 62^6 — the sequence seed, so the first real code is 7 characters. */
    private static final long SEED = 56_800_235_584L;

    @ParameterizedTest
    @ValueSource(longs = {
            0, 1, 61,          // last single-digit value
            62, 63,            // first two-digit value and the one after it
            3843,              // 62^2 - 1
            3844,              // 62^2
            SEED - 1,          // last 6-character code
            SEED,              // first 7-character code
            3_521_614_606_207L, // 62^7 - 1, last 7-character code
            Long.MAX_VALUE
    })
    void roundTripsBoundaryValues(long value) {
        assertThat(Base62Codec.decode(Base62Codec.encode(value))).isEqualTo(value);
    }

    @Test
    void roundTripsRandomValues() {
        Random random = new Random(20261028L); // fixed seed: a failure is reproducible
        for (int i = 0; i < 10_000; i++) {
            long value = Math.floorMod(random.nextLong(), Long.MAX_VALUE);
            assertThat(Base62Codec.decode(Base62Codec.encode(value)))
                    .as("round trip of %d", value)
                    .isEqualTo(value);
        }
    }

    @Test
    void encodesZeroToASingleCharacter() {
        // "" would round-trip to a path that cannot exist.
        assertThat(Base62Codec.encode(0)).isEqualTo("0");
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> Base62Codec.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * This is the test that justifies seeding the sequence at 62^6 rather
     * than 0: every id in the real range encodes to exactly code-length
     * characters, so no code is ever short enough to collide with a reserved
     * path or look broken.
     */
    @Test
    void idsInTheProductionRangeAreExactlySevenCharacters() {
        long lastSevenCharId = 3_521_614_606_207L; // 62^7 - 1

        assertThat(Base62Codec.encode(SEED)).hasSize(7);
        assertThat(Base62Codec.encode(lastSevenCharId)).hasSize(7);
        assertThat(Base62Codec.encode(SEED - 1)).hasSize(6);
        assertThat(Base62Codec.encode(lastSevenCharId + 1)).hasSize(8);

        Random random = new Random(7L);
        for (int i = 0; i < 1_000; i++) {
            long id = SEED + Math.floorMod(random.nextLong(), lastSevenCharId - SEED);
            assertThat(Base62Codec.encode(id)).hasSize(7);
        }
    }

    @Test
    void alphabetHasSixtyTwoDistinctCharacters() {
        String alphabet = Base62Codec.alphabet();
        Set<Character> distinct = new HashSet<>();
        for (char c : alphabet.toCharArray()) {
            distinct.add(c);
        }
        // A duplicate in the alphabet is silent and corrupts decode for
        // exactly one digit, so assert it rather than eyeballing the constant.
        assertThat(alphabet).hasSize(62);
        assertThat(distinct).hasSize(62);
        assertThat(alphabet).matches("[0-9A-Za-z]+");
    }

    @Test
    void encodeOnlyEmitsAlphabetCharacters() {
        Random random = new Random(99L);
        for (int i = 0; i < 2_000; i++) {
            long value = Math.floorMod(random.nextLong(), Long.MAX_VALUE);
            assertThat(Base62Codec.encode(value)).matches("[0-9A-Za-z]+");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "ab-cd", "ab cd", "é", "aaa/bbb", "+"})
    void decodeRejectsCharactersOutsideTheAlphabet(String code) {
        assertThatThrownBy(() -> Base62Codec.decode(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsNullAndEmpty() {
        assertThatThrownBy(() -> Base62Codec.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62Codec.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsStringsLongerThanAnyValidCode() {
        assertThatThrownBy(() -> Base62Codec.decode("aaaaaaaaaaaa")) // 12 chars
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An 11-character code is short enough to pass the length check but can
     * still exceed Long.MAX_VALUE. Without the overflow guard this would wrap
     * silently and decode to a negative id.
     */
    @Test
    void decodeRejectsAnElevenCharacterCodeThatOverflowsALong() {
        assertThat(Base62Codec.encode(Long.MAX_VALUE)).hasSize(11);

        assertThatThrownBy(() -> Base62Codec.decode("zzzzzzzzzzz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void isValidCodeAgreesWithDecode() {
        assertThat(Base62Codec.isValidCode("1000000")).isTrue();
        assertThat(Base62Codec.isValidCode("abc!")).isFalse();
        assertThat(Base62Codec.isValidCode("")).isFalse();
        assertThat(Base62Codec.isValidCode(null)).isFalse();
    }
}
