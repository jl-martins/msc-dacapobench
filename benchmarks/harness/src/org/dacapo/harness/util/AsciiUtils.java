package org.dacapo.harness.util;

import java.util.function.IntPredicate;

public final class AsciiUtils {
    private static final long POSITIVE_LONG_PARSING_CUTOFF = Long.MAX_VALUE / 10L;

    private AsciiUtils() {
    }

    public static int parseFirstUnsignedInt(String s) {
        final long longResult = parseFirstUnsignedLong(s, "int");
        if (longResult > Integer.MAX_VALUE) {
            throw new NumberFormatException("int overflow for string: " + s);
        }

        return (int) longResult;
    }

    public static long parseFirstUnsignedLong(String s) {
        return parseFirstUnsignedLong(s, "long");
    }

    private static long parseFirstUnsignedLong(String s, String typeLabel) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string");
        }

        final int firstDigitIndex = indexOfFirstDigit(s);
        if (firstDigitIndex == -1) {
            throw new NumberFormatException("String \"" + s + "\" does not contain a parseable " + typeLabel);
        }

        final int n = s.length();
        long result = 0L;
        for (int i = firstDigitIndex; i < n && isAsciiDigit(s.charAt(i)); i++) {
            int digit = s.charAt(i) - '0';

            if (result > POSITIVE_LONG_PARSING_CUTOFF || (result == POSITIVE_LONG_PARSING_CUTOFF && digit > 7)) {
                throw new NumberFormatException(typeLabel + " overflow for string: " + s);
            }

            result = 10L * result + digit;
        }

        return result;
    }

    public static int indexOfFirstDigit(String s) {
        return indexOf(s, AsciiUtils::isAsciiDigit);
    }

    public static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static int indexOf(String s, IntPredicate predicate) {
        final int n = s.length();

        for (int i = 0; i < n; ++i) {
            if (predicate.test(s.charAt(i))) {
                return i;
            }
        }

        return -1;
    }
}
