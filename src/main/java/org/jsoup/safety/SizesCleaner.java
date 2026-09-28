package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Attribute;

import java.util.ArrayList;
import java.util.List;

/**
 Validates and rewrites {@code sizes} attributes (on responsive {@code img} and {@code source} elements), one
 source-size entry at a time.
 <p>
 The value is split into entries on top-level commas only: a comma nested inside a pair of parentheses (a media
 condition or a {@code calc()} expression) belongs to the current entry, so it never starts the next one. Each entry
 has an optional, non-empty, parenthesis-balanced media condition followed by a mandatory length value. Whitespace and
 the delimiting quotes a parser may have carried on the value are syntax only and never appear in the cleaned output.
 One malformed entry invalidates only that entry; the following entries are still parsed. When no entry survives, the
 attribute is removed rather than left empty.
 </p>
 <p>
 Unlike {@code srcset}, a {@code sizes} value holds no URLs and is checked against no protocol policy; its grammar is
 the whole boundary. The media condition may contain only letters, digits, whitespace, hyphens, colons, dots,
 comparison operators, and nested parentheses, and a length is either zero or a non-negative decimal number with one
 of the fixed CSS length units ({@code px}, {@code em}, {@code rem}, {@code vw}, {@code vh}, {@code vmin},
 {@code vmax}, {@code ch}, {@code ex}, or {@code %}), or a parenthesis-balanced {@code calc()} expression using just
 those units, numbers, whitespace, and {@code + - * /}. Semicolons, braces, control characters, URL fragments,
 unbalanced parentheses, and empty lengths are rejected, so the attribute can never carry a declaration block or a
 script fragment past the cleaner.
 </p>
 */
final class SizesCleaner {
    private SizesCleaner() {}

    /**
     Determine whether the supplied attribute is a {@code sizes} attribute that should be handled entry by entry,
     rather than treated as an ordinary opaque attribute.
     @param attr the attribute to test
     @return true if it is a {@code sizes} attribute
     */
    static boolean isSizes(Attribute attr) {
        return "sizes".equalsIgnoreCase(attr.getKey());
    }

    /**
     Clean a {@code sizes} attribute value into a value containing only acceptable entries, in their original order.
     The input is not modified; callers must apply the result themselves.
     @param rawValue the original attribute value
     @return the cleaning result; {@link Result#removed} is true (with a null value) when no entry survived, in which
     case the attribute must be removed rather than left empty
     */
    static Result clean(String rawValue) {
        List<String> accepted = new ArrayList<>();
        int dropped = 0;
        if (rawValue != null && !rawValue.isEmpty()) {
            int len = rawValue.length();
            int pos = 0;
            while (pos < len) {
                // 1. Split on top-level commas only: parens (media conditions, calc()) own their commas. Whitespace,
                //    empty items, and repeated commas are skipped here and never raise an error.
                while (pos < len && isWsOrComma(rawValue.charAt(pos))) pos++;
                if (pos >= len) break;

                int start = pos;
                int depth = 0;
                int firstCommaInParen = -1; // resync point if an opening paren never closes
                while (pos < len) {
                    char c = rawValue.charAt(pos);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        if (depth == 0) {
                            // unmatched close: carry it with this entry (whose grammar check will fail) but still
                            // advance, so a later comma can resync parsing onto the following entries
                            pos++;
                            continue;
                        }
                        depth--;
                    } else if (c == ',') {
                        if (depth == 0) break;
                        if (firstCommaInParen < 0) firstCommaInParen = pos; // remember the first swallowed comma
                    }
                    pos++;
                }
                int entryEnd = pos;
                if (depth > 0 && firstCommaInParen >= 0) {
                    // an opening paren never closed: drop the malformed entry alone and resync at its first swallowed
                    // comma, so a later valid entry is not lost with it
                    entryEnd = firstCommaInParen;
                    pos = firstCommaInParen + 1;
                }
                String entry = rawValue.substring(start, entryEnd).trim();
                if (pos < len && rawValue.charAt(pos) == ',') pos++; // consume the entry separator

                entry = stripDelimitingQuotes(entry);
                String normalized = cleanEntry(entry);
                if (normalized != null)
                    accepted.add(normalized);
                else
                    dropped++;
            }
        }

        if (accepted.isEmpty()) return new Result(null, dropped, true);
        StringBuilder sb = StringUtil.borrowBuilder();
        for (int i = 0; i < accepted.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(accepted.get(i));
        }
        return new Result(StringUtil.releaseBuilder(sb), dropped, false);
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
     Strip one layer of matching {@code "} or {@code '} quotes delimiting the whole entry, the way the HTML parser
     treats a quoted token: the quotes are syntax only. An unterminated, mismatched, or embedded quote is left in
     place so the grammar check rejects the entry.
     */
    private static String stripDelimitingQuotes(String entry) {
        if (entry.length() >= 2) {
            char first = entry.charAt(0);
            char last = entry.charAt(entry.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                String inner = entry.substring(1, entry.length() - 1);
                if (inner.indexOf(first) < 0) return inner.trim();
            }
        }
        return entry;
    }

    private static String cleanEntry(String entry) {
        entry = entry.trim();
        if (entry.isEmpty() || containsControlChar(entry)) return null;
        // delimiting quotes are parse-time syntax only and never valid media/length grammar, so any quote that was
        // not a boundary delimiter (and thus not stripped up front) invalidates the entry
        if (entry.indexOf('"') >= 0 || entry.indexOf('\'') >= 0) return null;
        if (entry.indexOf(';') >= 0 || entry.indexOf('{') >= 0 || entry.indexOf('}') >= 0) return null;
        if (!isBalanced(entry)) return null;

        // Find where the mandatory length begins, scanning only at parenthesis depth 0: a length is either a plain
        // number+unit token or a calc(...) expression, and is what any leading media condition is followed by. A
        // digit or a calc( only appear at depth 0 as the length itself.
        int depth = 0;
        int lengthStart = -1;
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (depth > 0) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (entry.regionMatches(true, i, "calc(", 0, 5)) {
                lengthStart = i; // a calc( expression begins with the letters "calc"
                break;
            } else if (isAsciiDigit(c)) {
                lengthStart = i;
                break;
            }
        }
        if (lengthStart < 0) return null; // no length token at all

        String media = null;
        String length;
        if (lengthStart == 0) {
            length = entry;
        } else {
            // the media condition and length must be separated by whitespace; a condition glued to the length is not
            // the sizes grammar
            if (!StringUtil.isWhitespace(entry.charAt(lengthStart - 1))) return null;
            media = entry.substring(0, lengthStart).trim();
            if (media.isEmpty() || !isValidMedia(media) || containsScriptFragment(media)) return null;
            length = entry.substring(lengthStart).trim();
        }
        if (!isValidLength(length)) return null;

        return media == null ? length : media + " " + length;
    }

    /**
     Validate an optional media condition: non-empty, parenthesis-balanced (groups may nest), containing at least one
     parenthesized group, and limited to a restricted character set. At the top level a media condition consists of
     identifiers, whitespace, hyphens, and the grouped features only, so a colon, digit, dot, or comparison operator
     found outside a group (the shape a smuggled {@code javascript:...} prefix has) rejects the condition; inside a
     group the feature grammar additionally permits digits, colons, dots, and the {@code < > =} comparisons. A bare
     pair of empty parentheses, an empty group, a comma, or any other character invalidates the condition.
     */
    private static boolean isValidMedia(String media) {
        if (media.isEmpty()) return false;
        int depth = 0;
        int groups = 0;
        boolean[] groupContent = new boolean[Math.max(8, count('(', media) + 1)];
        for (int i = 0; i < media.length(); i++) {
            char c = media.charAt(i);
            if (c == '(') {
                depth++;
                groups++;
                if (depth < groupContent.length) groupContent[depth] = false;
                continue;
            }
            if (c == ')') {
                if (depth == 0 || !groupContent[depth]) return false; // unmatched or empty group
                depth--;
                groupContent[depth] = true; // a non-empty inner group supplies content to its enclosing group
                continue;
            }
            if (!isMediaChar(c, depth)) return false;
            if (!StringUtil.isWhitespace(c)) groupContent[depth] = true;
        }
        return depth == 0 && groups > 0;
    }

    /**
     Whether a character is legal at a given media-condition nesting depth. At depth 0 only identifier characters,
     whitespace, and hyphens are legal (feature values never appear there); inside a feature group, digits, colons,
     dots, and comparison operators are additionally allowed.
     */
    private static boolean isMediaChar(char c, int depth) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (StringUtil.isWhitespace(c)) return true;
        if (c == '-') return true;
        if (depth == 0) return false;
        if (c >= '0' && c <= '9') return true;
        switch (c) {
            case ':': case '.':
            case '<': case '>': case '=':
                return true;
            default:
                return false;
        }
    }

    /**
     Validate the mandatory length: either zero / a non-negative decimal number with a fixed CSS length unit, or a
     balanced {@code calc()} expression whose body uses only those units, numbers, whitespace, and arithmetic.
     */
    private static boolean isValidLength(String length) {
        if (length.isEmpty()) return false;
        int p = 0;
        while (p < length.length() && StringUtil.isWhitespace(length.charAt(p))) p++;
        if (p >= length.length()) return false;

        // a calc() expression starts with the letters "calc" (a leading parenthesis is not part of its spelling)
        if (length.regionMatches(true, p, "calc(", 0, 5)) {
            // the expression runs to the final non-whitespace character, which must be its closing parenthesis
            int end = length.length();
            while (end > p && StringUtil.isWhitespace(length.charAt(end - 1))) end--;
            if (end <= p + 5 || length.charAt(end - 1) != ')') return false; // unclosed or empty expression
            String body = length.substring(p + 5, end - 1);
            return isValidCalcBody(body);
        }
        return isValidSimpleLength(length.substring(p));
    }

    /**
     Validate a {@code calc()} body: balanced, and limited to numbers, the fixed length units, whitespace, and the
     {@code + - * /} operators. URL fragments, semicolons, braces, letters outside a unit spelling, a leading minus
     sign, and empty operands are all rejected.
     */
    private static boolean isValidCalcBody(String body) {
        if (!isBalanced(body)) return false;
        int i = 0;
        int len = body.length();
        boolean expectOperand = true;
        boolean[] groupHasContent = new boolean[Math.max(8, count('(', body) + 1)];
        int depth = 0;
        while (i < len) {
            char c = body.charAt(i);
            if (StringUtil.isWhitespace(c)) { i++; continue; }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                if (expectOperand) {
                    // a leading unary sign would let a length begin negative; none are permitted
                    return false;
                }
                expectOperand = true;
                i++;
                continue;
            }
            if (c == '(') {
                if (!expectOperand) return false; // a group is itself an operand: it cannot follow another operand
                depth++;
                if (depth >= groupHasContent.length) {
                    boolean[] grown = new boolean[depth + 8];
                    System.arraycopy(groupHasContent, 0, grown, 0, groupHasContent.length);
                    groupHasContent = grown;
                }
                groupHasContent[depth] = false;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth == 0 || expectOperand || !groupHasContent[depth]) return false; // unmatched/empty group
                depth--;
                groupHasContent[depth] = true; // the closed group supplies content to its parent
                i++;
                continue;
            }
            if (!expectOperand) return false; // two operands with no operator between them
            int numEnd = readNumber(body, i);
            if (numEnd == i) return false; // a letter here that is not part of a unit is invalid
            i = readUnit(body, numEnd);
            groupHasContent[depth] = true;
            expectOperand = false;
        }
        return !expectOperand && depth == 0;
    }

    private static int count(char ch, String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    /**
     Validate a plain length: zero is accepted on its own; any other non-negative decimal number must be immediately
     followed by one fixed unit spelling. A leading sign is never accepted.
     */
    private static boolean isValidSimpleLength(String length) {
        int numEnd = readNumber(length, 0);
        if (numEnd == 0) return false; // a leading sign (e.g. "-5px") is not permitted
        if (numEnd == length.length()) return isZeroValue(length); // a bare number is valid only when it is zero
        String rest = length.substring(numEnd);
        return isUnit(rest);
    }

    private static boolean isZeroValue(String s) {
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (c != '0') return false;
                hasDigit = true;
            } else if (c != '.') {
                return false;
            }
        }
        return hasDigit;
    }

    /**
     Read a non-negative decimal number starting at {@code start}: one or more digits, optionally followed by a dot
     with one or more trailing digits (a leading or lone dot is not accepted). Returns the index just past the
     number, or {@code start} when no number begins there.
     */
    private static int readNumber(String s, int start) {
        int i = start;
        int len = s.length();
        int intDigits = 0;
        while (i < len && isAsciiDigit(s.charAt(i))) { intDigits++; i++; }
        if (i < len && s.charAt(i) == '.') {
            int j = i + 1;
            int fracDigits = 0;
            while (j < len && isAsciiDigit(s.charAt(j))) { fracDigits++; j++; }
            if (fracDigits == 0) return start; // a trailing or lone dot is not a number
            i = j;
        }
        return intDigits == 0 ? start : i;
    }

    /**
     Read the optional length unit following a number at {@code start} (with no separating whitespace). Returns the
     index just past the unit, or {@code start} when no known unit begins there.
     */
    private static int readUnit(String s, int start) {
        String[] units = {"vmin", "vmax", "rem", "px", "em", "vw", "vh", "ch", "ex", "%"};
        String lower = s.toLowerCase();
        for (String unit : units) {
            if (lower.startsWith(unit, start)) return start + unit.length();
        }
        return start;
    }

    private static boolean isUnit(String s) {
        return readUnit(s, 0) == s.length() && !s.isEmpty();
    }

    private static boolean isBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return false;
                depth--;
            }
        }
        return depth == 0;
    }

    private static boolean isWsOrComma(char c) {
        return c == ',' || StringUtil.isWhitespace(c);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     Detect an executable or URL fragment smuggled into an otherwise well-formed media condition, e.g.
     {@code (x: url(javascript:alert(1)))} whose parentheses balance and whose individual characters are otherwise
     permitted. Character references have already been decoded by the parser, so a case-insensitive substring test
     suffices.
     */
    private static boolean containsScriptFragment(String s) {
        String lc = s.toLowerCase();
        return lc.contains("javascript:")
            || lc.contains("vbscript:")
            || lc.contains("data:")
            || lc.contains("url(")
            || lc.contains("expression(");
    }

    private static boolean containsControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }
}
