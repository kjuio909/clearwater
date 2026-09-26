package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for the {@code An+B of S} extension to {@code :nth-child()} and {@code :nth-last-child()}: positions are
 computed against the filtered set of element siblings matching S.
 */
public class NthChildOfSelectorTest {

    private Document mixedList() {
        // li items, some classed "hit", with a text node sprinkled in (non-element nodes must not take a position)
        return Jsoup.parse(
            "<ul id=list>" +
                "<li class='hit'>a</li>" +       // filtered pos 1 / last 5
                "<li>skip</li>" +
                "<li class='hit'>b</li>" +       // filtered pos 2 / last 4
                " text " +
                "<li class='hit other'>c</li>" + // filtered pos 3 / last 3
                "<li class='nope'>skip2</li>" +
                "<li class='hit'>d</li>" +       // filtered pos 4 / last 2
                "<li class='hit'>e</li>" +       // filtered pos 5 / last 1
            "</ul>" +
            "<ul id=other><li class='hit'>x1</li><li class='hit'>x2</li></ul>"
        );
    }

    @Test void nthChildConstantOf() {
        Document doc = mixedList();
        assertTexts(doc.select("#list :nth-child(1 of .hit)"), "a");
        assertTexts(doc.select("#list :nth-child(2 of li.hit)"), "b");
        assertTexts(doc.select("#list :nth-child(3 of .hit)"), "c");
        assertTexts(doc.select("#list :nth-child(5 of .hit)"), "e");
        assertTrue(doc.select("#list :nth-child(6 of .hit)").isEmpty());
    }

    @Test void nthLastChildConstantOf() {
        Document doc = mixedList();
        assertTexts(doc.select("#list :nth-last-child(1 of .hit)"), "e");
        assertTexts(doc.select("#list :nth-last-child(2 of .hit)"), "d");
        assertTexts(doc.select("#list :nth-last-child(5 of .hit)"), "a");
        assertTrue(doc.select("#list :nth-last-child(6 of .hit)").isEmpty());

        // forward and reverse positions stay consistent: filter renumbers both ways
        assertTexts(doc.select("#list :nth-last-child(3 of .hit)"), "c");
    }

    @Test void nthChildOddEvenLinearOf() {
        Document doc = mixedList();
        assertTexts(doc.select("#list :nth-child(odd of .hit)"), "a", "c", "e");
        assertTexts(doc.select("#list :nth-child(even of .hit)"), "b", "d");
        assertTexts(doc.select("#list :nth-child(2n+1 of .hit)"), "a", "c", "e");
        assertTexts(doc.select("#list :nth-child(2n of .hit)"), "b", "d");
        assertTexts(doc.select("#list :nth-child(3n+1 of .hit)"), "a", "d");
        assertTexts(doc.select("#list :nth-last-child(-n+2 of .hit)"), "d", "e");
        assertTexts(doc.select("#list :nth-child(-n+2 of .hit)"), "a", "b");
    }

    @Test void nthChildWhitespaceAndCaseOf() {
        Document doc = mixedList();
        assertTexts(doc.select("#list :nth-child( 2n + 1 of .hit )"), "a", "c", "e");
        assertTexts(doc.select("#list :nth-child(2 OF .hit)"), "b");
        assertTexts(doc.select("#list :nth-child(odd   of   li[class~=hit])"), "a", "c", "e");
    }

    @Test void negativeExpressionsDontMatchOutOfBounds() {
        Document doc = mixedList();
        // plain negative constant never matches (positions start at 1)
        assertTrue(doc.select("#list :nth-child(-5 of .hit)").isEmpty());
        assertTrue(doc.select("#list :nth-last-child(-5 of .hit)").isEmpty());
        // -n step never matches
        assertTrue(doc.select("#list :nth-child(-1n of .hit)").isEmpty());
    }

    @Test void nonElementNodesDoNotTakePositions() {
        // the " text " node between b and c must not shift positions
        Document doc = Jsoup.parse(
            "<div><span class=x>1</span> <!-- comment --> <span class=x>2</span></div>");
        assertTexts(doc.select("div :nth-child(2 of .x)"), "2");
        assertTexts(doc.select("div :nth-last-child(1 of .x)"), "2");
    }

    @Test void selectorListInOfCommasScopedToPseudo() {
        Document doc = Jsoup.parse(
            "<ul>" +
                "<li class=a>1</li>" +
                "<li class=b>2</li>" +
                "<li class=c>3</li>" +
                "<li class=a>4</li>" +
            "</ul>");
        // comma in of S is part of the pseudo argument, not a top-level selector separator
        // filtered set is li1(a)=1, li2(b)=2, li4(a)=3
        assertTexts(doc.select("ul :nth-child(2 of .a, .b)"), "2");
        assertTexts(doc.select("ul :nth-last-child(1 of .a, .b)"), "4");
    }

    @Test void commasInAttributeValuesDoNotEndOfClause() {
        Document doc = Jsoup.parse(
            "<ul>" +
                "<li data-x='1,2'>a1</li>" +
                "<li data-x='z'>skip</li>" +
                "<li data-x='1,2'>a2</li>" +
            "</ul>");
        assertTexts(doc.select("ul :nth-child(2 of li[data-x='1,2'])"), "a2");
        // commas inside a nested pseudo's own selector list must not end the of argument either
        assertTexts(doc.select("ul :nth-child(2 of li:is([data-x=z], [data-x='1,2']))"), "skip");
    }

    @Test void nestedPseudoAndNotInOf() {
        Document doc = Jsoup.parse(
            "<ul>" +
                "<li><p>x</p></li>" +                 // 1 of :has(p)
                "<li class=skip>skip</li>" +
                "<li><p>y</p></li>" +                 // 2 of :has(p)
                "<li><p>z</p><em>m</em></li>" +       // 3 of :has(p) and not :has(em)
            "</ul>");
        assertTexts(doc.select("ul :nth-child(2 of li:has(p))"), "y");
        // nested :not with its own selector list — inner commas and parens must not end the of arg
        assertTexts(doc.select("ul :nth-child(2 of li:has(p):not(:has(em), .skip))"), "y");
        // comma after the nested pseudo still delimits the of selector list
        // S matches li2 (.skip, pos 1) and li4 (:has(em), pos 2) — pos 1 selects only li2
        Elements crossList = doc.select("ul :nth-child(1 of li:has(em), li.skip)");
        assertEquals(1, crossList.size());
        assertEquals("skip", crossList.get(0).text());
    }

    @Test void combinatorsAllowedInOf() {
        Document doc = Jsoup.parse(
            "<section>" +
                "<div><em class=t>1</em><span>x</span><em class=t>2</em></div>" +
            "</section>");
        assertTexts(doc.select("section :nth-child(2 of div > em.t)"), "2");
        assertTexts(doc.select("section :nth-last-child(1 of div > em)"), "2");
    }

    @Test void outerSelectorIntersectsWithOfPosition() {
        Document doc = Jsoup.parse(
            "<ul>" +
                "<li class='a hit'>1</li>" +
                "<li class='b hit'>2</li>" +
                "<li class='a hit'>3</li>" +
            "</ul>");
        // among .hit the positions are 1,2,3; outer .a intersects
        assertTexts(doc.select("ul li.a:nth-child(2 of .hit)")); // pos 2 is class b
        assertTexts(doc.select("ul li.a:nth-child(3 of .hit)"), "3");
    }

    @Test void combinesWithChildAdjacentAndNot() {
        Document doc = Jsoup.parse(
            "<ol>" +
                "<li class=h>1</li>" +
                "<li class=h>2</li>" +
                "<li class=h>3</li>" +
            "</ol>");
        assertTexts(doc.select("ol > :nth-child(2 of .h)"), "2");
        assertTexts(doc.select("ol .h + .h:nth-child(3 of .h)"), "3"); // adjacent and nth
        assertTexts(doc.select("ol :nth-child(2 of .h):not(:first-child)"), "2");
    }

    @Test void eachParentCountedIndependently() {
        Document doc = Jsoup.parse(
            "<div id=a><span class=x>a1</span><span class=x>a2</span></div>" +
            "<div id=b><span class=x>b1</span><span class=x>b2</span><span class=x>b3</span></div>");
        assertTexts(doc.select("body :nth-child(1 of .x)"), "a1", "b1");
        assertTexts(doc.select("body :nth-last-child(1 of .x)"), "a2", "b3");
    }

    @Test void descendantsAndRootScopeNotCounted() {
        Document doc = Jsoup.parse(
            "<div id=root><span class=x>root1<span class=x>inner</span></span><span class=x>root2</span></div>");
        Elements els = doc.select("#root > :nth-child(2 of .x)");
        assertTexts(els, "root2"); // only the direct child spans are siblings; inner is under root1
    }

    @Test void noMatchesGivesDeterministicEmptyResult() {
        Document doc = mixedList();
        assertTrue(doc.select("#list :nth-child(1 of .does-not-exist)").isEmpty());
        assertTrue(doc.select("#list :nth-last-child(2 of .does-not-exist)").isEmpty());
    }

    @Test void documentOrderAndNoDuplicates() {
        Document doc = Jsoup.parse(
            "<ul>" +
                "<li class='a b'>1</li>" +
                "<li class='a'>2</li>" +
                "<li class='b'>3</li>" +
            "</ul>");
        // first list item matches both branches of the of selector list — must be returned once, in doc order
        Elements els = doc.select("ul :nth-child(odd of .a, .b)");
        assertEquals(2, els.size());
        assertEquals("1", els.get(0).text());
        assertEquals("3", els.get(1).text());
    }

    @Test void domMutationsReflectedImmediately() {
        Document doc = mixedList();
        Evaluator eval = Selector.evaluatorOf("#list :nth-child(2 of .hit)");
        assertTexts(doc.select(eval), "b");

        // remove an earlier matching sibling: filtered positions renumber
        doc.select("#list .hit").first().remove(); // removes a; hits now b,c,d,e
        assertTexts(doc.select(eval), "c");

        // change the attribute used by S: c drops out of the set, d becomes position 2
        doc.select("#list .hit").get(1).removeAttr("class"); // b idx0, c idx1
        assertTexts(doc.select(eval), "d");

        // insert a fresh matching sibling at the front: it takes position 1
        Element list = doc.expectFirst("#list");
        list.prependChild(new Element("li").addClass("hit").text("z"));
        assertTexts(doc.select(eval), "b");

        // the reverse query is recomputed the same way against the live DOM
        Evaluator lastEval = Selector.evaluatorOf("#list :nth-last-child(1 of .hit)");
        assertTexts(doc.select(lastEval), "e");
    }

    @Test void plainNthChildBehaviorUnchanged() {
        Document doc = Jsoup.parse("<div><p>1</p><p>2</p><p>3</p></div>");
        assertTexts(doc.select("div :nth-child(2)"), "2");
        assertTexts(doc.select("div :nth-last-child(2)"), "2");
        assertTexts(doc.select("div :nth-child(odd)"), "1", "3");
    }

    @Test void parseErrorsDoNotReturnPartialResultsOrMutateDom() {
        Document doc = mixedList();
        String html = doc.html();

        assertParseError(":nth-child(of .hit)");            // missing formula
        assertParseError(":nth-child(1 of)");               // missing selector
        assertParseError(":nth-child(1 of ");               // missing closing paren
        assertParseError(":nth-child(1 of .hit");           // unbalanced
        assertParseError(":nth-child(bogus of .hit)");      // invalid formula
        assertParseError(":nth-of-type(1 of .hit)");        // of not valid for nth-of-type
        assertParseError(":nth-last-of-type(1 of .hit)");

        // DOM untouched
        assertEquals(html, doc.html());

        // and the next valid query works against the full current DOM
        assertTexts(doc.select("#list :nth-child(2 of .hit)"), "b");
    }

    @Test void parsedEvaluatorToString() {
        assertEquals(":nth-child(2n+1 of .hit)", Selector.evaluatorOf(":nth-child(odd of .hit)").toString());
        assertEquals(":nth-last-child(2 of li.x)", Selector.evaluatorOf(":nth-last-child(2 of li.x)").toString());
    }

    private void assertParseError(String css) {
        assertThrows(Selector.SelectorParseException.class, () -> Selector.evaluatorOf(css),
            () -> "Expected parse error for: " + css);
    }

    private static void assertTexts(Elements els, String... texts) {
        assertEquals(texts.length, els.size(),
            () -> "Expected " + texts.length + " elements but got " + els.size() + ": " + els.outerHtml());
        for (int i = 0; i < texts.length; i++) {
            String expected = texts[i];
            String actual = expected.startsWith("<") ? els.get(i).outerHtml() : els.get(i).text();
            assertEquals(expected, actual, "Element " + i);
        }
    }
}
