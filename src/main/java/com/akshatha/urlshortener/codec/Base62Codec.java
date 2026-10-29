package com.akshatha.urlshortener.codec;

/**
 * Converts a long id to a short Base62 string and back.
 *
 * Pure logic, no Spring. The alphabet order IS the encoding, so it is declared
 * once and both directions read from it.
 */
public final class Base62Codec {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();

    /**
     * Long.MAX_VALUE is "AzL8n0Y58m7" — 11 characters. Anything longer cannot
     * be a valid code, so we reject it before the accumulator can overflow.
     */
    public static final int MAX_ENCODED_LENGTH = 11;

    /** Reverse lookup, so decode() is an array index rather than indexOf(). */
    private static final int[] VALUES = new int[128];

    static {
        java.util.Arrays.fill(VALUES, -1);
        for (int i = 0; i < BASE; i++) {
            VALUES[ALPHABET.charAt(i)] = i;
        }
    }

    private Base62Codec() {
    }

    /**
     * encode(0) is "0", not "" — an empty code would round-trip to a path that
     * does not exist. Negative ids are rejected rather than silently wrapped;
     * every allocator in this project is monotonic from a positive seed, so a
     * negative value here means a bug upstream and should be loud.
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        // Digits fall out least-significant first; fill a buffer backwards so
        // we never have to reverse or prepend.
        char[] buf = new char[MAX_ENCODED_LENGTH];
        int pos = buf.length;
        long remaining = value;
        while (remaining > 0) {
            buf[--pos] = ALPHABET.charAt((int) (remaining % BASE));
            remaining /= BASE;
        }
        return new String(buf, pos, buf.length - pos);
    }

    /**
     * Throws on anything that is not a valid code. The redirect path never
     * calls this on untrusted input — RedirectController constrains /{code}
     * with a regex, so a malformed code 404s at routing time and the clean
     * 404 path costs no exception. Keeping decode() strict means a bad code
     * reaching it is a genuine error rather than a silent empty Optional.
     */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must be non-empty");
        }
        if (code.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    "code too long to be a valid id: " + code.length() + " chars");
        }

        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            int digit = c < 128 ? VALUES[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("illegal character '" + c + "' in code");
            }
            // An 11-char code can still exceed Long.MAX_VALUE; catch the wrap.
            if (result > (Long.MAX_VALUE - digit) / BASE) {
                throw new IllegalArgumentException("code overflows a long: " + code);
            }
            result = result * BASE + digit;
        }
        return result;
    }

    /** True if every character is in the alphabet and the length is plausible. */
    public static boolean isValidCode(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_ENCODED_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= 128 || VALUES[c] < 0) {
                return false;
            }
        }
        return true;
    }

    public static String alphabet() {
        return ALPHABET;
    }
}
