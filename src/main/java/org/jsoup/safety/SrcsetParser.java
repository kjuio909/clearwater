package org.jsoup.safety;

import org.jsoup.internal.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 Parser for the HTML {@code srcset} attribute, following the "parse a srcset attribute" algorithm in the HTML standard.
 Splits the attribute value into image candidates (a URL and optional width or pixel density descriptors), so that the
 {@link Cleaner} can validate each candidate URL individually. Commas only separate candidates at candidate boundaries;
 commas within a URL (e.g. in {@code data:} URLs) do not split. Candidates with missing, duplicate, or malformed
 descriptors, or with out-of-range descriptor values, are dropped individually; empty items (from leading, trailing, or
 repeated commas) are ignored without failing the parse.
 */
final class SrcsetParser {
    private SrcsetParser() {
    }

    /** A single validated image candidate: a URL and its descriptors (e.g. {@code 100w} or {@code 2x}). */
    static final class Candidate {
        final String url;
        final List<String> descriptors; // validated descriptor tokens, in source order; empty if none

        Candidate(String url, List<String> descriptors) {
            this.url = url;
            this.descriptors = descriptors;
        }
    }

    /** The result of parsing a srcset attribute value. */
    static final class Result {
        final List<Candidate> candidates = new ArrayList<>(); // valid candidates, in source order
        boolean dropped = false; // true if any candidate was dropped as invalid
    }

    private static final int InDescriptor = 0;
    private static final int InParens = 1;
    private static final int AfterDescriptor = 2;

    /** Parses a srcset attribute value into its valid candidates, in source order. Never throws. */
    static Result parse(String input) {
        Result result = new Result();
        int pos = 0;
        final int length = input.length();

        // splitting loop
        while (true) {
            // collect leading whitespace and commas; empty candidates are ignored
            while (pos < length && (StringUtil.isWhitespace(input.charAt(pos)) || input.charAt(pos) == ','))
                pos++;
            if (pos >= length)
                return result;

            // collect the URL: a run of non-whitespace (may contain commas, e.g. in data: URLs)
            final int start = pos;
            while (pos < length && !StringUtil.isWhitespace(input.charAt(pos)))
                pos++;
            String url = input.substring(start, pos);

            List<String> descriptors = new ArrayList<>();
            if (url.endsWith(",")) {
                // a URL ending in commas has no descriptors; the commas are candidate separators
                int end = url.length();
                while (end > 0 && url.charAt(end - 1) == ',')
                    end--;
                url = url.substring(0, end);
            } else {
                pos = tokenizeDescriptors(input, pos, descriptors);
            }

            if (url.isEmpty()) // defensive; the splitting loop consumes comma-only runs
                continue;

            if (validDescriptors(descriptors))
                result.candidates.add(new Candidate(url, descriptors));
            else
                result.dropped = true;
        }
    }

    /**
     Collects the descriptor tokens of one candidate, starting at {@code pos} (just past the URL). Returns the position
     to resume the splitting loop from (just past a terminating comma, at the start of the next candidate, or at EOF).
     */
    private static int tokenizeDescriptors(String input, int pos, List<String> descriptors) {
        final int length = input.length();
        while (pos < length && StringUtil.isWhitespace(input.charAt(pos)))
            pos++;

        StringBuilder current = new StringBuilder();
        int state = InDescriptor;

        while (true) {
            final boolean eof = pos >= length;
            final char c = eof ? ' ' : input.charAt(pos);

            switch (state) {
                case InDescriptor:
                    if (eof) {
                        if (current.length() > 0) descriptors.add(current.toString());
                        return pos;
                    } else if (StringUtil.isWhitespace(c)) {
                        if (current.length() > 0) {
                            descriptors.add(current.toString());
                            current.setLength(0);
                        }
                        state = AfterDescriptor;
                    } else if (c == ',') {
                        if (current.length() > 0) descriptors.add(current.toString());
                        return pos + 1;
                    } else if (c == '(') {
                        current.append(c);
                        state = InParens;
                    } else {
                        current.append(c);
                    }
                    break;
                case InParens:
                    if (eof) {
                        if (current.length() > 0) descriptors.add(current.toString());
                        return pos;
                    } else if (c == ')') {
                        current.append(c);
                        state = InDescriptor;
                    } else {
                        current.append(c);
                    }
                    break;
                case AfterDescriptor:
                    if (eof) {
                        return pos;
                    } else if (StringUtil.isWhitespace(c)) {
                        // stay in this state
                    } else if (c == ',') {
                        return pos + 1;
                    } else {
                        state = InDescriptor;
                        pos--; // re-read this character as the start of a new descriptor
                    }
                    break;
            }
            pos++;
        }
    }

    /**
     Validates the descriptor tokens of one candidate. Only a width ({@code 100w}) or a pixel density ({@code 2x})
     descriptor is supported, at most one of each and not both; values must be syntactically valid and greater than
     zero.
     */
    private static boolean validDescriptors(List<String> descriptors) {
        boolean hasWidth = false;
        boolean hasDensity = false;
        for (String descriptor : descriptors) {
            final int len = descriptor.length();
            final char suffix = len > 0 ? descriptor.charAt(len - 1) : '\0';
            if (len > 1 && suffix == 'w') {
                if (hasWidth || hasDensity) return false;
                String number = descriptor.substring(0, len - 1);
                if (!isNonNegativeInteger(number) || isAllZeros(number)) return false;
                hasWidth = true;
            } else if (len > 1 && suffix == 'x') {
                if (hasWidth || hasDensity) return false;
                String number = descriptor.substring(0, len - 1);
                if (!isValidFloat(number)) return false;
                final double value;
                try {
                    value = Double.parseDouble(number);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (!(value > 0) || Double.isInfinite(value)) return false; // also rejects NaN
                hasDensity = true;
            } else {
                return false; // missing, unknown, or unsupported (e.g. h) descriptor
            }
        }
        return true;
    }

    /** Tests if the string is a valid non-negative integer (one or more ASCII digits). */
    private static boolean isNonNegativeInteger(String string) {
        final int length = string.length();
        if (length == 0) return false;
        for (int i = 0; i < length; i++) {
            char c = string.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isAllZeros(String string) {
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) != '0') return false;
        }
        return true;
    }

    private static final java.util.regex.Pattern ValidFloat =
        java.util.regex.Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /** Tests if the string is a valid floating-point number, per the HTML definition. */
    private static boolean isValidFloat(String string) {
        return ValidFloat.matcher(string).matches();
    }
}
