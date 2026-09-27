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

    @Test void rootRelativeCandidateIsKeptVerbatimWithoutBase() {
        // with no document base URI, a safe root-relative reference is preserved as input, never joined to a
        // fabricated path and never dropped
        assertEquals("<img srcset=\"/assets/a.png 1x\">",
            Jsoup.clean("<img srcset=\"/assets/a.png 1x\">", safelist()));
        assertEquals("<img srcset=\"/assets/a.png\">",
            Jsoup.clean("<img srcset=\"/assets/a.png\">", safelist()));

        // the bad candidate is dropped on its own; the root-relative one survives, in order
        assertEquals("<img srcset=\"/assets/a.png 1x\">",
            Jsoup.clean("<img srcset=\"/assets/a.png 1x, javascript:alert(1) 2x\">", safelist()));

        // the identical value on a source and the fallback img is filtered and serialized verbatim identically
        Safelist sl = pictureSafelist();
        String value = "/assets/a.png 1x, javascript:bad 2x";
        String img = Jsoup.clean("<img srcset='" + value + "'>", sl);
        String source = Jsoup.clean("<source srcset='" + value + "'>", sl);
        assertEquals(img.replaceFirst("^<img", "<source"), source);
        assertEquals("<source srcset=\"/assets/a.png 1x\">", source);

        // no joining against any path: the spelling is byte-for-byte the input reference
        Document clean = new Cleaner(sl).clean(Jsoup.parseBodyFragment(
            "<picture><source srcset=\"/a/b.png 2x\"><img srcset=\"/c/d.png 3x\"></picture>"));
        assertEquals("/a/b.png 2x", clean.expectFirst("source").attr("srcset"));
        assertEquals("/c/d.png 3x", clean.expectFirst("img").attr("srcset"));
    }

    @Test void nonRootRelativeCandidatesWithoutBaseAreDropped() {
        // in-directory relative references cannot be resolved without a base and are not root-relative
        assertEquals("<img>", Jsoup.clean("<img srcset=\"a.jpg 1x\">", safelist()));
        assertEquals("<img>", Jsoup.clean("<img srcset=\"assets/a.png 1x\">", safelist()));
        // a protocol-relative reference inherits an unknown scheme without a base and is not retained
        assertEquals("<img>", Jsoup.clean("<img srcset=\"//evil.example/a.png 1x\">", safelist()));
        // mixed: only the unresolvable relative candidate is lost
        assertEquals("<img srcset=\"/a.png 1x\">",
            Jsoup.clean("<img srcset=\"a.png 2x, /a.png 1x\">", safelist()));
    }

    @Test void rootRelativeCandidateWithControlCharIsRejected() {
        // the root-relative retention never bypasses the control-character / scheme checks
        assertEquals("<img srcset=\"/ok.png 1x\">",
            Jsoup.clean("<img srcset=\"/a&#x0a;.png, /ok.png 1x\">", safelist()));
        assertEquals("<img srcset=\"/ok.png 1x\">",
            Jsoup.clean("<img srcset=\"/&#x09;x, /ok.png 1x\">", safelist()));
    }

    @Test void rootRelativeCandidatesSurviveRepeatedCleaningWithoutBase() {
        Cleaner cleaner = new Cleaner(pictureSafelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(
            "<picture><source srcset=\"/a.png 1x, javascript:bad\"><img srcset=\"/b.png 2x\"></picture>"));
        String expected = "<picture><source srcset=\"/a.png 1x\"><img srcset=\"/b.png 2x\"></picture>";
        assertEquals(expected, once.body().html());
        assertEquals(expected, cleaner.clean(once).body().html());
        // serialize, reparse, clean again: structure, order, and values are stable
        Document reparsed = Jsoup.parse(once.body().html());
        assertEquals(expected, cleaner.clean(reparsed).body().html());
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

    @Test void everyEdgeSrcsetValueCleansVerbatimIdenticallyOnSourceAndImg() {
        // the same value, on either element, must split candidates, handle quote boundaries and character
        // references, skip consecutive/leading/trailing commas, resync after an unterminated quote, and drop
        // illegal candidates to a byte-identical result
        String[] values = {
            ",, http://example.com/a.jpg 1x,,, http://example.com/b.jpg 2x,",
            "  ",
            ",,,",
            "'http://example.com/a b.jpg' 1x, http://example.com/c.jpg 2x",
            "\"http://example.com/a,b.jpg\", , http://example.com/c.jpg 2x",
            "\"http://example.com/a.jpg 1x, http://example.com/b.jpg 2x, http://example.com/c.jpg 3x",
            "http://example.com/a.jpg 100w 200w, http://example.com/b.jpg 2x, javascript:x 3x",
            "http://example.com/a?b=1&amp;c=2 1x, http://example.com/d.jpg 0w",
            "data:text/plain,Hi 1x, http://example.com/a.jpg 2x",
            "/assets/a.png 1x, /assets/b.png 2x, javascript:z",
        };
        Safelist sl = pictureSafelist().addProtocols("source", "srcset", "http", "https")
            .addProtocols("img", "srcset", "http", "https");
        for (String value : values) {
            String img = Jsoup.clean("<img srcset='" + value + "'>", "http://example.com/p/", sl);
            String source = Jsoup.clean("<source srcset='" + value + "'>", "http://example.com/p/", sl);
            assertEquals(img.replaceFirst("^<img", "<source"), source,
                "srcset cleaned differently on source vs img: " + value);
        }
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

    @Test void sourceWithNoOwnPolicyAndImgWhitelistedUsesImgPolicy() {
        // source has no own or global policy; the fallback img tag is whitelisted with http/https on src, so source
        // candidates are filtered by that same image policy (this is what makes same-string results identical)
        Safelist sl = new Safelist()
            .addTags("img", "source")
            .addAttributes("img", "src", "srcset")
            .addAttributes("source", "srcset")
            .addProtocols("img", "src", "http", "https");
        String html = "<source srcset=\"http://example.com/a.jpg 1x, javascript:x 2x\">"
            + "<img srcset=\"http://example.com/b.jpg 1x, javascript:y 2x\">";
        String clean = Jsoup.clean(html, "http://example.com/", sl);
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x\">"
            + "<img srcset=\"http://example.com/b.jpg 1x\">", clean);
    }

    @Test void sourceNeverBorrowsImgPolicyWhenImgTagIsNotWhitelisted() {
        // only the source tag is allowed: img policy cannot govern it, and tightening img must not change source
        Safelist onlySource = new Safelist()
            .addTags("source")
            .addAttributes("source", "srcset");
        String html = "<source srcset=\"http://example.com/a.jpg 1x, javascript:x 2x, /r.png 3x\">";
        // no own and no global protocol policy: candidates are accepted like an ordinary untyped attribute
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x, javascript:x 2x, /r.png 3x\">",
            Jsoup.clean(html, "http://example.com/", onlySource));

        // adding or tightening an img policy leaves source completely untouched (img is not even whitelisted)
        Safelist withImgPolicy = new Safelist()
            .addTags("source")
            .addAttributes("source", "srcset")
            .addProtocols("img", "src", "https");
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x, javascript:x 2x, /r.png 3x\">",
            Jsoup.clean(html, "http://example.com/", withImgPolicy));
    }

    @Test void onlyImgWhitelistedRemovesSourceEntirely() {
        Safelist onlyImg = Safelist.relaxed().addAttributes("img", "srcset");
        String html = "<source srcset=\"http://example.com/a.jpg 1x\"><img srcset=\"http://example.com/b.jpg 1x\">";
        String clean = Jsoup.clean(html, "http://example.com/", onlyImg);
        assertFalse(clean.contains("<source"));
        assertEquals("<img srcset=\"http://example.com/b.jpg 1x\">", clean);
    }

    @Test void globalProtocolRuleAppliesToBothElements() {
        // a :all protocol rule governs both source and img when neither declares an own policy
        Safelist sl = new Safelist()
            .addTags("img", "source")
            .addAttributes(":all", "srcset")
            .addProtocols(":all", "srcset", "http", "https");
        String html = "<source srcset=\"http://example.com/a.jpg 1x, data:text/plain,x 2x\">"
            + "<img srcset=\"https://example.com/b.jpg 1x, javascript:y 2x\">";
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x\">"
            + "<img srcset=\"https://example.com/b.jpg 1x\">", Jsoup.clean(html, "http://example.com/", sl));
    }

    @Test void elementSpecificPolicyOverridesGlobalRule() {
        // :all allows only data:, but img declares its own http/https policy; img wins for its own attribute
        Safelist sl = new Safelist()
            .addTags("img")
            .addAttributes(":all", "srcset")
            .addAttributes("img", "srcset")
            .addProtocols(":all", "srcset", "data")
            .addProtocols("img", "srcset", "http", "https");
        String html = "<img srcset=\"http://example.com/a.jpg 1x, data:text/plain,x 2x\">";
        assertEquals("<img srcset=\"http://example.com/a.jpg 1x\">",
            Jsoup.clean(html, "http://example.com/", sl));
    }

    @Test void globalRuleTakesPrecedenceOverImgFallbackForSource() {
        // source has no own policy; a :all rule exists, so it governs source even though the whitelisted img also
        // defines a policy. :all allows data: only -> source keeps data: and rejects http:.
        Safelist sl = new Safelist()
            .addTags("img", "source")
            .addAttributes(":all", "srcset")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset")
            .addProtocols(":all", "srcset", "data")
            .addProtocols("img", "srcset", "http", "https");
        String source = Jsoup.clean("<source srcset=\"data:text/plain,Hi 1x, http://example.com/a.jpg 2x\">",
            "http://example.com/", sl);
        assertEquals("<source srcset=\"data:text/plain,Hi 1x\">", source);
        // the img-specific policy still governs img independently
        String img = Jsoup.clean("<img srcset=\"data:text/plain,Hi 1x, http://example.com/a.jpg 2x\">",
            "http://example.com/", sl);
        assertEquals("<img srcset=\"http://example.com/a.jpg 2x\">", img);
    }

    @Test void tighteningSourcePolicyDoesNotChangeImgAndViceVersa() {
        // source restricted to https; img keeps the relaxed http/https image policy
        Safelist sl = pictureSafelist().addProtocols("source", "srcset", "https");
        String html = "<source srcset=\"http://example.com/a.jpg 1x, https://example.com/b.jpg 2x\">"
            + "<img srcset=\"http://example.com/c.jpg 1x, https://example.com/d.jpg 2x\">";
        assertEquals("<source srcset=\"https://example.com/b.jpg 2x\">"
            + "<img srcset=\"http://example.com/c.jpg 1x, https://example.com/d.jpg 2x\">",
            Jsoup.clean(html, "https://example.com/", sl));

        // and the reverse: restricting img leaves a source with its own policy completely independent
        Safelist imgOnly = pictureSafelist()
            .addProtocols("source", "srcset", "http", "https")
            .addProtocols("img", "srcset", "https");
        assertEquals("<source srcset=\"http://example.com/a.jpg 1x, https://example.com/b.jpg 2x\">"
            + "<img srcset=\"https://example.com/d.jpg 2x\">",
            Jsoup.clean(html, "https://example.com/", imgOnly));
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
