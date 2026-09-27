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

    @Test void doubleQuotedAddressKeepsInnerCommaAndDropsQuotes() {
        String html = "<img srcset='\"http://example.com/a,b.jpg\" 1x, http://example.com/c.jpg 2x'>";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x, http://example.com/c.jpg 2x\">", clean);
    }

    @Test void singleQuotedAddressKeepsInnerCommaAndDropsQuotes() {
        String html = "<img srcset=\"'http://example.com/a,b.jpg' 1x, http://example.com/c.jpg 2x\">";
        String clean = Jsoup.clean(html, safelist());
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x, http://example.com/c.jpg 2x\">", clean);
    }

    @Test void quotedAddressWithWhitespaceIsRequotedAndReparseable() {
        Cleaner cleaner = new Cleaner(safelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(
            "<img srcset='\"http://example.com/a b.jpg\" 1x, http://example.com/c.jpg 2x'>", "http://example.com/"));
        assertEquals("\"http://example.com/a b.jpg\" 1x, http://example.com/c.jpg 2x",
            once.expectFirst("img").attr("srcset"));

        // serializing and cleaning again yields the same candidates
        Document twice = cleaner.clean(Jsoup.parseBodyFragment(once.body().html(), "http://example.com/"));
        assertEquals(once.body().html(), twice.body().html());
    }

    @Test void quotedUnsafeAddressIsDroppedAlone() {
        String html = "<img srcset='\"javascript:alert(1)\" 1x, http://example.com/a.jpg 2x'>";
        assertEquals("<img srcset=\"http://example.com/a.jpg 2x\">", Jsoup.clean(html, safelist()));
    }

    @Test void entitiesInsideQuotedAddressAreNotBoundaries() {
        String html = "<img srcset='\"http://example.com/a&comma;b.jpg\" 1x'>";
        assertEquals("<img srcset=\"http://example.com/a,b.jpg 1x\">", Jsoup.clean(html, safelist()));
    }

    @Test void unterminatedQuotedAddressConsumesRestWithoutThrowing() {
        Cleaner cleaner = new Cleaner(safelist());
        Document doc = Jsoup.parseBodyFragment("<img srcset='\"http://example.com/a.jpg'>", "http://example.com/");
        Document clean = assertDoesNotThrow(() -> cleaner.clean(doc));
        assertEquals("http://example.com/a.jpg", clean.expectFirst("img").attr("srcset"));
    }

    @Test void zeroDensityIsDropped() {
        String html = "<img srcset=\"http://example.com/a.jpg 0x, http://example.com/b.jpg 0.0x, http://example.com/c.jpg 2x\">";
        assertEquals("<img srcset=\"http://example.com/c.jpg 2x\">", Jsoup.clean(html, safelist()));
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
