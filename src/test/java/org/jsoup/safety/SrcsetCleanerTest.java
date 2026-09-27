package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for per-candidate cleaning of {@code srcset} attributes in the {@link Cleaner}.
 */
public class SrcsetCleanerTest {

    private static Safelist safelist() {
        return Safelist.relaxed().addAttributes("img", "srcset");
    }

    /** The public entry point for responsive-image cleaning: picture/source tags plus srcset on img and source. */
    private static Safelist pictureSafelist() {
        return Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset", "media", "type");
    }

    @Test void srcsetRemovedWhenNotAllowed() {
        // without the attribute in the safelist, the existing attribute-removal behavior is unchanged
        String html = "<img src=\"http://example.com/src.jpg\" srcset=\"http://example.com/a.jpg 1x\">";
        String clean = Jsoup.clean(html, Safelist.relaxed());
        assertEquals("<img src=\"http://example.com/src.jpg\">", clean);
    }

    @Test void retainsCandidatesAndDescriptors() {
        String html = "<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x, http://example.com/c.jpg 300w\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x, http://example.com/c.jpg 300w\">", clean);
    }

    @Test void retainsCandidateWithoutDescriptor() {
        String clean = Jsoup.clean("<img srcset=\"http://example.com/a.jpg, http://example.com/b.jpg 2x\">", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void normalizesWhitespaceAroundDescriptors() {
        String clean = Jsoup.clean("<img srcset=\"  http://example.com/a.jpg     1x  , http://example.com/b.jpg  2x \">", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void resolvesRelativeCandidatesAgainstBaseUri() {
        String html = "<img srcset=\"/a.jpg 1x, /b.jpg 2x\">";
        String clean = Jsoup.clean(html, "http://example.com/path/", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void preservesRelativeCandidatesWhenConfigured() {
        Safelist safelist = safelist().preserveRelativeLinks(true);
        String clean = Jsoup.clean("<img srcset=\"/a.jpg 1x, http://example.com/b.jpg 2x\">", "http://example.com/", safelist);
        assertEquals("<img srcset=\"/a.jpg 1x, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void keepsRootRelativeCandidatesVerbatimWithoutBase() {
        // with no document base, a scheme-less root-relative reference is unambiguous and must survive as written
        // (no fabricated/concatenated path), while path-relative and scheme-relative references cannot resolve
        assertEquals("<img srcset=\"/a.jpg 1x\">", Jsoup.clean("<img srcset=\"/a.jpg 1x\">", safelist()));
        assertEquals("<img srcset=\"/assets/a.png\">", Jsoup.clean("<img srcset=\"/assets/a.png\">", safelist()));
        // an unsafe candidate next to a root-relative one is dropped on its own
        assertEquals("<img srcset=\"/assets/a.png 1x\">",
            Jsoup.clean("<img srcset=\"/assets/a.png 1x, javascript:bad 2x\">", safelist()));
        // a path-relative reference is ambiguous without a base and is dropped
        assertEquals("<img>", Jsoup.clean("<img srcset=\"a.jpg 1x, ./a.jpg, ../b.jpg 2x\">", safelist()));
        // a scheme-relative //host reference has no scheme to inherit without a base and is dropped
        assertEquals("<img>", Jsoup.clean("<img srcset=\"//example.com/a.jpg 1x\">", safelist()));

        // identical result on a <source> and on a fallback <img>, verbatim
        Safelist sl = pictureSafelist();
        String value = "/assets/a.png 1x, javascript:bad 2x, /b,c.png 300w";
        String img = Jsoup.clean("<img srcset='" + value + "'>", sl);
        String source = Jsoup.clean("<source srcset='" + value + "'>", sl);
        assertEquals("<img srcset=\"/assets/a.png 1x, /b,c.png 300w\">", img);
        assertEquals("<source srcset=\"/assets/a.png 1x, /b,c.png 300w\">", source);

        // the verbatim root-relative value is stable across re-clean and serialize-reparse-clean
        Cleaner cleaner = new Cleaner(sl);
        Document once = cleaner.clean(Jsoup.parseBodyFragment("<img srcset='/assets/a.png 1x'>", ""));
        assertEquals("<img srcset=\"/assets/a.png 1x\">", once.body().html());
        assertEquals(once.body().html(), cleaner.clean(once).body().html());
        Document reparsed = Jsoup.parse(once.body().html());
        assertEquals(once.body().html(), cleaner.clean(reparsed).body().html());
    }

    @Test void dropsJavascriptCandidateAndKeepsRest() {
        String html = "<img srcset=\"javascript:alert(1) 1x, http://example.com/a.jpg 1x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", clean);
    }

    @Test void dropsDisguisedJavascriptCandidate() {
        // entity-decoded tabs/newlines/control characters must not smuggle a scheme through
        String html = "<img srcset=\"&#0013;ja&Tab;va&Tab;script&#0010;:alert(1) 1x, http://example.com/a.jpg 1x\">";
        String clean = Jsoup.clean(html, "https://", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", clean);

        String colon = "<img srcset=\"ja&Tab;va&Tab;script&colon;alert(1), http://example.com/a.jpg 1x\">";
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", Jsoup.clean(colon, "https://", safelist()));
    }

    @Test void schemeCheckIsCaseInsensitive() {
        String clean = Jsoup.clean("<img srcset=\"HTTP://example.com/a.jpg 1x\">", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", clean);
    }

    @Test void candidateWithBadDescriptorIsDroppedAlone() {
        String html = "<img srcset=\"http://example.com/a.jpg 100zz, "
            + "http://example.com/b.jpg 1x 2x, "
            + "http://example.com/c.jpg 0w, "
            + "http://example.com/d.jpg -5w, "
            + "http://example.com/e.jpg 1.5w, "
            + "http://example.com/f.jpg 100W, "
            + "http://example.com/g.jpg, "
            + "http://example.com/h.jpg (orientation: portrait), "
            + "http://example.com/i.jpg 2x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/g.jpg, http://example.com/i.jpg 2x\">", clean);
    }

    @Test void strictWidthDescriptors() {
        // only a single strictly positive decimal integer followed by a lower-case w is a width descriptor
        String good = "<img srcset=\"http://example.com/a.jpg 1w, http://example.com/b.jpg 300w, "
            + "http://example.com/c.jpg 007w, http://example.com/d.jpg 9223372036854775807w\">";
        assertEquals("<img srcset=\"http://example.com/a.jpg 1w, http://example.com/b.jpg 300w, "
            + "http://example.com/c.jpg 007w, http://example.com/d.jpg 9223372036854775807w\">",
            Jsoup.clean(good, safelist()));

        String bad = "<img srcset=\"http://example.com/z.jpg 0w, http://example.com/a.jpg +3w, "
            + "http://example.com/b.jpg -3w, http://example.com/c.jpg 1.5w, http://example.com/d.jpg .5w, "
            + "http://example.com/e.jpg 1e2w, http://example.com/f.jpg 100W, http://example.com/g.jpg w, "
            + "http://example.com/h.jpg 1ww, http://example.com/i.jpg 00w, "
            + "http://example.com/j.jpg 99999999999999999999999999w, http://example.com/ok.jpg 2x\">";
        assertEquals("<img srcset=\"http://example.com/ok.jpg 2x\">", Jsoup.clean(bad, safelist()));
    }

    @Test void duplicateOrMixedWidthDescriptorsAreDropped() {
        String bad = "<img srcset=\"http://example.com/a.jpg 100w 200w, "
            + "http://example.com/b.jpg 100w 100w, "
            + "http://example.com/c.jpg 100w 2x, "
            + "http://example.com/d.jpg 2x 100w, "
            + "http://example.com/ok.jpg 320w\">";
        assertEquals("<img srcset=\"http://example.com/ok.jpg 320w\">", Jsoup.clean(bad, safelist()));
    }

    @Test void allBadWidthDescriptorsRemoveAttribute() {
        String bad = "<img srcset=\"http://example.com/a.jpg 0w, http://example.com/b.jpg -1w\">";
        assertEquals("<img>", Jsoup.clean(bad, "http://example.com/", safelist()));
    }

    @Test void cleanedSrcsetReparsesToSameCandidates() {
        String html = "<img srcset=' , \"http://example.com/a,b.jpg\" 1x, , http://example.com/b.jpg  300w ,'>";
        String clean = Jsoup.clean(html, "http://example.com/", safelist());
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x, http://example.com/b.jpg 300w\">", clean);
        // parsing the cleaned value yields the same two candidates, descriptors, and order
        Element img = Jsoup.parse(clean).expectFirst("img");
        assertEquals("http://example.com/a,b.jpg 1x, http://example.com/b.jpg 300w", img.attr("srcset"));
    }

    @Test void illegalDensityValuesAreDropped() {
        String html = "<img srcset=\"http://example.com/a.jpg -1x, http://example.com/b.jpg x, "
            + "http://example.com/c.jpg .x, http://example.com/d.jpg 1ex, http://example.com/e.jpg 2x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/e.jpg 2x\">", clean);
    }

    @Test void validDensityGrammarAccepted() {
        String html = "<img srcset=\"http://example.com/a.jpg 0.5x, http://example.com/b.jpg +1x, "
            + "http://example.com/c.jpg 1e2x, http://example.com/d.jpg .25x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 0.5x, http://example.com/b.jpg +1x, "
            + "http://example.com/c.jpg 1e2x, http://example.com/d.jpg .25x\">", clean);
    }

    @Test void emptyAndConsecutiveAndTrailingSeparatorsDoNotThrowOrSwallow() {
        String html = "<img srcset=\",, http://example.com/a.jpg 1x,,, http://example.com/b.jpg 2x,\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void emptyValueRemovesAttribute() {
        assertEquals("<img>", Jsoup.clean("<img srcset=\"\">", safelist()));
        assertEquals("<img>", Jsoup.clean("<img srcset=\"   \">", safelist()));
        assertEquals("<img>", Jsoup.clean("<img srcset=\",,\">", safelist()));
    }

    @Test void quotedUrlTreatsCommaAndWhitespaceAsLiteral() {
        // double-quoted address: the embedded comma belongs to the address while parsing, and the delimiting
        // quotes are syntax only and never appear in the cleaned output
        String html = "<img srcset='\"http://example.com/a,b.jpg\" 1x, http://example.com/c.jpg 2x'>";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x, http://example.com/c.jpg 2x\">", clean);
        assertFalse(clean.contains("&quot;"), "delimiting quote leaked into output: " + clean);

        // single quotes delimit the address the same way; the inner space is literal while parsing, but a bare
        // address containing that space could not reparse as one candidate, so it is dropped alone
        String single = "<img srcset=\"'http://example.com/a b.jpg' 1x, http://example.com/c.jpg 2x\">";
        String cleanSingle = Jsoup.clean(single, safelist());
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", cleanSingle);

        // quoted address with no descriptor still protects the inner comma, and still emits no quotes
        String noDesc = "<img srcset='\"http://example.com/a,b.jpg\", http://example.com/c.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/a,b.jpg, http://example.com/c.jpg 2x\">",
            Jsoup.clean(noDesc, safelist()));

        // inner commas/parentheses are literal for quoted relative addresses too
        String relative = "<img srcset='\"/a,b.jpg\" 1x, /c.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x, http://example.com/c.jpg 2x\">",
            Jsoup.clean(relative, "http://example.com/path/", safelist()));
    }

    @Test void quotedUrlIsRecheckedAfterReparse() {
        // serialized quotes must parse back to the same single address and remain protocol-checked
        String html = "<img srcset='\"javascript:alert(1)\", http://example.com/c.jpg 2x'>";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", clean);
        assertEquals(clean, Jsoup.clean(clean, safelist()));
    }

    @Test void malformedQuotedUrlIsDroppedAlone() {
        // a descriptor glued directly to the closing quote invalidates only that candidate
        String glued = "<img srcset='\"http://example.com/a.jpg\"1x, http://example.com/c.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", Jsoup.clean(glued, safelist()));

        // an unterminated quote does not swallow a later valid candidate
        String unterminated = "<img srcset='\"http://example.com/a.jpg 1x, http://example.com/c.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", Jsoup.clean(unterminated, safelist()));

        // an empty quoted address is no candidate at all
        String empty = "<img srcset='\"\" 1x, http://example.com/c.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", Jsoup.clean(empty, safelist()));
    }

    @Test void zeroAndNonFiniteDensitiesAreDropped() {
        String html = "<img srcset=\"http://example.com/a.jpg 0x, http://example.com/b.jpg +0x, "
            + "http://example.com/c.jpg 0.0x, http://example.com/d.jpg 1e999x, http://example.com/e.jpg 2x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/e.jpg 2x\">", clean);
    }

    @Test void controlCharCandidatesAreRejectedNotStripped() {
        // an entity-decoded control character in the address must drop the candidate rather than smuggle a scheme
        String html = "<img srcset=\"java&#x09;script:alert(1), http://example.com/ok.jpg 1x\">";
        String clean = Jsoup.clean(html, "https://", safelist());
        assertEquals("<img srcset=\"http://example.com/ok.jpg 1x\">", clean);

        // a raw control char inside an otherwise-good URL invalidates that candidate only
        String raw = "<img srcset=\"http://example.com/a&#x0a;.jpg 1x, http://example.com/ok.jpg 2x\">";
        assertEquals("<img srcset=\"http://example.com/ok.jpg 2x\">", Jsoup.clean(raw, "https://", safelist()));
    }

    @Test void quotedCandidateOutputIsIdempotent() {
        String html = "<img srcset='\"http://example.com/a,b.jpg\" 1x, \"http://example.com/c,d.jpg\" 2x'>";
        Cleaner cleaner = new Cleaner(safelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(html, "http://example.com/"));
        Document twice = cleaner.clean(once);
        assertEquals(once.body().html(), twice.body().html());
        // both addresses survive, bare (no delimiting quotes), each still carrying its single descriptor
        String srcset = once.expectFirst("img").attr("srcset");
        assertEquals("http://example.com/a,b.jpg 1x, http://example.com/c,d.jpg 2x", srcset);
        assertFalse(srcset.contains("\"") && srcset.contains("'"), "delimiting quote leaked into output: " + srcset);
        assertEquals(2, srcset.split("\\s[12]x", -1).length - 1);
    }

    @Test void quotedAddressWithWhitespaceIsDroppedAlone() {
        // a quoted address whose decoded value contains HTML whitespace cannot be written bare and still reparse as
        // one candidate, so it is dropped rather than re-quoted; the other candidates are untouched
        String html = "<img srcset='\"http://example.com/a b.jpg\" 1x, http://example.com/c.jpg 2x'>";
        String clean = Jsoup.clean(html, "http://example.com/", safelist());
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", clean);

        // the same applies when relative links are preserved (no absolute rewrite can remove the space)
        Safelist preserving = safelist().preserveRelativeLinks(true);
        String rel = Jsoup.clean("<img srcset='\"/a b.jpg\" 1x, /c.jpg 2x'>", "http://example.com/", preserving);
        assertEquals("<img srcset=\"/c.jpg 2x\">", rel);
        assertEquals(rel, Jsoup.clean(rel, "http://example.com/", preserving));
    }

    @Test void commaInsideDataUrlIsNotASeparator() {
        Safelist safelist = safelist().addProtocols("img", "srcset", "http", "https", "data");
        // data: URLs with no descriptor: the comma that is part of the data must survive the boundary comma
        String html = "<img srcset=\"data:text/plain,Hello 1x, http://example.com/a.jpg 2x\">";
        String clean = Jsoup.clean(html, safelist);
        assertEquals("<img srcset=\"data:text/plain,Hello 1x, http://example.com/a.jpg 2x\">", clean);

        String noDescriptor = "<img srcset=\"data:,foo, http://example.com/a.jpg 2x\">";
        String clean2 = Jsoup.clean(noDescriptor, safelist);
        assertEquals("<img srcset=\"data:,foo, http://example.com/a.jpg 2x\">", clean2);
    }

    @Test void dataUrlDroppedUnlessProtocolAllowed() {
        String html = "<img srcset=\"data:image/gif;base64,R0lGOD 1x, http://example.com/a.jpg 1x\">";
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", Jsoup.clean(html, safelist()));

        Safelist withData = safelist().addProtocols("img", "srcset", "http", "https", "data");
        String clean = Jsoup.clean(html, withData);
        assertEquals("<img srcset=\"data:image/gif;base64,R0lGOD 1x, http://example.com/a.jpg 1x\">", clean);
    }

    @Test void explicitSrcsetProtocolsAreIndependentOfSrc() {
        Safelist safelist = Safelist.relaxed()
            .addAttributes("img", "srcset")
            .addProtocols("img", "srcset", "data");
        String html = "<img src=\"http://example.com/src.jpg\" srcset=\"data:text/plain,Hi 1x, http://example.com/a.jpg 1x\">";
        String clean = Jsoup.clean(html, safelist);
        // srcset only allows data:; src still uses its own http/https policy
        assertEquals("<img src=\"http://example.com/src.jpg\" srcset=\"data:text/plain,Hi 1x\">", clean);
    }

    @Test void fallsBackToSrcProtocolsThenAllTag() {
        Safelist safelist = new Safelist()
            .addTags("img")
            .addAttributes(":all", "srcset")
            .addProtocols(":all", "src", "http", "https");
        String clean = Jsoup.clean("<img srcset=\"http://example.com/a.jpg 1x, javascript:alert(1)\">", "http://example.com/", safelist);
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">", clean);
    }

    @Test void noProtocolPolicyAcceptsLikeOrdinaryUntypedAttribute() {
        Safelist safelist = new Safelist().addTags("img").addAttributes("img", "srcset");
        String clean = Jsoup.clean("<img srcset=\"javascript:alert(1) 1x, /a.jpg\">", safelist);
        assertEquals("<img srcset=\"javascript:alert(1) 1x, /a.jpg\">", clean);
    }

    @Test void worksOnAnyTag() {
        Safelist safelist = new Safelist()
            .addTags("source")
            .addAttributes("source", "srcset")
            .addProtocols("source", "src", "http", "https");
        String clean = Jsoup.clean("<source srcset=\"http://example.com/a.jpg 1x, javascript:x 2x\">", "http://example.com/", safelist);
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x\">", clean);
    }

    @Test void srcAndHrefAndOtherAttributesAreUnaffected() {
        String html = "<img src=\"/src.jpg\" alt=\"Hi\" title=\"T\" srcset=\"javascript:bad 1x, http://example.com/a.jpg 1x\">"
            + "<a href=\"/x\">x</a>";
        String clean = Jsoup.clean(html, "http://example.com/", safelist());
        assertEquals("<img src=\"http://example.com/src.jpg\" alt=\"Hi\" title=\"T\" srcset=\"http://example.com/a.jpg 1x\">"
            + "<a href=\"http://example.com/x\">x</a>", clean);
    }

    @Test void nodesAndDocumentOrderArePreserved() {
        String html = "<p>Before</p>"
            + "<img srcset=\"http://example.com/a.jpg 1x, javascript:bad 2x\">"
            + "<img srcset=\"http://example.com/b.jpg 1x\">"
            + "<p>After</p>";
        String clean = org.jsoup.TextUtil.stripNewlines(Jsoup.clean(html, "http://example.com/", safelist()));
        assertEquals("<p>Before</p>"
            + "<img srcset=\"http://example.com/a.jpg 1x\">"
            + "<img srcset=\"http://example.com/b.jpg 1x\">"
            + "<p>After</p>", clean);
    }

    @Test void oneBadSrcsetDoesNotAffectOtherElements() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img srcset=\"javascript:bad 1x\"><img srcset=\"http://example.com/ok.jpg 1x\">",
            "http://example.com/");
        Document clean = new Cleaner(safelist()).clean(dirty);
        assertEquals("<img><img srcset=\"http://example.com/ok.jpg 1x\">", clean.body().html());
    }

    @Test void inputDocumentIsNotModified() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img srcset=\"/a.jpg 1x, javascript:bad 2x\">", "http://example.com/");
        Element img = dirty.expectFirst("img");
        String original = img.attr("srcset");
        new Cleaner(safelist()).clean(dirty);
        assertEquals(original, img.attr("srcset"));
        assertEquals("/a.jpg 1x, javascript:bad 2x", img.attr("srcset"));
    }

    @Test void cleaningIsIdempotentAndReparseable() {
        String html = "<img srcset=\"  /a.jpg     1x ,  http://example.com/b.jpg  2x , javascript:bad 3x\">";
        Cleaner cleaner = new Cleaner(safelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(html, "http://example.com/"));
        Document twice = cleaner.clean(once);

        String expected = "<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">";
        assertEquals(expected, once.body().html());
        assertEquals(expected, twice.body().html());

        // the result must parse again into the same candidates and descriptors
        Document reparsed = Jsoup.parse(once.body().html());
        assertEquals(once.body().html(), cleaner.clean(reparsed).body().html());
        Element img = reparsed.expectFirst("img");
        assertEquals(2, img.attr("srcset").split(",").length);
        assertTrue(img.attr("srcset").contains("1x"));
        assertTrue(img.attr("srcset").contains("2x"));
    }

    @Test void repeatedCleaningOfSameInputIsStable() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">", "http://example.com/");
        Cleaner cleaner = new Cleaner(safelist());
        assertEquals(cleaner.clean(dirty).body().html(), cleaner.clean(dirty).body().html());
    }

    @Test void isValidReflectsDroppedCandidates() {
        Cleaner cleaner = new Cleaner(safelist());
        Document ok = Jsoup.parseBodyFragment("<img srcset=\"http://example.com/a.jpg 1x\">", "http://example.com/");
        Document bad = Jsoup.parseBodyFragment("<img srcset=\"http://example.com/a.jpg 1x, javascript:alert(1)\">", "http://example.com/");
        Document badDescriptor = Jsoup.parseBodyFragment("<img srcset=\"http://example.com/a.jpg nope\">", "http://example.com/");
        assertTrue(cleaner.isValid(ok));
        assertFalse(cleaner.isValid(bad));
        assertFalse(cleaner.isValid(badDescriptor));
    }

    @Test void entitiesInUrlsAreHandledAndReserialized() {
        String html = "<img srcset=\"http://example.com/a?b=1&amp;c=2 1x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a?b=1&amp;c=2 1x\">", clean);
    }

    @Test void commaInsideParenthesizedDescriptorDoesNotSwallowNextCandidate() {
        // the comma inside the (invalid, image-relevant) descriptor is not a candidate boundary; it is dropped alone
        String html = "<img srcset=\"http://example.com/a.jpg (min-width: 400px, foo: bar), http://example.com/b.jpg 1x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/b.jpg 1x\">", clean);
    }

    @Test void tabInsideSchemeIsStrippedBeforeProtocolCheck() {
        String html = "<img srcset=\"http://example.com/ok.jpg 1x, ja&Tab;vas&#x09;cript:alert(1) 2x\">";
        String clean = Jsoup.clean(html, "https://", safelist());
        assertEquals("<img srcset=\"http://example.com/ok.jpg 1x\">", clean);
    }

    @Test void leadingControlCharsDoNotBypassSchemeCheck() {
        String html = "<img srcset=\"&#01;&#10;javascript:alert(1), http://example.com/ok.jpg 1x\">";
        String clean = Jsoup.clean(html, "https://", safelist());
        assertEquals("<img srcset=\"http://example.com/ok.jpg 1x\">", clean);
    }

    @Test void badCandidateBeforeAndAfterGoodOneDoesNotCorruptOutput() {
        String html = "<img srcset=\"bad 0w, http://example.com/a.jpg 1x, javascript:x, http://example.com/b.jpg 2x, nope 1x 2x\">";
        String clean = Jsoup.clean(html, "http://example.com/", safelist());
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x\">", clean);

        // re-clean of the clean result is stable
        assertEquals(clean, Jsoup.clean(clean, "http://example.com/", safelist()));
    }

    @Test void descriptorSemanticsSurviveReparse() {
        Document doc = Jsoup.parse(Jsoup.clean(
            "<img srcset=\"http://example.com/a.jpg, http://example.com/b.jpg 2x, http://example.com/c.jpg 480w\">",
            safelist()));
        Element img = doc.expectFirst("img");
        String srcset = img.attr("srcset");
        assertTrue(srcset.contains("http://example.com/a.jpg,"));
        assertTrue(srcset.contains("2x"));
        assertTrue(srcset.contains("480w"));
    }

    @Test void neverThrowsOnHostileInput() {
        String[] hostile = {
            "", " ", ",", ",,,", " , , ",
            "http://example.com/a.jpg,",
            "http://example.com/a.jpg , , ,",
            "http://example.com/a.jpg (",
            "http://example.com/a.jpg ((,,,)) 1x, http://example.com/b.jpg 1x",
            "http://example.com/a.jpg \t\n\r\f 1x  ,  http://example.com/b.jpg",
            "http://example.com/a.jpg 99999999999999999999999999999999w, http://example.com/b.jpg 1x"
        };
        Cleaner cleaner = new Cleaner(safelist());
        for (String value : hostile) {
            Document doc = Jsoup.parseBodyFragment("<img srcset=\"" + value + "\">", "http://example.com/");
            Document clean = assertDoesNotThrow(() -> cleaner.clean(doc));
            // a surviving srcset must never contain a javascript candidate
            clean.select("img").forEach(img ->
                assertFalse(img.attr("srcset").toLowerCase().contains("javascript")));
        }
    }

    // ===== <picture>/<source> candidate coverage =====

    @Test void sourceAndFallbackImgSrcsetAreFilteredWithSameRules() {
        String html = "<picture>"
            + "<source media=\"(min-width: 800px)\" srcset=\"/large.jpg 800w, javascript:x 1x\">"
            + "<img src=\"/fallback.jpg\" srcset=\"/fb.jpg 1x, javascript:y 2x\" alt=\"x\">"
            + "</picture>";
        String clean = Jsoup.clean(html, "http://example.com/", pictureSafelist());
        assertEquals("<picture>"
            + "<source media=\"(min-width: 800px)\" srcset=\"http://example.com/large.jpg 800w\">"
            + "<img src=\"http://example.com/fallback.jpg\" srcset=\"http://example.com/fb.jpg 1x\" alt=\"x\">"
            + "</picture>", clean);
    }

    @Test void sourceSrcsetIsVerbatimIdenticalToImgSrcsetForSameValue() {
        String value = "\"http://example.com/a,b.jpg\" 1x, http://example.com/c.jpg 300w, , javascript:bad 2x";
        Safelist sl = pictureSafelist();
        String img = Jsoup.clean("<img srcset='" + value + "'>", "http://example.com/", sl);
        String source = Jsoup.clean("<source srcset='" + value + "'>", "http://example.com/", sl);
        assertEquals(
            img.replaceFirst("^<img", "<source").replaceFirst(">$", ">"),
            source);
        assertEquals("<source srcset=\"http://example.com/a,b.jpg 1x, http://example.com/c.jpg 300w\">", source);
    }

    @Test void nestedPicturesAndEachSourceAreHandledIndependently() {
        String html = "<picture>"
            + "<source srcset=\"javascript:a 1x\">"
            + "<picture>"
            + "<source type=\"image/webp\" srcset=\"/b.webp 1x, javascript:b 2x\">"
            + "<source media=\"(m)\" srcset=\"/c.jpg 2x\">"
            + "</picture>"
            + "<img src=\"/f.jpg\" srcset=\"/f2.jpg 2x\">"
            + "</picture>";
        String clean = Jsoup.clean(html, "http://example.com/", pictureSafelist());
        // the all-invalid first source loses only its srcset, and is not removed; siblings keep their order/attrs
        assertEquals("<picture>"
            + "<source>"
            + "<picture>"
            + "<source type=\"image/webp\" srcset=\"http://example.com/b.webp 1x\">"
            + "<source media=\"(m)\" srcset=\"http://example.com/c.jpg 2x\">"
            + "</picture>"
            + "<img src=\"http://example.com/f.jpg\" srcset=\"http://example.com/f2.jpg 2x\">"
            + "</picture>", clean);
    }

    @Test void mixedValidInvalidCandidatesOnSourceKeepOrderAndSeparators() {
        String html = "<source srcset=',, \"/a,b.jpg\" 1x,,, /b.jpg 2x, javascript:bad, \"/c d.jpg\" 3x,'>";
        String clean = Jsoup.clean(html, "http://example.com/path/", pictureSafelist());
        // quoted address drops its delimiting quotes; the whitespace-containing quoted address is dropped alone;
        // empty/leading/trailing commas do not swallow following candidates
        assertEquals("<source srcset=\"http://example.com/a,b.jpg 1x, http://example.com/b.jpg 2x\">", clean);
    }

    @Test void unterminatedQuoteOnSourceResyncsAtComma() {
        String html = "<picture><source media=\"(x)\" srcset='\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x'>"
            + "<img src=\"/f.jpg\"></picture>";
        String clean = Jsoup.clean(html, "http://example.com/", pictureSafelist());
        assertEquals("<picture><source media=\"(x)\" srcset=\"http://example.com/b.jpg 2x\">"
            + "<img src=\"http://example.com/f.jpg\"></picture>", clean);
    }

    @Test void emptyOrAllInvalidSourceSrcsetRemovesOnlyTheAttribute() {
        Safelist sl = pictureSafelist();
        String allInvalid = "<picture><source media=\"(m)\" type=\"image/webp\" srcset=\"javascript:x 1x\">"
            + "<img src=\"/f.jpg\"></picture>";
        assertEquals("<picture><source media=\"(m)\" type=\"image/webp\">"
            + "<img src=\"http://example.com/f.jpg\"></picture>",
            Jsoup.clean(allInvalid, "http://example.com/", sl));

        String empty = "<picture><source media=\"(m)\" srcset=\"   ,, \"><img src=\"/f.jpg\"></picture>";
        assertEquals("<picture><source media=\"(m)\"><img src=\"http://example.com/f.jpg\"></picture>",
            Jsoup.clean(empty, "http://example.com/", sl));
    }

    @Test void sourceTagGatingRemovesTagWhenNotAllowed() {
        // picture allowed, but source tag not: the source element itself is gone (children/content hoisted per
        // existing unknown-tag rules), media/type/srcset do not survive on it; img is untouched
        Safelist sl = Safelist.relaxed().addTags("picture").addAttributes("img", "srcset");
        String html = "<picture><source media=\"(m)\" type=\"image/webp\" srcset=\"/a.jpg 1x\"><img src=\"/f.jpg\"></picture>";
        String clean = Jsoup.clean(html, "http://example.com/", sl);
        assertFalse(clean.contains("<source"), "source must not appear when its tag is not allowed: " + clean);
        assertFalse(clean.contains("javascript"));
        assertTrue(clean.contains("<img"));
    }

    @Test void sourceAllowedButSrcsetNotRemovesOnlySrcset() {
        Safelist sl = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("source", "media", "type")
            .addAttributes("img", "srcset");
        String html = "<picture><source media=\"(m)\" type=\"image/webp\" srcset=\"/a.jpg 1x\"><img src=\"/f.jpg\"></picture>";
        String clean = Jsoup.clean(html, "http://example.com/", sl);
        assertEquals("<picture><source media=\"(m)\" type=\"image/webp\"><img src=\"http://example.com/f.jpg\"></picture>",
            clean);
    }

    @Test void explicitSourceSrcsetProtocolsStillWinOverImgPolicy() {
        // an explicit policy on source is not overridden by the img fallback; here only data: is allowed on source,
        // while img keeps http/https
        Safelist sl = pictureSafelist().addProtocols("source", "srcset", "data");
        String html = "<source srcset=\"data:text/plain,Hi 1x, http://example.com/a.jpg 2x\">";
        assertEquals("<source srcset=\"data:text/plain,Hi 1x\">", Jsoup.clean(html, "http://example.com/", sl));
    }

    // ===== element / attribute whitelist and protocol-policy priority and isolation =====

    /** Clean a standalone {@code <tag srcset='raw'>} fragment (outside a picture) and return the srcset value. */
    private static String cleanSrcset(String tag, String raw, String baseUri, Safelist sl) {
        Document clean = new Cleaner(sl).clean(Jsoup.parseBodyFragment("<" + tag + " srcset='" + raw + "'>", baseUri));
        Element el = clean.expectFirst(tag);
        return el.attr("srcset");
    }

    @Test void sameSrcsetValueIsCleanedVerbatimIdenticallyOnSourceAndImg() {
        // candidate splitting, quote boundaries, character references, consecutive/leading/trailing commas,
        // unterminated quotes, and illegal candidates must produce byte-identical results on source and img,
        // both with and without a document base
        String[] values = {
            "/a.jpg 1x, http://example.com/b.jpg 2x",
            "\"http://example.com/a,b.jpg\" 1x, , javascript:x, http://ok.com/c.jpg 2x, ",
            ",, /x , ,",
            "\"/a b.jpg\" 1x, /ok.jpg 2x",
            "http://example.com/a.jpg 0w, http://example.com/b.jpg 1x",
            "\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x",
            "data:text/plain,Hi 1x, http://example.com/a.jpg 2x",
            "&#01;javascript:alert(1), http://example.com/ok.jpg 1x",
            "/assets/a.png 1x, rel.png 2x, //e.com/x.png 3x"
        };
        for (String value : values) {
            String withBaseImg = cleanSrcset("img", value, "http://example.com/", pictureSafelist());
            String withBaseSource = cleanSrcset("source", value, "http://example.com/", pictureSafelist());
            String noBaseImg = cleanSrcset("img", value, "", pictureSafelist());
            String noBaseSource = cleanSrcset("source", value, "", pictureSafelist());
            assertEquals(withBaseImg, withBaseSource, "base: value diverged between img/source: " + value);
            assertEquals(noBaseImg, noBaseSource, "no-base: value diverged between img/source: " + value);
        }
    }

    @Test void looseningSourceProtocolsNeverRelaxesImgAndViceVersa() {
        // source is widened to accept data:; img keeps the relaxed img src policy (http/https) and must not gain data:
        Safelist looseSource = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset")
            .addProtocols("source", "srcset", "http", "https", "data");
        assertEquals("<img srcset=\"http://e.com/a.jpg 2x\">",
            Jsoup.clean("<img srcset=\"data:x 1x, http://e.com/a.jpg 2x\">", "http://example.com/", looseSource));
        assertEquals("<source srcset=\"data:x 1x, http://e.com/a.jpg 2x\">",
            Jsoup.clean("<source srcset=\"data:x 1x, http://e.com/a.jpg 2x\">", "http://example.com/", looseSource));

        // img is tightened to https only; an explicit source policy (data,http,https) is unaffected by that
        Safelist tightImg = looseSource.addProtocols("img", "srcset", "https");
        assertEquals("<img srcset=\"https://e.com/b.jpg 3x\">",
            Jsoup.clean("<img srcset=\"data:x 1x, http://e.com/a.jpg 2x, https://e.com/b.jpg 3x\">", "http://example.com/", tightImg));
        assertEquals("<source srcset=\"data:x 1x, http://e.com/a.jpg 2x, https://e.com/b.jpg 3x\">",
            Jsoup.clean("<source srcset=\"data:x 1x, http://e.com/a.jpg 2x, https://e.com/b.jpg 3x\">", "http://example.com/", tightImg));
    }

    @Test void sourceWithoutOwnPolicyInheritsImgPolicyButImgIgnoresSourcePolicy() {
        // source has srcset structurally allowed but no protocols of its own; img is tightened to https:
        // the fallback source inherits the img policy (http dropped), proving the inheritance is directional only
        Safelist sl = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset")
            .addProtocols("img", "srcset", "https");
        assertEquals("<source srcset=\"https://e.com/b.jpg 2x\">",
            Jsoup.clean("<source srcset=\"http://e.com/a.jpg 1x, https://e.com/b.jpg 2x\">", "http://example.com/", sl));
        assertEquals("<img srcset=\"https://e.com/b.jpg 2x\">",
            Jsoup.clean("<img srcset=\"http://e.com/a.jpg 1x, https://e.com/b.jpg 2x\">", "http://example.com/", sl));
    }

    @Test void globalAllAttributeRuleAppliesOnlyWhenNoElementRuleDeclared() {
        // srcset allowed only via :all, with an :all protocol policy: governs both img and source
        Safelist all = new Safelist()
            .addTags("img", "source")
            .addAttributes(":all", "srcset")
            .addProtocols(":all", "srcset", "http", "https");
        assertEquals("<source srcset=\"http://e.com/a.jpg 1x\"><img srcset=\"http://i.com/b.jpg 2x\">",
            Jsoup.clean("<source srcset=\"http://e.com/a.jpg 1x, javascript:x\"><img srcset=\"http://i.com/b.jpg 2x, data:y\">",
                "http://example.com/", all));

        // a tag-specific policy overrides :all for that tag only; other tags still use :all
        Safelist override = all.addTags("div").addProtocols("img", "srcset", "https");
        assertEquals("<img srcset=\"https://e.com/b.jpg 2x\">",
            Jsoup.clean("<img srcset=\"http://e.com/a.jpg 1x, https://e.com/b.jpg 2x\">", "http://example.com/", override));
        assertEquals("<div srcset=\"http://e.com/a.jpg 1x, https://e.com/b.jpg 2x\"></div>",
            Jsoup.clean("<div srcset=\"http://e.com/a.jpg 1x, https://e.com/b.jpg 2x\"></div>", "http://example.com/", override));
    }

    @Test void globalAttributeWithoutProtocolsActsAsUntypedAttribute() {
        // srcset allowed via :all with NO protocol policy: like any ordinary untyped attribute, values pass through
        Safelist all = new Safelist().addTags("img", "source").addAttributes(":all", "srcset");
        assertEquals("<source srcset=\"javascript:x 1x, /a 2x\"><img srcset=\"javascript:x 1x, /a 2x\">",
            Jsoup.clean("<source srcset=\"javascript:x 1x, /a 2x\"><img srcset=\"javascript:x 1x, /a 2x\">",
                "http://example.com/", all));
    }

    @Test void srcsetAttributeAllowanceOnOneElementNeverAuthorizesTheOther() {
        // srcset allowed on source but not img: source keeps it, the fallback img loses only that attribute
        Safelist sourceOnly = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("source", "srcset", "media", "type");
        String html = "<picture><source media=\"(m)\" srcset=\"http://e.com/a.jpg 1x\">"
            + "<img src=\"/f.jpg\" srcset=\"http://e.com/i.jpg 1x\" alt=\"x\"></picture>";
        assertEquals("<picture><source media=\"(m)\" srcset=\"http://e.com/a.jpg 1x\">"
            + "<img src=\"http://example.com/f.jpg\" alt=\"x\"></picture>",
            Jsoup.clean(html, "http://example.com/", sourceOnly));

        // srcset allowed on img but not source: img keeps it, source keeps media/type but loses only srcset
        Safelist imgOnly = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("source", "media", "type")
            .addAttributes("img", "srcset");
        assertEquals("<picture><source media=\"(m)\" type=\"image/webp\">"
            + "<img src=\"http://example.com/f.jpg\" srcset=\"http://e.com/i.jpg 1x\"></picture>",
            Jsoup.clean("<picture><source media=\"(m)\" type=\"image/webp\" srcset=\"http://e.com/a.jpg 1x\">"
                + "<img src=\"/f.jpg\" srcset=\"http://e.com/i.jpg 1x\"></picture>", "http://example.com/", imgOnly));
    }

    @Test void rootRelativeCandidateKeepsExactInputWithoutBaseAndStillRejectsUnsafe() {
        Safelist sl = pictureSafelist();
        // exact input spelling is retained: no scheme/host and no extra path segments are concatenated
        assertEquals("<img srcset=\"/assets/a.png\">", Jsoup.clean("<img srcset=\"/assets/a.png\">", sl));
        assertEquals("<source srcset=\"/assets/a.png\">", Jsoup.clean("<source srcset=\"/assets/a.png\">", sl));
        // only the root-relative candidate survives next to a path-relative (unresolvable) or unsafe-scheme one
        assertEquals("<img srcset=\"/ok.jpg 1x\">",
            Jsoup.clean("<img srcset=\"/ok.jpg 1x, rel.jpg 2x, javascript:bad 3x\">", sl));
        // control characters still reject even an otherwise root-relative-looking candidate
        assertEquals("<img srcset=\"/ok.jpg 1x\">",
            Jsoup.clean("<img srcset=\"/a&#x0a;.jpg, /ok.jpg 1x\">", sl));
        // with a base present, the root-relative candidate is resolved as before (not treated as the no-base case)
        assertEquals("<img srcset=\"http://example.com/assets/a.png\">",
            Jsoup.clean("<img srcset=\"/assets/a.png\">", "http://example.com/", sl));
        // the no-base verbatim output stays stable through re-clean and serialize-reparse-clean
        Cleaner cleaner = new Cleaner(sl);
        Document once = cleaner.clean(Jsoup.parseBodyFragment("<picture><source srcset='/x.png 1x, bad 2x'><img srcset='/y.png'></picture>", ""));
        Document twice = cleaner.clean(once);
        assertEquals(once.body().html(), twice.body().html());
        assertEquals(once.body().html(), cleaner.clean(Jsoup.parse(once.body().html())).body().html());
        assertTrue(once.body().html().contains("<source srcset=\"/x.png 1x\""));
        assertTrue(once.body().html().contains("<img srcset=\"/y.png\""));
    }

    @Test void pictureCleaningIsIdempotentAndReparseStable() {
        String html = "<picture>"
            + "<source media=\"(min-width: 600px)\" srcset=\"/a.jpg 600w, javascript:x 1x\">"
            + "<source type=\"image/webp\" srcset=\"/b.webp, /c.webp 2x\">"
            + "<img src=\"/f.jpg\" srcset=\"/f.jpg 1x, bad\">"
            + "</picture>";
        Cleaner cleaner = new Cleaner(pictureSafelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(html, "http://example.com/"));
        Document twice = cleaner.clean(once);
        assertEquals(once.body().html(), twice.body().html());

        String onceHtml = once.body().html();
        Document reparsed = Jsoup.parse(onceHtml);
        assertEquals(onceHtml, cleaner.clean(reparsed).body().html());
        assertEquals(2, once.select("source").size());
        assertEquals("http://example.com/a.jpg 600w", once.expectFirst("source").attr("srcset"));
    }

    @Test void oneFailingSourceSrcsetDoesNotHalfUpdateOrAffectOthers() {
        Document dirty = Jsoup.parseBodyFragment(
            "<picture><source srcset=\"javascript:bad\"><source srcset=\"http://example.com/ok.jpg 1x\">"
                + "<img srcset=\"http://example.com/img.jpg 2x\"></picture>",
            "http://example.com/");
        String original = dirty.expectFirst("source").attr("srcset");
        Document clean = new Cleaner(pictureSafelist()).clean(dirty);
        assertEquals(original, dirty.expectFirst("source").attr("srcset"), "input document must not be modified");
        assertEquals("<picture><source><source srcset=\"http://example.com/ok.jpg 1x\">"
            + "<img srcset=\"http://example.com/img.jpg 2x\"></picture>", clean.body().html());
    }
}
