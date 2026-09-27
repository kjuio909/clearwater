package org.jsoup.safety;

import org.jsoup.internal.Normalizer;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 Validates and rewrites {@code srcset} attributes, one image candidate at a time.
 <p>
 The attribute value is split into candidates following the HTML {@code srcset} parsing rules: a candidate consists of a
 URL followed by an optional width ({@code w}) or pixel density ({@code x}) descriptor. Commas only separate
 candidates at a candidate boundary, so commas nested inside a {@code data:} URL, a quoted value, or entity-decoded
 content are preserved. A candidate address may be wrapped in matching {@code "} or {@code '} quotes; the commas and
 whitespace inside are literal and the quotes never become part of the address. Each surviving URL is checked
 against the safelist with the same protocol and encoding rules used for ordinary URI attributes such as {@code src};
 invalid descriptors cause their single candidate to be dropped, without affecting the other candidates.
 </p>
 */
final class SrcsetCleaner {
    private SrcsetCleaner() {}

    private static final int InDescriptor = 1;
    private static final int InParens = 2;
    private static final int AfterDescriptor = 3;

    /**
     Determine whether the supplied attribute is a {@code srcset} attribute that should be handled candidate by
     candidate, rather than treated as an ordinary opaque attribute.
     @param attr the attribute to test
     @return true if it is a {@code srcset} attribute
     */
    static boolean isSrcset(Attribute attr) {
        return "srcset".equalsIgnoreCase(attr.getKey());
    }

    /**
     Clean a {@code srcset} attribute value into a value containing only the acceptable candidates, in their original
     order. The input element is not modified; callers must apply the result themselves.
     @param el the element carrying the attribute (used for URL resolution against its base URI)
     @param rawValue the original attribute value
     @param safelist the safelist to validate each candidate URL against
     @return the cleaning result; {@link Result#removed} is true (with a null value) when no candidate survived, in
     which case the attribute must be removed rather than left empty
     */
    static Result clean(Element el, String rawValue, Safelist safelist) {
        List<Candidate> accepted = new ArrayList<>();
        int dropped = 0;
        if (rawValue != null && !rawValue.isEmpty()) {
            int pos = 0;
            int len = rawValue.length();

            while (pos < len) {
                // 1. Splitting loop: skip a run of ASCII whitespace and candidate-separating commas. Empty items,
                //    consecutive commas, and trailing separators are consumed here and never raise an error.
                while (pos < len && isWsOrComma(rawValue.charAt(pos))) pos++;
                if (pos >= len) break;

                // 2. Collect the URL and whatever descriptor tokens follow it. A URL run may be wrapped in matching
                //    quotes, in which case its commas and whitespace are literal address content.
                UrlRun run = collectUrl(rawValue, pos);
                pos = run.nextPos;
                if (!run.valid) { // malformed quoted run: drop this item, keep parsing any later candidates
                    dropped++;
                    continue;
                }
                String url = run.url;

                if (url.isEmpty()) continue;

                String descriptor = parseDescriptors(run.descriptors);
                String safeUrl = (descriptor != null) ? isSafeUrl(el, url, safelist) : null;
                if (safeUrl != null)
                    accepted.add(new Candidate(safeUrl, descriptor, run.quoted, run.quoteChar));
                else
                    dropped++;
            }
        }

        if (accepted.isEmpty()) return new Result(null, dropped, true);
        return new Result(serialize(accepted), dropped, false);
    }

    /** The outcome of cleaning a srcset attribute: the rewritten {@code value} (or null) and statistics. */
    static final class Result {
        final String value;
        final int droppedCandidates;
        final boolean removed;

        private Result(String value, int droppedCandidates, boolean removed) {
            this.value = value;
            this.droppedCandidates = droppedCandidates;
            this.removed = removed;
        }
    }

    private static final class TokenizerResult {
        final List<String> tokens;
        final int nextPos; // position just past the candidate-separating comma, or at end of input

        private TokenizerResult(List<String> tokens, int nextPos) {
            this.tokens = tokens;
            this.nextPos = nextPos;
        }
    }

    /** One collected candidate: the decoded URL, its descriptor tokens, and where parsing should continue. */
    private static final class UrlRun {
        final String url;
        final List<String> descriptors;
        final int nextPos;
        final boolean valid; // false for a malformed quoted run (junk after the quote, or an unterminated quote)
        final boolean quoted;
        final char quoteChar; // the delimiter when {@code quoted}, otherwise undefined

        private UrlRun(String url, List<String> descriptors, int nextPos, boolean valid, boolean quoted, char quoteChar) {
            this.url = url;
            this.descriptors = descriptors;
            this.nextPos = nextPos;
            this.valid = valid;
            this.quoted = quoted;
            this.quoteChar = quoteChar;
        }
    }

    /**
     Collect one candidate starting at {@code start}: its URL run and the descriptor tokens that follow it.
     <p>
     A run whose first character is a matching {@code "} or {@code '} is quoted: the address runs to the closing
     delimiter, so embedded commas, whitespace, and decoded character references are literal address content rather
     than candidate boundaries. The delimiter itself never becomes part of the address. The closing quote may be
     followed by whitespace introducing descriptors, a boundary comma, or end of input; any other character glued
     directly to the quote invalidates the candidate. An unterminated quote drops that item, and parsing resyncs at
     the next candidate comma so any following valid candidate is still considered.
     </p>
     */
    private static UrlRun collectUrl(String input, int start) {
        int len = input.length();
        char first = input.charAt(start);

        if (first == '"' || first == '\'') {
            char quote = first;
            int contentStart = start + 1;
            int p = contentStart;
            while (p < len && input.charAt(p) != quote) p++;
            if (p >= len) {
                // Unterminated quote: the malformed item is dropped, but a later comma still resyncs parsing so a
                // following valid candidate is not swallowed. Without a comma there is nothing more to recover.
                int comma = input.indexOf(',', contentStart);
                int nextPos = comma >= 0 ? comma + 1 : len;
                return new UrlRun(input.substring(contentStart), Collections.emptyList(), nextPos, false, true, quote);
            }
            String url = input.substring(contentStart, p);
            p++; // the closing quote

            int q = p;
            boolean separated = false;
            while (q < len && StringUtil.isWhitespace(input.charAt(q))) { q++; separated = true; }

            if (q < len && input.charAt(q) == ',') {
                return new UrlRun(url, Collections.emptyList(), q + 1, true, true, quote);
            }
            if (q == len) {
                return new UrlRun(url, Collections.emptyList(), q, true, true, quote);
            }
            // Descriptor tokens begin here; they are legal only after whitespace separating them from the quote.
            // Tokenize regardless so that the boundary comma (if any) is consumed and later candidates survive.
            TokenizerResult tokenized = tokenizeDescriptors(input, q);
            return new UrlRun(url, tokenized.tokens, tokenized.nextPos, separated, true, quote);
        }

        // Unquoted URL: a run of non-whitespace characters.
        int urlStart = start;
        int pos = start;
        while (pos < len && !StringUtil.isWhitespace(input.charAt(pos))) pos++;
        int urlRunEnd = pos;
        int urlEnd = urlRunEnd;
        while (urlEnd > urlStart && input.charAt(urlEnd - 1) == ',') urlEnd--;
        String url = input.substring(urlStart, urlEnd);

        if (urlEnd < urlRunEnd) {
            // The URL run ended in comma(s) with no whitespace: the boundary has no descriptor and parsing restarts
            // at the splitting loop. This is what keeps a comma inside a data: URL (e.g. "data:,Hello") in the URL.
            return new UrlRun(url, Collections.emptyList(), urlRunEnd, true, false, (char) 0);
        }
        // Tokenize the descriptors with the HTML srcset state machine, so that a comma nested in a parenthesized
        // (or quoted) descriptor does not get mistaken for a candidate boundary.
        TokenizerResult tokenized = tokenizeDescriptors(input, pos);
        return new UrlRun(url, tokenized.tokens, tokenized.nextPos, true, false, (char) 0);
    }

    private static boolean isWsOrComma(char c) {
        return c == ',' || StringUtil.isWhitespace(c);
    }

    /**
     Run the HTML srcset descriptor tokenizer starting at the first character after the URL's whitespace run.
     Parenthesized content (and anything quoted within it) is taken verbatim, so commas and whitespace inside it
     cannot start a new candidate. Stops at a boundary comma (consumed) or end of input.
     */
    private static TokenizerResult tokenizeDescriptors(String input, int pos) {
        List<String> tokens = new ArrayList<>();
        int len = input.length();
        int state = InDescriptor;
        StringBuilder token = new StringBuilder();

        while (pos < len) {
            char c = input.charAt(pos);
            switch (state) {
                case InDescriptor:
                    if (StringUtil.isWhitespace(c)) {
                        if (token.length() > 0) { tokens.add(token.toString()); token.setLength(0); }
                        state = AfterDescriptor;
                    } else if (c == ',') {
                        if (token.length() > 0) { tokens.add(token.toString()); token.setLength(0); }
                        return new TokenizerResult(tokens, pos + 1); // consume the candidate separator
                    } else if (c == '(') {
                        token.append(c);
                        state = InParens;
                    } else {
                        token.append(c);
                    }
                    pos++;
                    break;
                case InParens:
                    token.append(c); // commas, whitespace, and quotes in here are all literal
                    if (c == ')') state = InDescriptor;
                    pos++;
                    break;
                case AfterDescriptor:
                    if (StringUtil.isWhitespace(c)) {
                        pos++;
                    } else if (c == ',') {
                        return new TokenizerResult(tokens, pos + 1);
                    } else {
                        state = InDescriptor; // reprocess this character as a new token
                    }
                    break;
            }
        }
        if (token.length() > 0) tokens.add(token.toString());
        return new TokenizerResult(tokens, pos);
    }

    /**
     Validate the tokenized descriptor list of one candidate. Legal forms are: no descriptor, a single non-zero
     width ({@code <integer>w}), or a single finite strictly-positive pixel density ({@code <number>x}). Duplicated,
     unknown, parenthesized, or syntactically invalid descriptors invalidate the candidate.
     @return the normalized descriptor suffix ({@code ""} when absent, or {@code " <descriptor>"}), or {@code null}
     */
    private static String parseDescriptors(List<String> tokens) {
        if (tokens.isEmpty()) return "";
        if (tokens.size() > 1) return null;

        String descriptor = tokens.get(0);
        if (descriptor.isEmpty()) return "";
        char suffix = descriptor.charAt(descriptor.length() - 1);
        if (suffix != 'w' && suffix != 'x') return null; // only lower-case width/density descriptors are valid
        String number = descriptor.substring(0, descriptor.length() - 1);
        if (number.isEmpty()) return null;

        if (suffix == 'w') {
            if (parseWidth(number) == null) return null;
        } else {
            if (parseDensity(number) == null) return null;
        }
        return " " + descriptor;
    }

    /** Parse a non-negative integer with no sign, exponent, or decimal point; zero and overflow are invalid. */
    private static Long parseWidth(String s) {
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) return null; // overflow
            value = value * 10 + digit;
        }
        return value == 0L ? null : value;
    }

    /** Parse a finite strictly-positive floating-point number matching the HTML valid floating-point number grammar. */
    private static Double parseDensity(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            if (s.charAt(i) == '-') return null;
            i++;
        }
        int intDigits = 0;
        while (i < s.length() && isAsciiDigit(s.charAt(i))) { intDigits++; i++; }
        int fracDigits = 0;
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            while (i < s.length() && isAsciiDigit(s.charAt(i))) { fracDigits++; i++; }
        }
        // grammar: digits before the optional dot, or digits after it (".5"); a lone "." is invalid
        if (intDigits == 0 && fracDigits == 0) return null;
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int exponentDigits = 0;
            while (i < s.length() && isAsciiDigit(s.charAt(i))) { exponentDigits++; i++; }
            if (exponentDigits == 0) return null;
        }
        if (i != s.length()) return null; // trailing junk
        try {
            double value = Double.parseDouble(s);
            // the descriptor must be a strictly positive finite number: zero, negatives, NaN, and infinity are invalid
            if (!(value > 0d) || Double.isNaN(value) || Double.isInfinite(value)) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     Check one candidate URL against the safelist's protocol policy and return the URL spelling to emit.
     <p>
     Character references have already been decoded by the parser, so a URL that still contains an ASCII control
     character or newline is rejected outright: such characters cannot occur in a conforming address and are used to
     smuggle disguised {@code javascript:} or {@code data:} schemes past a prefix test. Other URLs are resolved and
     checked exactly like an ordinary URI attribute such as {@code src}. When relative links are not preserved,
     resolvable candidates are emitted in absolute form.
     </p>
     @return the URL to keep (absolute or original spelling), or {@code null} if it must be dropped
     */
    private static String isSafeUrl(Element el, String url, Safelist safelist) {
        if (containsControlChar(url)) return null; // controls, tabs, and newlines can never be part of a safe address

        Set<Safelist.Protocol> protocols = safelist.srcsetProtocols(el.normalName());
        if (protocols.isEmpty()) return url; // no protocol policy: accept, like an ordinary untyped attribute

        String resolved = StringUtil.resolve(el.baseUri(), url);
        String check = resolved;
        if (check.isEmpty() && !StringUtil.hasHttpScheme(url)) check = url; // custom schemes checked as written
        if (check.isEmpty()) return null; // an unresolvable relative URL with no base is not acceptable

        String lc = Normalizer.lowerCase(check);
        for (Safelist.Protocol protocol : protocols) {
            String prot = protocol.toString();
            if (prot.equals("#")) continue; // fragment anchors are not image candidates
            if (lc.startsWith(prot)
                && lc.length() > prot.length()
                && lc.charAt(prot.length()) == ':') {
                // Emit the absolute form like an ordinary src value, unless relative links are preserved.
                return (!safelist.preserveRelativeLinks() && !resolved.isEmpty()) ? resolved : url;
            }
        }
        return null;
    }

    private static boolean containsControlChar(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }

    private static String serialize(List<Candidate> candidates) {
        StringBuilder sb = StringUtil.borrowBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            Candidate candidate = candidates.get(i);
            // A quoted address is re-wrapped in its original delimiter so the serialized value parses back into the
            // same single candidate (its inner commas/whitespace stay literal). Unquoted runs never contain
            // whitespace, so they need no wrapping.
            if (candidate.quoted) sb.append(candidate.quoteChar);
            sb.append(candidate.url);
            if (candidate.quoted) sb.append(candidate.quoteChar);
            sb.append(candidate.descriptor);
        }
        return StringUtil.releaseBuilder(sb);
    }

    private static final class Candidate {
        final String url;
        final String descriptor; // normalized as "" or " <descriptor>"
        final boolean quoted;
        final char quoteChar;

        Candidate(String url, String descriptor, boolean quoted, char quoteChar) {
            this.url = url;
            this.descriptor = descriptor;
            this.quoted = quoted;
            this.quoteChar = quoteChar;
        }
    }
}
