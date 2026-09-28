package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for per-entry cleaning of {@code sizes} attributes in the {@link Cleaner}, on {@code img} and {@code source}.
 */
public class SizesCleanerTest {

    /** The public entry point for responsive-image cleaning: picture/source tags plus srcset and sizes. */
    private static Safelist pictureSafelist() {
        return Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset", "sizes")
            .addAttributes("source", "srcset", "sizes", "media", "type");
    }

    private static Safelist imgSizesSafelist() {
        return Safelist.relaxed().addAttributes("img", "sizes");
    }

    // ===== whitelist gating =====

    @Test void sizesRemovedWhenNotAllowed() {
        // without the attribute in the safelist, the existing attribute-removal behavior is unchanged
        String html = "<img src=\"http://example.com/src.jpg\" sizes=\"100px\">";
        assertEquals("<img src=\"http://example.com/src.jpg\">", Jsoup.clean(html, Safelist.relaxed()));
    }

    @Test void sizesAllowedButElementTagNotAllowed() {
        Safelist sl = Safelist.relaxed().addTags("picture").addAttributes("img", "sizes");
        String html = "<picture><source sizes=\"100px\"><img sizes=\"200px\"></picture>";
        String clean = Jsoup.clean(html, sl);
        assertFalse(clean.contains("<source"), clean);
        assertTrue(clean.contains("sizes=\"200px\""), clean);
    }

    @Test void sizesOnSourceAllowedIndependentlyOfImg() {
        Safelist sl = new Safelist()
            .addTags("source")
            .addAttributes("source", "sizes");
        // source keeps its own sizes even though no img tag or policy exists at all
        assertEquals("<source sizes=\"100px\">", Jsoup.clean("<source sizes=\"100px\">", sl));
    }

    // ===== basic lengths and entries =====

    @Test void plainLengthsAreKept() {
        String html = "<img sizes=\"100px, 50em, 1.5rem, 100vw, 90vh, 10vmin, 20vmax, 30ch, 40ex, 50%\">";
        String clean = Jsoup.clean(html, imgSizesSafelist());
        assertEquals("<img sizes=\"100px, 50em, 1.5rem, 100vw, 90vh, 10vmin, 20vmax, 30ch, 40ex, 50%\">", clean);
    }

    @Test void zeroIsAcceptedWithAndWithoutUnit() {
        assertEquals("<img sizes=\"0\">", Jsoup.clean("<img sizes=\"0\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"0px\">", Jsoup.clean("<img sizes=\"0px\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"0.0rem\">", Jsoup.clean("<img sizes=\"0.0rem\">", imgSizesSafelist()));
        // a non-zero number without a unit is not a length
        assertEquals("<img>", Jsoup.clean("<img sizes=\"100\">", imgSizesSafelist()));
    }

    @Test void mediaConditionPlusLength() {
        String html = "<img sizes=\"(min-width: 600px) 200px, (max-width: 400px) 100px, 50vw\">";
        String clean = Jsoup.clean(html, imgSizesSafelist());
        assertEquals("<img sizes=\"(min-width: 600px) 200px, (max-width: 400px) 100px, 50vw\">", clean);
    }

    @Test void comparisonOperatorsAreAllowedInMediaConditions() {
        // the comparisons are allowed; serialized as normal attribute entities (the parsed attr value is unchanged)
        assertEquals("<img sizes=\"(width &gt;= 600px) 200px\">",
            Jsoup.clean("<img sizes=\"(width >= 600px) 200px\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"(400px &lt;= width &lt; 800px) 50%\">",
            Jsoup.clean("<img sizes=\"(400px <= width < 800px) 50%\">", imgSizesSafelist()));
        // the attribute value read back from the DOM is the unescaped comparison expression
        Document doc = Jsoup.parse(Jsoup.clean("<img sizes=\"(width >= 600px) 200px\">", imgSizesSafelist()));
        assertEquals("(width >= 600px) 200px", doc.expectFirst("img").attr("sizes"));
    }

    @Test void combinedMediaGroupsAreKept() {
        String html = "<img sizes=\"(min-width: 400px) and (max-width: 800px) 300px\">";
        assertEquals("<img sizes=\"(min-width: 400px) and (max-width: 800px) 300px\">",
            Jsoup.clean(html, imgSizesSafelist()));
    }

    @Test void nestedParenthesesInMediaCondition() {
        assertEquals("<img sizes=\"((min-width: 600px)) 200px\">",
            Jsoup.clean("<img sizes=\"((min-width: 600px)) 200px\">", imgSizesSafelist()));
    }

    // ===== splitting =====

    @Test void commaInsideParensBelongsToCurrentEntry() {
        // the comma is inside the media-condition group, so it must NOT split into a second entry; the whole entry is
        // malformed (a comma is not part of the media grammar) and is dropped alone, never swallowing the real next
        // entry after the top-level comma
        String html = "<img sizes=\"(min-width: 600px, foo: bar) 300px, 100px\">";
        String clean = Jsoup.clean(html, imgSizesSafelist());
        assertEquals("<img sizes=\"100px\">", clean);
    }

    @Test void commaInsideCalcBelongsToCurrentEntry() {
        // a comma inside the calc parens must not start a new entry; the malformed calc is dropped alone, the later
        // top-level entry survives
        String html = "<img sizes=\"calc(100px, 200px), 50vw\">";
        String clean = Jsoup.clean(html, imgSizesSafelist());
        assertEquals("<img sizes=\"50vw\">", clean);
    }

    @Test void emptyConsecutiveAndTrailingEntriesAreIgnored() {
        assertEquals("<img sizes=\"100px, 200px\">",
            Jsoup.clean("<img sizes=\",, 100px,,, 200px,\">", imgSizesSafelist()));
        assertEquals("<img>", Jsoup.clean("<img sizes=\",,,\">", imgSizesSafelist()));
        assertEquals("<img>", Jsoup.clean("<img sizes=\"   \">", imgSizesSafelist()));
    }

    // ===== per-entry failure isolation =====

    @Test void oneBadEntryIsDroppedAlone() {
        String html = "<img sizes=\"nonsense, 100px, 200px\">";
        assertEquals("<img sizes=\"100px, 200px\">", Jsoup.clean(html, imgSizesSafelist()));

        // a bad entry in the middle must not swallow a valid later entry
        String mixed = "<img sizes=\"100px, (bad, 200px, 300px\">";
        assertEquals("<img sizes=\"100px, 200px, 300px\">", Jsoup.clean(mixed, imgSizesSafelist()));
    }

    @Test void badEntryBeforeAndAfterGoodOneKeepsOrder() {
        String html = "<img sizes=\"bogus, 100px, -5px, 200px, javascript:x, 300px\">";
        assertEquals("<img sizes=\"100px, 200px, 300px\">", Jsoup.clean(html, imgSizesSafelist()));
    }

    @Test void duplicateMediaConditionsAreBothKeptInOrder() {
        String html = "<img sizes=\"(min-width: 600px) 100px, (min-width: 600px) 200px\">";
        assertEquals(html, Jsoup.clean(html, imgSizesSafelist()));
    }

    // ===== rejected grammar =====

    @Test void negativeAndBadLengthsAreRejected() {
        String html = "<img sizes=\"-5px, +10px, 100, 12pt, 100 px, .5px, 5PXx, 100p x\">";
        assertEquals("<img>", Jsoup.clean(html, imgSizesSafelist()));
    }

    @Test void mediaConditionWithoutLengthIsRejected() {
        assertEquals("<img>", Jsoup.clean("<img sizes=\"(min-width: 600px)\">", imgSizesSafelist()));
        assertEquals("<img>", Jsoup.clean("<img sizes=\"(min-width: 600px) nonsense\">", imgSizesSafelist()));
    }

    @Test void emptyMediaConditionIsRejected() {
        assertEquals("<img>", Jsoup.clean("<img sizes=\"() 100px\">", imgSizesSafelist()));
        // the empty group drops only that entry; the earlier valid entry is kept
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"100px, ( ) 200px\">", imgSizesSafelist()));
    }

    @Test void semicolonsBracesAndControlCharsRejectTheEntry() {
        // a semicolon turns the entry into a declaration shape; only the later valid entry survives
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"100px; background: url(javascript:alert(1)), 100px\">", imgSizesSafelist()));
        assertEquals("<img>",
            Jsoup.clean("<img sizes=\"100px { color: red }\">", imgSizesSafelist()));
        // an entity-decoded tab inside the length drops only that entry, not the next one
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"10&#x09;0px, 100px\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"10&#x0a;0px, 100px\">", imgSizesSafelist()));
    }

    @Test void scriptFragmentsAndUrlsAreRejected() {
        // colon is permitted inside a media group, but a media prefix with no group cannot smuggle a scheme
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"javascript:alert(1) 100px, 100px\">", imgSizesSafelist()));
        // url() with slashes uses characters the media grammar forbids
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"(background: url(http://evil.example/x)) 100px, 100px\">", imgSizesSafelist()));
        // expression() is not calc()
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"expression(alert(1)), 100px\">", imgSizesSafelist()));
        // a url()/scheme whose parentheses balance and whose characters are otherwise legal must still be rejected
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"(x: url(javascript:alert(1))) 100px, 100px\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"(x: expression(alert(1))) 100px, 100px\">", imgSizesSafelist()));
    }

    @Test void unbalancedParenthesesRejectTheEntry() {
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"(min-width: 600px 100px, 100px\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"100px\">",
            Jsoup.clean("<img sizes=\"min-width: 600px) 100px, 100px\">", imgSizesSafelist()));
    }

    @Test void unterminatedParenResyncsAtComma() {
        // the first entry never closes its paren; it is dropped alone and the later entries survive
        String html = "<img sizes=\"(min-width: 600px, 200px, 300px\">";
        assertEquals("<img sizes=\"200px, 300px\">", Jsoup.clean(html, imgSizesSafelist()));
    }

    @Test void quotesAreStrippedOrRejected() {
        // delimiting quotes around the whole value are syntax only and never appear in the output
        assertEquals("<img sizes=\"100px, 200px\">",
            Jsoup.clean("<img sizes='\"100px\", 200px'>", imgSizesSafelist()));
        assertEquals("<img sizes=\"100px, 200px\">",
            Jsoup.clean("<img sizes=\"'100px', 200px\">", imgSizesSafelist()));
        // an embedded quote that is not a delimiter invalidates only that entry
        assertEquals("<img sizes=\"200px\">",
            Jsoup.clean("<img sizes=\"10'0px, 200px\">", imgSizesSafelist()));
    }

    // ===== calc() =====

    @Test void validCalcExpressionsAreKept() {
        assertEquals("<img sizes=\"calc(100px - 2rem)\">",
            Jsoup.clean("<img sizes=\"calc(100px - 2rem)\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"(max-width: 600px) calc(100vw - 32px)\">",
            Jsoup.clean("<img sizes=\"(max-width: 600px) calc(100vw - 32px)\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"calc(2 * 50%)\">",
            Jsoup.clean("<img sizes=\"calc(2 * 50%)\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"calc((100vw - 20px) / 2)\">",
            Jsoup.clean("<img sizes=\"calc((100vw - 20px) / 2)\">", imgSizesSafelist()));
        // input casing of the function and units is preserved verbatim
        assertEquals("<img sizes=\"CALC(100PX + 10REM)\">",
            Jsoup.clean("<img sizes=\"CALC(100PX + 10REM)\">", imgSizesSafelist()));
    }

    @Test void invalidCalcExpressionsAreRejected() {
        String[] bad = {
            "calc()",                // empty
            "calc(-100px)",          // leading minus
            "calc(+100px)",          // leading plus
            "calc(100px +)",         // dangling operator
            "calc(100px 200px)",     // missing operator
            "calc(100px; 200px)",    // semicolon
            "calc(url(http://x))",   // url
            "calc(100px - 2pt)",     // pt is not an allowed unit
            "calc(100px",            // unclosed
            "calc((100px)",          // unbalanced nested
        };
        for (String value : bad) {
            assertEquals("<img>", Jsoup.clean("<img sizes=\"" + value + "\">", imgSizesSafelist()),
                "expected rejection: " + value);
        }
    }

    @Test void calcWithSingleOperandOrBareNumberIsAcceptedLexically() {
        // the grammar is lexical and non-empty; a single calculation value is a balanced, well-formed expression
        assertEquals("<img sizes=\"calc(100px)\">",
            Jsoup.clean("<img sizes=\"calc(100px)\">", imgSizesSafelist()));
        assertEquals("<img sizes=\"calc(100px + 2)\">",
            Jsoup.clean("<img sizes=\"calc(100px + 2)\">", imgSizesSafelist()));
    }

    @Test void calcAfterMediaCondition() {
        assertEquals("<img sizes=\"(min-width: 600px) calc(100vw - 20px), 100px\">",
            Jsoup.clean("<img sizes=\"(min-width: 600px) calc(100vw - 20px), 100px\">", imgSizesSafelist()));
    }

    // ===== source vs img parity and independence =====

    @Test void sameSizesValueCleansVerbatimIdenticallyOnSourceAndImg() {
        String[] values = {
            "100px, 200px, 50%",
            "(min-width: 600px) 200px, 100px",
            "(min-width: 600px) and (max-width: 900px) 300px, calc(100vw - 20px)",
            "nonsense, 100px, -5px, 200px, , 300px,",
            "(unclosed, 200px, 300px",
            "calc(100vw - 10px), (orientation: portrait) 40em",
            "0, 0px, 100vmin",
            "javascript:x 100px, 100px",
            ",,,",
            "  ",
        };
        Safelist sl = pictureSafelist();
        for (String value : values) {
            String img = Jsoup.clean("<img sizes='" + value + "'>", sl);
            String source = Jsoup.clean("<source sizes='" + value + "'>", sl);
            assertEquals(img.replaceFirst("^<img", "<source"), source,
                "sizes cleaned differently on source vs img: " + value);
        }
    }

    @Test void oneFailingSizesDoesNotAffectOtherElementsOrAttributes() {
        Document dirty = Jsoup.parseBodyFragment(
            "<picture>"
                + "<source media=\"(min-width: 800px)\" type=\"image/webp\" srcset=\"http://e/a.webp 1x\" sizes=\"bad\">"
                + "<source media=\"(m)\" srcset=\"http://e/b.jpg 2x\" sizes=\"(min-width: 400px) 100px, 200px\">"
                + "<img src=\"http://e/f.jpg\" srcset=\"http://e/f2.jpg 1x\" sizes=\"300px, bad2\" alt=\"x\">"
                + "</picture>",
            "http://example.com/");
        Document clean = new Cleaner(pictureSafelist()).clean(dirty);
        assertEquals("<picture>"
            + "<source media=\"(min-width: 800px)\" type=\"image/webp\" srcset=\"http://e/a.webp 1x\">"
            + "<source media=\"(m)\" srcset=\"http://e/b.jpg 2x\" sizes=\"(min-width: 400px) 100px, 200px\">"
            + "<img src=\"http://e/f.jpg\" srcset=\"http://e/f2.jpg 1x\" sizes=\"300px\" alt=\"x\">"
            + "</picture>", clean.body().html());
    }

    @Test void sizesDoesNotRelaxSrcsetProtocolChecks() {
        // a valid sizes must not let a javascript: srcset candidate through
        Safelist sl = pictureSafelist();
        String html = "<img sizes=\"100px\" srcset=\"javascript:alert(1) 1x, http://example.com/a.jpg 2x\">";
        assertEquals("<img sizes=\"100px\" srcset=\"http://example.com/a.jpg 2x\">",
            Jsoup.clean(html, "http://example.com/", sl));

        String source = "<source sizes=\"(min-width: 1px) 1px\" srcset=\"javascript:x 1x, http://example.com/a.jpg 2x\">";
        assertEquals("<source sizes=\"(min-width: 1px) 1px\" srcset=\"http://example.com/a.jpg 2x\">",
            Jsoup.clean(source, "http://example.com/", sl));
    }

    @Test void sourcePolicyDoesNotWidenImgAndViceVersa() {
        // sizes has no protocol policy at all; only the attribute whitelist gates it per tag
        Safelist onlyImg = Safelist.relaxed().addAttributes("img", "sizes");
        String html = "<source sizes=\"100px\"><img sizes=\"200px\">";
        String clean = Jsoup.clean(html, onlyImg);
        assertFalse(clean.contains("<source"));
        assertTrue(clean.contains("sizes=\"200px\""));
    }

    // ===== normalization, idempotency, non-mutation =====

    @Test void whitespaceAndSerializationAreNormalized() {
        assertEquals("<img sizes=\"100px, 200px\">",
            Jsoup.clean("<img sizes=\"  100px  ,   200px  \">", imgSizesSafelist()));
        assertEquals("<img sizes=\"(min-width: 600px) 200px, 100px\">",
            Jsoup.clean("<img sizes=\"  (min-width: 600px)   200px ,100px\">", imgSizesSafelist()));
    }

    @Test void noEmptyOrDuplicateOrHalfUpdatedEntries() {
        String clean = Jsoup.clean("<img sizes=\", 100px, , bad, 200px,,\">", imgSizesSafelist());
        assertEquals("<img sizes=\"100px, 200px\">", clean);
        assertFalse(clean.contains(",,"));
        assertFalse(clean.matches("(?s).*,\\s*,.*"));
    }

    @Test void cleaningIsIdempotentAndReparseStable() {
        String html = "<picture>"
            + "<source media=\"(min-width: 600px)\" srcset=\"http://e/a.jpg 600w\" sizes=\"(min-width: 600px) calc(100vw - 16px), bad, 50%\">"
            + "<img src=\"http://e/f.jpg\" sizes=\"(max-width: 400px) 100px, 200px, nope\">"
            + "</picture>";
        Cleaner cleaner = new Cleaner(pictureSafelist());
        Document once = cleaner.clean(Jsoup.parseBodyFragment(html, "http://example.com/"));
        Document twice = cleaner.clean(once);
        assertEquals(once.body().html(), twice.body().html());

        String onceHtml = once.body().html();
        Document reparsed = Jsoup.parse(onceHtml);
        assertEquals(onceHtml, cleaner.clean(reparsed).body().html());
        // picture hierarchy, source/img order, and entry order are preserved
        assertEquals("(min-width: 600px) calc(100vw - 16px), 50%",
            once.expectFirst("source").attr("sizes"));
        assertEquals("(max-width: 400px) 100px, 200px",
            once.expectFirst("img").attr("sizes"));
    }

    @Test void inputDocumentIsNotModified() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img sizes=\"100px, bad, 200px\"><img sizes=\"nope\">", "http://example.com/");
        Element first = dirty.expectFirst("img");
        String original = first.attr("sizes");
        new Cleaner(imgSizesSafelist()).clean(dirty);
        assertEquals(original, first.attr("sizes"));
        assertEquals("100px, bad, 200px", first.attr("sizes"));
        assertEquals("nope", dirty.select("img").get(1).attr("sizes"));
    }

    @Test void repeatedCleaningOfSameInputIsStable() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img sizes=\"(min-width: 600px) 100px, calc(100vw - 10px), 50%\">", "http://example.com/");
        Cleaner cleaner = new Cleaner(imgSizesSafelist());
        assertEquals(cleaner.clean(dirty).body().html(), cleaner.clean(dirty).body().html());
    }

    @Test void neverThrowsOnHostileInput() {
        String[] hostile = {
            "", " ", ",", ",,,", " , , ",
            "(", ")", "()", "(((", ")))",
            "calc(", "calc()", "calc(100px -",
            "(a,b,c", "(a), (b), (c",
            "100px;", "100px{}", "100px &#x00;",
            "calc(100px; 200px)", "calc(100px {})",
            "javascript:alert(1)// 100px",
            "(min-width: 600px)) 100px, 200px",
        };
        Cleaner cleaner = new Cleaner(pictureSafelist());
        for (String value : hostile) {
            Document doc = Jsoup.parseBodyFragment(
                "<picture><source sizes='" + value + "'></source><img sizes='" + value + "'></picture>",
                "http://example.com/");
            Document clean = assertDoesNotThrow(() -> cleaner.clean(doc));
            // no surviving sizes may carry a script fragment or declaration
            clean.select("[sizes]").forEach(el -> {
                String v = el.attr("sizes").toLowerCase();
                assertFalse(v.contains("javascript"), value);
                assertFalse(v.contains(";") || v.contains("{") || v.contains("}"), value);
            });
        }
    }

    @Test void isValidReflectsDroppedEntries() {
        Cleaner cleaner = new Cleaner(imgSizesSafelist());
        assertTrue(cleaner.isValid(Jsoup.parseBodyFragment("<img sizes=\"100px\">", "http://example.com/")));
        assertFalse(cleaner.isValid(Jsoup.parseBodyFragment("<img sizes=\"100px, bad\">", "http://example.com/")));
        assertFalse(cleaner.isValid(Jsoup.parseBodyFragment("<img sizes=\"nope\">", "http://example.com/")));
    }
}
