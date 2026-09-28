package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for per-entry cleaning of {@code sizes} attributes in the {@link Cleaner}, on {@code img} and {@code source}
 elements.
 */
public class SizesCleanerTest {

    private static Safelist safelist() {
        return Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset", "sizes")
            .addAttributes("source", "srcset", "sizes", "media", "type")
            .addProtocols("img", "srcset", "http", "https")
            .addProtocols("source", "srcset", "http", "https");
    }

    // ===== plain lengths =====

    @Test void plainLengthsAreKept() {
        String[] values = {"100vw", "0px", "1.5em", ".5rem", "50%", "12.0ch", "7ex", "100vmin", "100vmax"};
        for (String value : values) {
            assertEquals("<img sizes=\"" + value + "\">",
                Jsoup.clean("<img sizes='" + value + "'>", safelist()), value);
        }
    }

    @Test void multipleEntriesAreStablyJoined() {
        String clean = Jsoup.clean("<img sizes=' 100vw  ,  50vw ,  25vw '>", safelist());
        assertEquals("<img sizes=\"100vw, 50vw, 25vw\">", clean);
    }

    @Test void leadingAndTrailingWhitespaceAndEmptyEntriesAreRemoved() {
        assertEquals("<img sizes=\"100vw\">", Jsoup.clean("<img sizes='   100vw   '>", safelist()));
        assertEquals("<img sizes=\"100vw\">", Jsoup.clean("<img sizes=',,100vw,,,'>", safelist()));
        assertEquals("<img sizes=\"100vw, 50vw\">", Jsoup.clean("<img sizes='  , 100vw , , 50vw , '>", safelist()));
    }

    @Test void noValidEntryRemovesTheAttribute() {
        String[] values = {"", "   ", ",,,", "100", "abc", "100pxx", "100CM", "-100vw", "100 vw", "1e2vw"};
        for (String value : values) {
            assertEquals("<img>", Jsoup.clean("<img sizes='" + value + "'>", safelist()), value);
        }
    }

    // ===== media conditions =====

    @Test void mediaConditionEntryIsKept() {
        String clean = Jsoup.clean("<img sizes='(min-width: 30em) 50vw, 100vw'>", safelist());
        assertEquals("<img sizes=\"(min-width: 30em) 50vw, 100vw\">", clean);
    }

    @Test void compoundMediaConditionIsKept() {
        String clean = Jsoup.clean(
            "<img sizes='(min-width: 30em) and (orientation: landscape) 100vw, 50vw'>", safelist());
        assertEquals("<img sizes=\"(min-width: 30em) and (orientation: landscape) 100vw, 50vw\">", clean);
    }

    @Test void comparisonOperatorsAndNestedParensAreKept() {
        String clean = Jsoup.clean("<img sizes='(width >= 600px) and (100px <= width < 1000px) 80vw'>", safelist());
        // < and > are HTML-escaped on serialization, so assert the parsed attribute value rather than the markup
        String sizes = Jsoup.parse(clean).expectFirst("img").attr("sizes");
        assertEquals("(width >= 600px) and (100px <= width < 1000px) 80vw", sizes);
    }

    @Test void malformedMediaConditionsDropOnlyThatEntry() {
        // empty condition
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='() 50vw, 100vw'>", safelist()));
        // condition with no length after it
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 30em), 100vw'>", safelist()));
        // condition glued to the length without whitespace
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 30em)50vw, 100vw'>", safelist()));
        // forbidden punctuation in the condition
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 300px); 50vw, 100vw'>", safelist()));
        // unbalanced condition
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 30em 50vw, 100vw'>", safelist()));
        // stray close paren
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 30em) ) 50vw, 100vw'>", safelist()));
    }

    @Test void scriptLookingConditionIsRejected() {
        // a script scheme smuggled into the media condition cannot survive, even with inner whitespace
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(x: java&Tab;script:alert(1)) 50vw, 100vw'>", safelist()));
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(x:vbscript:msgbox(1)) 50vw, 100vw'>", safelist()));
        // a url() or data: fragment uses only allowed characters but is never part of a size entry
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(x: url(http://evil.test/)) 50vw, 100vw'>", safelist()));
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='calc(url(javascript:x)) 50vw, 100vw'>", safelist()));
    }

    @Test void duplicateMediaConditionsAreKeptInOrder() {
        String clean = Jsoup.clean("<img sizes='(min-width: 30em) 50vw, (min-width: 30em) 100vw'>", safelist());
        assertEquals("<img sizes=\"(min-width: 30em) 50vw, (min-width: 30em) 100vw\">", clean);
    }

    // ===== calc =====

    @Test void calcLengthsAreKept() {
        assertEquals("<img sizes=\"calc(100vw - 20px)\">",
            Jsoup.clean("<img sizes='calc(100vw - 20px)'>", safelist()));
        assertEquals("<img sizes=\"calc(50% + 2em)\">",
            Jsoup.clean("<img sizes='calc(50% + 2em)'>", safelist()));
        assertEquals("<img sizes=\"(min-width: 30em) calc(100vw - 2 * 10px)\">",
            Jsoup.clean("<img sizes='(min-width: 30em) calc(100vw - 2 * 10px)'>", safelist()));
        assertEquals("<img sizes=\"CALC(100VW - 20PX)\">",
            Jsoup.clean("<img sizes='CALC(100VW - 20PX)'>", safelist()));
        assertEquals("<img sizes=\"calc(100vw - calc(20px + 1em))\">",
            Jsoup.clean("<img sizes='calc(100vw - calc(20px + 1em))'>", safelist()));
    }

    @Test void malformedCalcDropsOnlyThatEntry() {
        String[] bad = {
            "calc(100vw - 20px",        // unclosed
            "calc(100vw - 20px))",      // stray close
            "calc()",                   // empty
            "calc(-100vw)",             // leading minus
            "calc(url(http://x))",      // url token
            "calc(100vw;",              // semicolon
            "calc(100vw + )",           // missing operand
            "calc(100vw ** 20px)",      // doubled operator
            "calc(100vw cm 20px)",      // unknown unit
            "calc(100vw - 20px){}",     // brace
        };
        for (String value : bad) {
            assertEquals("<img sizes=\"100vw\">",
                Jsoup.clean("<img sizes='" + value + ", 100vw'>", safelist()), value);
        }
    }

    @Test void commasInsideParensDoNotSplitEntries() {
        // a comma inside a balanced group is entry content: the whole (illegal-comma) entry is dropped, and the
        // following legal entry survives
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes='(min-width: 1px, 2px) 50vw, 100vw'>", safelist()));
        // an unclosed group cannot swallow later chunks: parsing resyncs at its first inner comma, so the trailing
        // chunks are reparsed on their own
        assertEquals("<img sizes=\"50vw, 100vw\">",
            Jsoup.clean("<img sizes='(min-width: 30em, 50vw, 100vw'>", safelist()));
        assertEquals("<img sizes=\"50vw, 100vw\">",
            Jsoup.clean("<img sizes='calc(100vw, 50vw, 100vw'>", safelist()));
    }

    // ===== quoting =====

    @Test void delimitingQuotesAreStripped() {
        // the attribute itself is usually quoted by the markup; here the value carries extra delimiting quotes
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes=\"'100vw'\">", safelist()));
        assertEquals("<img sizes=\"(min-width: 30em) 50vw, 100vw\">",
            Jsoup.clean("<img sizes='\"(min-width: 30em) 50vw, 100vw\"'>", safelist()));
        assertEquals("<img sizes=\"50vw, 100vw\">",
            Jsoup.clean("<img sizes=\"'50vw', '100vw'\">", safelist()));
    }

    // ===== source / img parity =====

    @Test void sameSizesValueCleansIdenticallyOnSourceAndImg() {
        String[] values = {
            "100vw",
            "(min-width: 30em) 50vw, 100vw",
            "(min-width: 30em) and (orientation: landscape) 100vw, 50vw, 25vw",
            "calc(100vw - 20px), 50vw, 100vw",
            "  , (width >= 600px) 80vw, , 100vw, ",
            "() 50vw, (min-width: 30em 50vw, 100vw, calc(), 25vw",
            "'(min-width: 30em) 50vw', 100vw",
        };
        Safelist sl = safelist();
        for (String value : values) {
            String img = Jsoup.clean("<img sizes='" + value + "'>", sl);
            String source = Jsoup.clean("<source sizes='" + value + "'>", sl);
            assertEquals(img.replaceFirst("^<img", "<source"), source,
                "sizes cleaned differently on source vs img: " + value);
        }
    }

    @Test void pictureSourceAndImgAreEachHandledIndependently() {
        String html = "<picture>"
            + "<source media=\"(min-width: 800px)\" type=\"image/webp\" sizes=\"garbage\" srcset=\"http://x/l.webp 800w\">"
            + "<source media=\"(min-width: 400px)\" sizes=\"(min-width: 400px) 100vw\" srcset=\"http://x/m.jpg 400w\">"
            + "<img sizes=\"(min-width: 800px) 50vw, 100vw\" srcset=\"http://x/s.jpg 200w\" alt=\"x\">"
            + "</picture>";
        String clean = Jsoup.clean(html, "http://x/", safelist());
        assertEquals("<picture>"
            + "<source media=\"(min-width: 800px)\" type=\"image/webp\" srcset=\"http://x/l.webp 800w\">"
            + "<source media=\"(min-width: 400px)\" sizes=\"(min-width: 400px) 100vw\" srcset=\"http://x/m.jpg 400w\">"
            + "<img sizes=\"(min-width: 800px) 50vw, 100vw\" srcset=\"http://x/s.jpg 200w\" alt=\"x\">"
            + "</picture>", clean);
    }

    // ===== whitelist gating =====

    @Test void sizesRemovedWhenNotWhitelisted() {
        // sizes not in the attribute whitelist: the attribute is removed, srcset/src untouched
        Safelist sl = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset", "media", "type");
        String clean = Jsoup.clean(
            "<picture><source sizes=\"100vw\" media=\"(min-width: 1px)\" srcset=\"http://x/a.jpg 1x\">"
                + "<img sizes=\"100vw\" srcset=\"http://x/b.jpg 1x\"></picture>", "http://x/", sl);
        assertEquals("<picture>"
            + "<source media=\"(min-width: 1px)\" srcset=\"http://x/a.jpg 1x\">"
            + "<img srcset=\"http://x/b.jpg 1x\">"
            + "</picture>", clean);
    }

    @Test void sizesOnOtherElementsIsNotSpeciallyHandled() {
        // a sizes attribute on a non-image element is neither special-cleaned nor auto-allowed; with a whitelist
        // that permits it on a div, it passes through as an ordinary attribute (no special handling, no removal logic)
        Safelist sl = Safelist.relaxed().addAttributes("div", "sizes");
        String clean = Jsoup.clean("<div sizes=\"whatever\"></div>", sl);
        // ordinary opaque attributes are kept verbatim
        assertEquals("<div sizes=\"whatever\"></div>", clean);
    }

    @Test void sizesDoesNotWidenSrcsetProtocolChecks() {
        // a valid sizes cannot save an unsafe srcset candidate on either element
        assertEquals("<img sizes=\"100vw\">",
            Jsoup.clean("<img sizes=\"100vw\" srcset=\"javascript:alert(1) 1x\">", safelist()));
        assertEquals("<source sizes=\"100vw\">",
            Jsoup.clean("<source sizes=\"100vw\" srcset=\"javascript:alert(1) 1x\">", safelist()));
    }

    @Test void sourcePolicyDoesNotLeakToImg() {
        // only source carries a sizes whitelist; the img's sizes is removed while source's is kept
        Safelist sl = Safelist.relaxed()
            .addTags("picture", "source")
            .addAttributes("img", "srcset")
            .addAttributes("source", "srcset", "sizes");
        String clean = Jsoup.clean(
            "<picture><source sizes=\"100vw\" srcset=\"http://x/a.jpg 1x\">"
                + "<img sizes=\"100vw\" srcset=\"http://x/b.jpg 1x\"></picture>", "http://x/", sl);
        assertEquals("<picture>"
            + "<source sizes=\"100vw\" srcset=\"http://x/a.jpg 1x\">"
            + "<img srcset=\"http://x/b.jpg 1x\">"
            + "</picture>", clean);
    }

    // ===== stability / non-mutation =====

    @Test void cleaningIsStableAcrossRepeatsAndReparse() {
        Cleaner cleaner = new Cleaner(safelist());
        String html = "<picture>"
            + "<source sizes=\"(min-width: 30em) 50vw, junk, 100vw\" srcset=\"http://x/a.jpg 1x, javascript:y 2x\">"
            + "<img sizes=\"calc(100vw - 20px), , 100vw\" srcset=\"http://x/b.jpg 2x\">"
            + "</picture>";
        Document once = cleaner.clean(Jsoup.parseBodyFragment(html));
        String expected = "<picture>"
            + "<source sizes=\"(min-width: 30em) 50vw, 100vw\" srcset=\"http://x/a.jpg 1x\">"
            + "<img sizes=\"calc(100vw - 20px), 100vw\" srcset=\"http://x/b.jpg 2x\">"
            + "</picture>";
        assertEquals(expected, once.body().html());
        assertEquals(expected, cleaner.clean(once).body().html());
        Document reparsed = Jsoup.parse(once.body().html());
        assertEquals(expected, cleaner.clean(reparsed).body().html());
    }

    @Test void inputDocumentIsNotModified() {
        Document dirty = Jsoup.parseBodyFragment(
            "<img sizes=\"100vw, garbage\" srcset=\"http://x/a.jpg 1x, javascript:y 2x\">");
        Element img = dirty.expectFirst("img");
        String sizesBefore = img.attr("sizes");
        String srcsetBefore = img.attr("srcset");
        new Cleaner(safelist()).clean(dirty);
        assertEquals(sizesBefore, img.attr("sizes"));
        assertEquals(srcsetBefore, img.attr("srcset"));
    }

    @Test void mixedValidAndInvalidProducesNoEmptyOrHalfUpdatedEntries() {
        // every surviving entry is complete; there are no empty slots and the raw bad spelling never appears
        String clean = Jsoup.clean(
            "<img sizes=\"50vw, bad, (min-width: 1px), calc(, 100vw, (min-width: 2px) 25vw\">", safelist());
        String sizes = Jsoup.parse(clean).expectFirst("img").attr("sizes");
        assertEquals("50vw, 100vw, (min-width: 2px) 25vw", sizes);
        assertFalse(sizes.contains("bad"));
        assertFalse(sizes.contains(",,") || sizes.contains(", ,"));
    }

    @Test void garbageValuesNeverInterruptCleaning() {
        String[] garbage = {
            "<img sizes=\"\u0000\u0001\u0007\" srcset=\"http://x/a.jpg 1x\">",
            "<img sizes=\"{{7*7}}\"><p>hello</p>",
            "<img sizes=\";\"><p>hello</p>",
            "<picture><source sizes=\"((((\"><img sizes=\"))))\" alt=\"ok\"></picture>",
        };
        Safelist sl = safelist();
        for (String html : garbage) {
            Document clean = assertDoesNotThrow(() -> new Cleaner(sl).clean(Jsoup.parseBodyFragment(html)));
            // a surviving sizes value must never contain rejected punctuation or a control character
            clean.select("[sizes]").forEach(el -> {
                String v = el.attr("sizes");
                assertFalse(v.contains(";") || v.contains("{") || v.contains("}"));
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    assertTrue(c > 0x1f && c != 0x7f);
                }
            });
        }
    }
}
