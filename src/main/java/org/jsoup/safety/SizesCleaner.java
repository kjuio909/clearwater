package org.jsoup.safety;

import org.jsoup.internal.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> accepted = new LinkedHashSet<>();
        int dropped = 0;
        if (rawValue != null && !rawValue.isEmpty()) {
            String value = stripQuotes(rawValue.trim()); // a quote pair wrapping the whole attribute value

            List<int[]> chunks = splitEntries(value);
            for (int[] chunk : chunks) {
                String entry = value.substring(chunk[0], chunk[1]).trim();
                String normalized = validateEntry(entry);
                if (normalized != null) {
                    // the cleaned value never carries a repeated entry: when the same spelling survives twice it
                    // is emitted once, at its first position, so re-cleaning is stable
                    if (!accepted.add(normalized)) dropped++;
                } else if (!entry.isEmpty()) {
                    dropped++;
                }
            }
        }

        if (accepted.isEmpty()) return new Result(null, dropped, true);
        return new Result(join(accepted), dropped, false);
    }

    /**
     Split a sizes value into entry character ranges {@code [start, end)} using the top-level commas only.
     <p>
     One forward scan records the running parenthesis depth at every comma and at the end. An entry starts at depth
     zero of its own, so a comma ends an entry while at (or below) the depth recorded at the entry's first character;
     a comma nested deeper is inside that entry's parenthesized content (the whole entry is validated later and fails
     as one, because neither a media condition nor a calc() body may contain a comma). When the value ends with
     groups still open, parsing recovers at the first comma inside the unclosed group and continues with that comma's
     depth as the new baseline, so a malformed entry never swallows a following valid one.
     </p>
     <p>
     The recovery is reproduced without rescanning: for each comma a monotonic stack gives the nearest later comma at
     no greater depth, and recovery moves to the immediately following comma (a strictly greater depth) while the end
     is still unclosed. Both passes and the boundary walk are linear, even for values made of thousands of unbalanced
     groups.
     </p>
     @return entry ranges in input order; ranges may be empty for consecutive commas
     */
    private static List<int[]> splitEntries(String value) {
        int len = value.length();
        int[] commaPos = new int[Math.min(8, len)];
        int[] commaDepth = new int[commaPos.length];
        int commas = 0;
        int depth = 0;
        for (int p = 0; p < len; p++) {
            char c = value.charAt(p);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',') {
                if (commas == commaPos.length) {
                    commaPos = Arrays.copyOf(commaPos, commas * 2);
                    commaDepth = Arrays.copyOf(commaDepth, commas * 2);
                }
                commaPos[commas] = p;
                commaDepth[commas] = depth;
                commas++;
            }
        }
        int endDepth = depth;

        // nextLE[i] is the nearest later comma at a depth no greater than comma i's: the next ordinary boundary once
        // an entry starts at comma i. A monotonic stack (greater depths popped) computes all of them in reverse.
        int[] nextLE = new int[commas];
        int[] stack = new int[commas];
        int stackSize = 0;
        int firstLEZero = -1; // earliest comma at or below the initial depth zero
        for (int i = commas - 1; i >= 0; i--) {
            int d = commaDepth[i];
            while (stackSize > 0 && commaDepth[stack[stackSize - 1]] > d) stackSize--;
            nextLE[i] = stackSize > 0 ? stack[stackSize - 1] : -1;
            stack[stackSize++] = i;
            if (d <= 0) firstLEZero = i;
        }

        // Walk the boundaries. Baseline depth is the depth at the previous boundary comma (zero before the first).
        // The next boundary is the nearest later comma at no greater depth; when none exists before the end yet the
        // value still leaves groups open, recovery starts the next entry at the very next comma instead.
        boolean[] boundary = new boolean[commas];
        int pos = -1;
        int baseDepth = 0;
        while (true) {
            int next = (pos < 0) ? firstLEZero : nextLE[pos];
            if (next >= 0) {
                boundary[next] = true;
                pos = next;
                baseDepth = commaDepth[next];
            } else if (endDepth > baseDepth && pos + 1 < commas) {
                // this entry never closed: recover at its first inner comma (a strictly greater depth)
                int recover = pos + 1;
                boundary[recover] = true;
                pos = recover;
                baseDepth = commaDepth[recover];
            } else {
                break;
            }
        }

        List<int[]> chunks = new ArrayList<>(commas + 1);
        int start = 0;
        for (int i = 0; i < commas; i++) {
            if (boundary[i]) {
                chunks.add(new int[]{start, commaPos[i]});
                start = commaPos[i] + 1;
            }
        }
        chunks.add(new int[]{start, len});
        return chunks;
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
     Validate one entry (a top-level-comma-delimited range from {@link #splitEntries}) and return its trimmed
     spelling, or {@code null} when it is malformed.
     <p>
     An entry is rejected outright when its parentheses do not balance (a stray close, or a group that never closes);
     the splitter's recovery keeps that imbalance from leaking across entries. A media condition may span several
     parenthesized groups joined by keywords ({@code (min-width: 30em) and (orientation: landscape)}), so the length
     is located at the end of the entry: either a whitespace-free scalar suffix, or a balanced {@code calc(...)}
     group. Anything before it is the optional media condition.
     </p>
     */
    private static String validateEntry(String entry) {
        String trimmed = stripQuotes(entry.trim()); // a quote pair delimiting this single entry
        if (trimmed.isEmpty() || !isParenBalanced(trimmed)) return null;
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

    /** Whether every parenthesis in the entry opens and closes within it, in proper nesting order. */
    private static boolean isParenBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return false;
        }
        return depth == 0;
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
            return isValidCalcBody(s, 5, s.length() - 1);
        }
        return isScalarLength(s);
    }

    private static boolean startsWithCalc(String s) {
        return s.length() > 5 && s.regionMatches(true, 0, "calc(", 0, 5);
    }

    /**
     Validate the body of a {@code calc()} expression over the index range {@code [from, to)} of {@code s}: balanced
     parentheses, and only non-negative scalars with recognized units (or bare numbers) joined by the
     {@code + - * /} operators. There is no leading minus, no empty operand, no URL, no semicolon, and no brace.
     <p>
     The expression is validated in place with a single flat token scan, mirroring the grammar one nesting level at a
     time: plain parenthesized groups are counted inline, while a nested {@code calc(...)} pushes its body range as a
     frame on heap-allocated arrays. Neither the call stack nor a per-level copy grows with nesting depth, so a deeply
     nested (or pathologically long) expression cannot exhaust the stack or quadratic memory.
     </p>
     */
    private static boolean isValidCalcBody(String s, int from, int to) {
        int a = from;
        int b = to;
        while (a < b && StringUtil.isWhitespace(s.charAt(a))) a++;
        while (b > a && StringUtil.isWhitespace(s.charAt(b - 1))) b--;
        if (a == b) return false; // empty body, e.g. calc()

        // Greedily pair parentheses within the body in one linear pass; an unmatched open or close fails the body.
        int[] match = new int[s.length()];
        Arrays.fill(match, -1);
        int[] parenStack = new int[b - a + 1];
        int parenDepth = 0;
        for (int p = a; p < b; p++) {
            char c = s.charAt(p);
            if (c == '(') {
                parenStack[parenDepth++] = p;
            } else if (c == ')') {
                if (parenDepth == 0) return false; // unmatched close
                int open = parenStack[--parenDepth];
                match[open] = p;
                match[p] = open;
            }
        }
        if (parenDepth != 0) return false; // unmatched open

        // Frames are the nested calc() bodies (the recursive calls in a direct transcription). A plain parenthesized
        // group is not a frame: it is counted by the frame's own depth, so a group closing behaves exactly like the
        // operand that ended it. Frame 0 is the outer body.
        int maxFrames = parenStack.length + 1;
        int[] frameEnd = new int[maxFrames];
        int[] frameDepth = new int[maxFrames];
        boolean[] framePrevOperand = new boolean[maxFrames];
        int frames = 1;
        frameEnd[0] = b;

        int i = a;
        while (true) {
            int end = frameEnd[frames - 1];
            while (i < end && StringUtil.isWhitespace(s.charAt(i))) i++;
            if (i == end) {
                // reached the close of this calc() body (or the end of the outer body): it must hold a complete
                // expression, every plain group closed
                if (frameDepth[frames - 1] != 0 || !framePrevOperand[frames - 1]) return false;
                frames--;
                if (frames == 0) return true;
                i++; // step past the nested calc's ')' into the parent body
                // the character directly following a nested calc() group must be whitespace, an operator, or a
                // closing parenthesis of the parent body
                int parentEnd = frameEnd[frames - 1];
                if (i < parentEnd) {
                    char next = s.charAt(i);
                    if (!StringUtil.isWhitespace(next) && next != '+' && next != '-' && next != '*'
                        && next != '/' && next != ')') return false;
                }
                framePrevOperand[frames - 1] = true; // a completed calc() group is itself an operand
                continue;
            }
            char c = s.charAt(i);
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                if (!framePrevOperand[frames - 1]) return false; // missing left operand, or a doubled operator
                framePrevOperand[frames - 1] = false;
                i++;
            } else if (c == '(') {
                if (framePrevOperand[frames - 1]) return false; // an operand must be joined to a group by an operator
                frameDepth[frames - 1]++;
                i++;
            } else if (c == ')') {
                int depth = frameDepth[frames - 1] - 1;
                if (depth < 0 || !framePrevOperand[frames - 1]) return false; // an empty or unbalanced group
                frameDepth[frames - 1] = depth;
                i++;
            } else if (isAsciiLetter(c)) {
                // the only word legal at an operand position is a nested calc(...), validated over the same grammar;
                // any other word (url, expression, attr, an unknown unit spelling, ...) is rejected
                if (i + 5 > end || !s.regionMatches(true, i, "calc(", 0, 5)) return false;
                int groupOpen = i + 4;
                int groupClose = match[groupOpen];
                if (groupClose < 0 || groupClose >= end) return false; // unclosed, or closes outside this body
                frameEnd[frames] = groupClose;
                frameDepth[frames] = 0;
                framePrevOperand[frames] = false;
                frames++;
                i = groupOpen + 1;
            } else {
                // a scalar operand: a non-negative number with an optional recognized unit
                int numEnd = readNumber(s, i, end);
                if (numEnd == i) return false;
                int afterUnit = readUnit(s, numEnd, end);
                i = (afterUnit != numEnd) ? afterUnit : numEnd; // a unit followed the number, or a bare number
                // the operand must end at whitespace, an operator, a closing parenthesis, or the end of the body;
                // a bare word glued on after a bare number is rejected
                if (i < end) {
                    char next = s.charAt(i);
                    if (!StringUtil.isWhitespace(next) && next != '+' && next != '-' && next != '*'
                        && next != '/' && next != ')') return false;
                }
                framePrevOperand[frames - 1] = true;
            }
        }
    }

    /** Read a non-negative decimal number ({@code 0}, {@code 12}, {@code 1.5}, {@code .5}); no sign or exponent. */
    private static int readNumber(String s, int start, int limit) {
        int i = start;
        int digits = 0;
        while (i < limit && isAsciiDigit(s.charAt(i))) { digits++; i++; }
        if (i < limit && s.charAt(i) == '.') {
            i++;
            int frac = 0;
            while (i < limit && isAsciiDigit(s.charAt(i))) { frac++; i++; }
            if (frac == 0) return start; // a trailing or fraction-less dot is not a number
        } else if (digits == 0) {
            return start;
        }
        return i;
    }

    /** Read a recognized length unit starting at {@code start}; return the index just past it, or {@code start}. */
    private static int readUnit(String s, int start, int limit) {
        if (start < limit && s.charAt(start) == '%') return start + 1;
        String[] units = {"vmin", "vmax", "rem", "vw", "vh", "em", "px", "ch", "ex"};
        for (String unit : units) {
            int end = start + unit.length();
            if (end <= limit && s.regionMatches(true, start, unit, 0, unit.length())) return end;
        }
        return start;
    }

    /** A plain scalar length: a non-negative decimal number immediately followed by a recognized unit. */
    private static boolean isScalarLength(String s) {
        int numEnd = readNumber(s, 0, s.length());
        if (numEnd == 0) return false;
        int unitEnd = readUnit(s, numEnd, s.length());
        return unitEnd != numEnd && unitEnd == s.length();
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

    private static String join(Set<String> entries) {
        StringBuilder sb = StringUtil.borrowBuilder();
        boolean first = true;
        for (String entry : entries) {
            if (!first) sb.append(", ");
            sb.append(entry);
            first = false;
        }
        return StringUtil.releaseBuilder(sb);
    }
}
