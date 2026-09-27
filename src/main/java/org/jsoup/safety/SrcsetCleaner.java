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
 content are preserved. Each surviving URL is checked against the safelist with the same protocol and encoding rules
 used for ordinary URI attributes such as {@code src}; invalid descriptors cause their single candidate to be dropped,
 without affecting the other candidates.
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

                // 2. Collect the URL. An address may be quoted ('...' or "..."): the quotes only delimit the
                //    address and are not part of it, so commas, whitespace, and decoded entities inside them
                //    cannot be mistaken for candidate boundaries. An unquoted URL is a run of non-whitespace
                //    characters.
                String url;
                List<String> descriptors;
                char first = rawValue.charAt(pos);
                if (first == '"' || first == '\'') {
                    int close = rawValue.indexOf(first, pos + 1);
                    if (close == -1) { // unterminated quote: the rest of the value is the address, no descriptors
                        url = rawValue.substring(pos + 1);
                        pos = len;
                        descriptors = Collections.emptyList();
                    } else {
                        url = rawValue.substring(pos + 1, close);
                        pos = close + 1;
                        // 3. Tokenize the descriptors (see below)
                        TokenizerResult tokenized = tokenizeDescriptors(rawValue, pos);
                        descriptors = tokenized.tokens;
                        pos = tokenized.nextPos;
                    }
                } else {
                    int urlStart = pos;
                    while (pos < len && !StringUtil.isWhitespace(rawValue.charAt(pos))) pos++;
                    int urlRunEnd = pos;
                    int urlEnd = urlRunEnd;
                    while (urlEnd > urlStart && rawValue.charAt(urlEnd - 1) == ',') urlEnd--;
                    url = rawValue.substring(urlStart, urlEnd);

                    if (urlEnd < urlRunEnd) {
                        // The URL run ended in comma(s) with no whitespace: the boundary has no descriptor and
                        // parsing restarts at the splitting loop. This is what keeps a comma inside a data: URL
                        // (e.g. "data:,Hello") part of the URL.
                        descriptors = Collections.emptyList();
                        pos = urlRunEnd;
                    } else {
                        // 3. Tokenize the descriptors with the HTML srcset state machine, so that a comma nested
                        //    in a parenthesized descriptor does not get mistaken for a candidate boundary.
                        TokenizerResult tokenized = tokenizeDescriptors(rawValue, pos);
                        descriptors = tokenized.tokens;
                        pos = tokenized.nextPos;
                    }
                }

                if (url.isEmpty()) continue;

                String descriptor = parseDescriptors(descriptors);
                String safeUrl = (descriptor != null) ? isSafeUrl(el, url, safelist) : null;
                if (safeUrl != null && isSerializable(safeUrl))
                    accepted.add(new Candidate(safeUrl, descriptor));
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
     width ({@code <integer>w}), or a single finite positive pixel density ({@code <number>x}). Duplicated,
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

    /** Parse a finite positive floating-point number matching the HTML valid floating-point number grammar. */
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
            // only positive densities are meaningful; zero, negative, and non-finite values invalidate the candidate
            if (value <= 0d || Double.isNaN(value) || Double.isInfinite(value)) return null;
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
     This mirrors the ordinary URI attribute path in {@link Safelist}: the URL is resolved against the element's base
     URI (which strips and percent-normalizes control characters the same way {@code src} does), and the resolved or
     as-written value must begin with an allowed scheme. When relative links are not preserved, resolvable candidates
     are emitted in absolute form, exactly like an ordinary {@code src} value.
     </p>
     @return the URL to keep (absolute or original spelling), or {@code null} if it must be dropped
     */
    private static String isSafeUrl(Element el, String url, Safelist safelist) {
        Set<Safelist.Protocol> protocols = safelist.srcsetProtocols(el.normalName());
        if (protocols.isEmpty()) return stripControlChars(url); // no protocol policy: accept, like an ordinary untyped attribute

        String resolved = StringUtil.resolve(el.baseUri(), url);
        String check = resolved;
        if (check.isEmpty() && !StringUtil.hasHttpScheme(url)) check = url; // custom schemes checked as written
        check = stripControlChars(check);
        if (check.isEmpty()) return null; // an unresolvable relative URL with no base is not acceptable

        String lc = Normalizer.lowerCase(check);
        for (Safelist.Protocol protocol : protocols) {
            String prot = protocol.toString();
            if (prot.equals("#")) continue; // fragment anchors are not image candidates
            if (lc.startsWith(prot)
                && lc.length() > prot.length()
                && lc.charAt(prot.length()) == ':') {
                // Emit the absolute form like an ordinary src value, unless relative links are preserved; in either
                // case strip ASCII control characters, which browsers remove from URLs and would destabilize reparse.
                String emit = (!safelist.preserveRelativeLinks() && !resolved.isEmpty()) ? resolved : url;
                return stripControlChars(emit);
            }
        }
        return null;
    }

    private static String stripControlChars(String url) {
        StringBuilder sb = null;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x1f) {
                if (sb == null) {
                    sb = new StringBuilder(url.length());
                    sb.append(url, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? url : sb.toString();
    }

    private static String serialize(List<Candidate> candidates) {
        StringBuilder sb = StringUtil.borrowBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            Candidate candidate = candidates.get(i);
            appendUrl(sb, candidate.url);
            sb.append(candidate.descriptor);
        }
        return StringUtil.releaseBuilder(sb);
    }

    /**
     Append one URL, re-quoting it when its bare spelling would not reparse into the same candidate: that is the
     case when it contains whitespace, ends in a comma (which the parser would strip as a separator), or starts
     with a quote character. {@link #isSerializable} guarantees a usable quote character is available.
     */
    private static void appendUrl(StringBuilder sb, String url) {
        if (!needsQuotes(url)) {
            sb.append(url);
            return;
        }
        char quote = url.indexOf('"') == -1 ? '"' : '\'';
        sb.append(quote).append(url).append(quote);
    }

    private static boolean needsQuotes(String url) {
        if (url.isEmpty()) return false;
        char first = url.charAt(0);
        if (first == '"' || first == '\'' || url.charAt(url.length() - 1) == ',') return true;
        for (int i = 0; i < url.length(); i++)
            if (StringUtil.isWhitespace(url.charAt(i))) return true;
        return false;
    }

    /** A URL that needs quotes but contains both quote characters cannot be serialized reparseably. */
    private static boolean isSerializable(String url) {
        return !needsQuotes(url) || url.indexOf('"') == -1 || url.indexOf('\'') == -1;
    }

    private static final class Candidate {
        final String url;
        final String descriptor; // normalized as "" or " <descriptor>"

        Candidate(String url, String descriptor) {
            this.url = url;
            this.descriptor = descriptor;
        }
    }
}
