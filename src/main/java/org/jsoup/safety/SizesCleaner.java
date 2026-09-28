package org.jsoup.safety;

import org.jsoup.internal.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 Validates and rewrites {@code sizes} attributes, one size entry at a time, so the browser receives trustworthy
 candidates without widening the element's security boundary.
 <p>
 The attribute value is a comma-separated list of size entries. Entries are split on top-level commas only: a comma
 nested inside a balanced pair of parentheses (a media condition, or a {@code calc()} length) belongs to the entry
 that opened the parentheses. Each entry has an optional media condition followed by a required length value:
 </p>
 <ul>
 <li>an optional media condition is a non-empty, parenthesis-balanced expression containing only
     letters, digits, whitespace, hyphens, colons, dots, comparison characters ({@code < = >}), and nested
     parentheses; it need not be wrapped in an outer parenthesized group, so a bare media type such as
     {@code screen} is acceptable. A semicolon, brace, quote, control character, or any other character invalidates
     the entry;</li>
 <li>the length is either a zero or a non-negative decimal number immediately followed by one of
     {@code px, em, rem, vw, vh, vmin, vmax, ch, ex, %} (e.g. {@code 100vw}, {@code 0px}, {@code 1.5em}), or a
     balanced {@code calc()} expression whose body contains only digits, dots, whitespace, the {@code + - * /}
     operators, nested parentheses, and the listed units. A URL, a semicolon, a brace, a leading minus, an empty
     length, or an unbalanced parenthesis invalidates the entry.</li>
 </ul>
 <p>
 A matching {@code "} or {@code '} pair wrapping the whole value delimits it in the source markup (character
 references have already been decoded by the parser); the delimiting quotes are syntax only and never appear in the
 cleaned output. One malformed entry is dropped on its own and never swallows a following valid entry: after an
 unbalanced parenthesis, parsing resynchronizes at the next comma. Accepted entries are trimmed and joined in input
 order with a stable {@code ", "} separator; a surviving entry with the exact same trimmed spelling as an earlier
 survivor is emitted only once, at its first position, so re-cleaning is stable. When no entry
 survives, the attribute must be removed rather than left empty.
 </p>
 */
final class SizesCleaner {
    private SizesCleaner() {}

    /**
     Determine whether the supplied attribute key is a {@code sizes} attribute that should be handled entry by entry,
     rather than treated as an ordinary opaque attribute.
     @param key the attribute key to test
     @return true if it is a {@code sizes} attribute
     */
    static boolean isSizes(String key) {
        return "sizes".equalsIgnoreCase(key);
    }

    /**
     Clean a {@code sizes} attribute value into a value containing only the acceptable entries, in their original
     order. The input is never modified; callers must apply the result themselves.
     @param rawValue the original attribute value
     @return the cleaning result; {@link Result#removed} is true (with a null value) when no entry survived, in which
     case the attribute must be removed rather than left empty
     */
    static Result clean(String rawValue) {
        List<String> accepted = new ArrayList<>();
        int dropped = 0;
        if (rawValue != null && !rawValue.isEmpty()) {
            String value = stripQuotes(rawValue.trim()); // a quote pair wrapping the whole attribute value
            int len = value.length();
            int pos = 0;

            while (pos < len) {
                // Splitting loop: collect one entry. Commas separate entries only at depth zero; a comma inside a
                // balanced parenthesized region is entry content. An unmatched ')' ends the entry at that point (the
                // entry is rejected); an unmatched '(' would otherwise run to the end, so the first comma inside it
                // is remembered and used as a resync point when the group never closes, so a following valid entry
                // is not swallowed.
                int entryStart = pos;
                int depth = 0;
                int deepComma = -1;
                boolean strayClose = false;
                while (pos < len) {
                    char c = value.charAt(pos);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth < 0) strayClose = true;
                    } else if (c == ',') {
                        if (depth <= 0) break; // top-level separator
                        if (deepComma < 0) deepComma = pos; // first separator inside an unclosed group
                    }
                    pos++;
                }

                String entry;
                if (pos >= len && depth > 0 && deepComma >= 0) {
                    // the group never closed: reject the run up to its first inner comma, and reparse every later
                    // comma-separated chunk as its own entry, so a trailing legal entry is not swallowed
                    entry = value.substring(entryStart, deepComma);
                    pos = deepComma;
                } else {
                    entry = value.substring(entryStart, pos);
                }
                boolean balanced = depth == 0 && !strayClose;

                String normalized = validateEntry(entry, balanced);
                if (normalized != null) {
                    // the cleaned value never carries a repeated entry: when the same spelling survives twice it
                    // is emitted once, at its first position, so re-cleaning is stable
                    if (accepted.contains(normalized)) dropped++;
                    else accepted.add(normalized);
                } else if (!entry.trim().isEmpty() || !balanced) dropped++;

                if (pos < len && value.charAt(pos) == ',') pos++; // consume the separator
            }
        }

        if (accepted.isEmpty()) return new Result(null, dropped, true);
        return new Result(join(accepted), dropped, false);
    }

    /** The outcome of cleaning a sizes attribute: the rewritten {@code value} (or null) and statistics. */
    static final class Result {
        final String value;
        final int droppedEntries;
        final boolean removed;

        private Result(String value, int droppedEntries, boolean removed) {
            this.value = value;
            this.droppedEntries = droppedEntries;
            this.removed = removed;
        }
    }

    /**
     Validate one raw entry and return its trimmed spelling, or {@code null} when it is malformed. {@code balanced}
     reports whether the collected run ended at parenthesis depth zero; an unbalanced run can never be a valid media
     condition or length.
     <p>
     A media condition may span several parenthesized groups joined by keywords ({@code (min-width: 30em) and
     (orientation: landscape)}), so the length is located at the end of the entry: either a whitespace-free scalar
     suffix, or a balanced {@code calc(...)} group. Anything before it is the optional media condition.
     </p>
     */
    private static String validateEntry(String entry, boolean balanced) {
        String trimmed = stripQuotes(entry.trim()); // a quote pair delimiting this single entry
        if (trimmed.isEmpty() || !balanced) return null;
        if (containsUnsafeChar(trimmed)) return null;
        if (containsScriptFragment(trimmed)) return null;

        int lengthStart = findLengthStart(trimmed);
        if (lengthStart < 0) return null;

        String length = trimmed.substring(lengthStart).trim();
        if (!isValidLength(length)) return null;

        if (lengthStart > 0) {
            String condition = trimmed.substring(0, lengthStart);
            // the condition and the length must be separated by whitespace
            if (!StringUtil.isWhitespace(condition.charAt(condition.length() - 1))) return null;
            if (!isValidMediaCondition(condition.trim())) return null;
        }
        return trimmed;
    }

    /**
     Return the index at which the trailing length begins, or {@code -1} when the entry does not end in a recognizable
     length. A {@code calc(...)} length ends in a balanced {@code )} whose matching {@code (} is preceded by the word
     {@code calc} at a token boundary; any other ending must be a single whitespace-free scalar token.
     */
    private static int findLengthStart(String s) {
        int n = s.length();
        if (s.charAt(n - 1) == ')') {
            int depth = 0;
            int i = n - 1;
            do {
                char c = s.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') depth--;
                i--;
            } while (i >= 0 && depth > 0);
            int open = i + 1; // index of the matching '('
            int wordStart = open;
            while (wordStart > 0 && isAsciiLetter(s.charAt(wordStart - 1))) wordStart--;
            if (open - wordStart == 4 && s.regionMatches(true, wordStart, "calc", 0, 4)
                && (wordStart == 0 || StringUtil.isWhitespace(s.charAt(wordStart - 1)))) {
                return wordStart;
            }
        }
        int ws = s.length() - 1;
        while (ws >= 0 && !StringUtil.isWhitespace(s.charAt(ws))) ws--;
        return ws + 1;
    }

    /**
     Validate the media condition text. It must hold a substantive token (a non-whitespace character other than a
     parenthesis), only the permitted media-query characters, and have balanced nested parentheses; it need not be
     wrapped in an outer parenthesized group, so a bare media type such as {@code screen} is acceptable, while an
     empty {@code ()} group is not a condition. No semicolons, braces, quotes, or other punctuation that could carry
     stylesheet or script content; a script scheme smuggled in with whitespace inside its name is also rejected.
     */
    private static boolean isValidMediaCondition(String condition) {
        String s = condition.trim();
        if (s.isEmpty()) return false;
        int depth = 0;
        boolean hasToken = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) return false;
            } else if (!isMediaConditionChar(c)) {
                return false;
            } else if (!StringUtil.isWhitespace(c)) {
                hasToken = true; // an empty () or ( ) group is not a media condition
            }
        }
        return depth == 0 && hasToken;
    }

    private static boolean isMediaConditionChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;
        return c == '-' || c == ':' || c == '.' || c == '<' || c == '>' || c == '='
            || StringUtil.isWhitespace(c);
    }

    /**
     Validate the length token: either a plain non-negative decimal scalar followed by a recognized length unit, or a
     balanced {@code calc()} expression over those same scalars and units.
     */
    private static boolean isValidLength(String s) {
        if (s.isEmpty()) return false;
        if (isAsciiLetter(s.charAt(0))) {
            // only a calc(...) expression may lead with a letter; any other word (url, expression, attr, ...) is out
            if (!startsWithCalc(s)) return false;
            if (s.charAt(s.length() - 1) != ')') return false;
            return isValidCalcBody(s.substring(5, s.length() - 1));
        }
        return isScalarLength(s);
    }

    private static boolean startsWithCalc(String s) {
        return s.length() > 5 && s.regionMatches(true, 0, "calc(", 0, 5);
    }

    /**
     Validate the body of a {@code calc()} expression: balanced parentheses, and only non-negative scalars with
     recognized units (or bare numbers) joined by the {@code + - * /} operators. There is no leading minus, no empty
     operand, no URL, no semicolon, and no brace.
     */
    private static boolean isValidCalcBody(String body) {
        String s = body.trim();
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (first == '-' || first == '+' || first == '*' || first == '/') return false;

        int depth = 0;
        int i = 0;
        int n = s.length();
        boolean prevOperand = false; // the previous token was a scalar or ')', so an operator may come next
        while (i < n) {
            char c = s.charAt(i);
            if (StringUtil.isWhitespace(c)) { i++; continue; }
            if (c == '(') {
                if (prevOperand) return false; // an operand must be followed by an operator, not a group
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth < 0 || !prevOperand) return false; // empty group or unbalanced close
                i++;
                continue;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                if (!prevOperand) return false; // missing left operand (also rejects doubled operators)
                prevOperand = false;
                i++;
                continue;
            }
            if (isAsciiLetter(c)) {
                // the only word legal at an operand position is a nested calc(...): a balanced group over the same
                // grammar. Any other word (url, expression, attr, an unknown unit spelling, ...) is rejected.
                if (!s.regionMatches(true, i, "calc(", 0, 5)) return false;
                int close = matchingClose(s, i + 5);
                if (close < 0 || !isValidCalcBody(s.substring(i + 5, close))) return false;
                i = close + 1;
                if (i < n) {
                    char next = s.charAt(i);
                    if (!StringUtil.isWhitespace(next) && next != '+' && next != '-' && next != '*'
                        && next != '/' && next != ')') return false;
                }
                prevOperand = true;
                continue;
            }
            // otherwise a scalar operand: a non-negative number with an optional recognized unit
            int numEnd = readNumber(s, i);
            if (numEnd == i) return false;
            int afterUnit = readUnit(s, numEnd);
            if (afterUnit != numEnd) {
                i = afterUnit; // a unit followed the number
            } else {
                i = numEnd; // bare number (legal inside calc), but never a bare word or unknown unit
                if (i < n && isAsciiLetter(s.charAt(i))) return false;
            }
            // the operand must end at whitespace, an operator, a parenthesis, or the end of the body
            if (i < n) {
                char next = s.charAt(i);
                if (!StringUtil.isWhitespace(next) && next != '+' && next != '-' && next != '*'
                    && next != '/' && next != ')') return false;
            }
            prevOperand = true;
        }
        return depth == 0 && prevOperand;
    }

    /** Read a non-negative decimal number ({@code 0}, {@code 12}, {@code 1.5}, {@code .5}); no sign or exponent. */
    private static int readNumber(String s, int start) {
        int i = start;
        int n = s.length();
        int digits = 0;
        while (i < n && isAsciiDigit(s.charAt(i))) { digits++; i++; }
        if (i < n && s.charAt(i) == '.') {
            i++;
            int frac = 0;
            while (i < n && isAsciiDigit(s.charAt(i))) { frac++; i++; }
            if (frac == 0) return start; // a trailing or fraction-less dot is not a number
        } else if (digits == 0) {
            return start;
        }
        return i;
    }

    /** Read a recognized length unit starting at {@code start}; return the index just past it, or {@code start}. */
    private static int readUnit(String s, int start) {
        int n = s.length();
        if (start < n && s.charAt(start) == '%') return start + 1;
        String[] units = {"vmin", "vmax", "rem", "vw", "vh", "em", "px", "ch", "ex"};
        for (String unit : units) {
            int end = start + unit.length();
            if (end <= n && s.regionMatches(true, start, unit, 0, unit.length())) return end;
        }
        return start;
    }

    /** A plain scalar length: a non-negative decimal number immediately followed by a recognized unit. */
    private static boolean isScalarLength(String s) {
        int numEnd = readNumber(s, 0);
        if (numEnd == 0) return false;
        int unitEnd = readUnit(s, numEnd);
        return unitEnd != numEnd && unitEnd == s.length();
    }

    /** Given the index just inside a {@code (} group, return the index of its matching {@code )}, or {@code -1}. */
    private static int matchingClose(String s, int start) {
        int depth = 1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     Reject characters that can never appear in a valid entry: control characters (other than HTML whitespace),
     quotes, semicolons, and braces. These are the characters that could smuggle stylesheet or script content through
     a length or media condition. Both the C0 range (and DEL) and the C1 range U+0080–U+009F count as control
     characters here.
     */
    private static boolean containsUnsafeChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == ';' || c == '{' || c == '}') return true;
            if (c <= 0x1f && !StringUtil.isWhitespace(c)) return true;
            if (c == 0x7f || (c >= 0x80 && c <= 0x9f)) return true;
        }
        return false;
    }

    /**
     Reject URL and script fragments that use only otherwise-permitted characters. A {@code url(} token is never
     valid in a media condition or length, and a script scheme name (optionally broken up by whitespace or an HTML
     entity-decoded tab, e.g. {@code java&Tab;script:}) must never survive. Whitespace is flattened before the
     substring test so spacing inside a scheme name cannot hide it.
     */
    private static boolean containsScriptFragment(String s) {
        String lc = s.toLowerCase();
        if (lc.contains("url(")) return true;
        String flattened = StringUtil.normaliseWhitespace(lc).replace(" ", "");
        return flattened.contains("javascript:") || flattened.contains("vbscript:")
            || flattened.contains("livescript:") || flattened.contains("data:");
    }

    /**
     Strip a single pair of matching delimiting quotes wrapping the whole text. The delimiters are parse-time syntax
     only; they are removed only when the same quote does not also occur inside the text (that is per-entry quoting,
     handled entry by entry), and a quote anywhere else is left in place and rejected by entry validation rather than
     treated as syntax.
     */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last
                && s.indexOf(first, 1) == s.length() - 1) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String join(List<String> entries) {
        StringBuilder sb = StringUtil.borrowBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(entries.get(i));
        }
        return StringUtil.releaseBuilder(sb);
    }
}
