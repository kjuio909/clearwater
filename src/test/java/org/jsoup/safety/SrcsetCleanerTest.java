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

    @Test void dropsUnresolvableRelativeCandidatesWithoutBase() {
        String clean = Jsoup.clean("<img srcset=\"/a.jpg 1x\">", safelist());
        assertEquals("<img>", clean);
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
}
