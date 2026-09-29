package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.integration.TestServer;
import org.jsoup.integration.routes.CookieRoute;
import org.jsoup.select.Elements;
import org.jsoup.select.SelectorTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FormElement
 *
 * @author Jonathan Hedley
 */
public class FormElementTest {
    @BeforeAll
    public static void setUp() {
        TestServer.start();
    }

    @Test public void hasAssociatedControls() {
        //"button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
        String html = "<form id=1><button id=1><fieldset id=2 /><input id=3><keygen id=4><object id=5><output id=6>" +
                "<select id=7><option></select><textarea id=8><p id=9>";
        Document doc = Jsoup.parse(html);

        FormElement form = (FormElement) doc.select("form").first();
        assertEquals(8, form.elements().size());
    }

    @Test public void createsFormData() {
        String html = "<form><input name='one' value='two'><select name='three'><option value='not'>" +
                "<option value='four' selected><option value='five' selected><textarea name=six>seven</textarea>" +
                "<input name='seven' type='radio' value='on' checked><input name='seven' type='radio' value='off'>" +
                "<input name='eight' type='checkbox' checked><input name='nine' type='checkbox' value='unset'>" +
                "<input name='ten' value='text' disabled>" +
                "<input name='eleven' value='text' type='button'>" +
                "<input name='twelve' value='text' type='image'>" +
                "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();

        assertEquals(5, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString()); // single select: only the first selected option in DOM order
        assertEquals("six=seven", data.get(2).toString());
        assertEquals("seven=on", data.get(3).toString()); // set
        assertEquals("eight=on", data.get(4).toString()); // default
        // five is selected but later than four: no duplicate key for a single select
        // nine should not appear, not checked checkbox
        // ten should not appear, disabled
        // eleven should not appear, button
    }

    @Test public void formDataUsesFirstAttribute() {
        String html = "<form><input name=test value=foo name=test2 value=bar>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        assertEquals("test=foo", form.formData().get(0).toString());
    }

    @Test public void createsSubmitableConnection() {
        String html = "<form action='/search'><input name='q'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        doc.select("[name=q]").attr("value", "jsoup");

        FormElement form = ((FormElement) doc.select("form").first());
        Connection con = form.submit();

        assertEquals(Connection.Method.GET, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
        List<Connection.KeyVal> dataList = (List<Connection.KeyVal>) con.request().data();
        assertEquals("q=jsoup", dataList.get(0).toString());

        doc.select("form").attr("method", "post");
        Connection con2 = form.submit();
        assertEquals(Connection.Method.POST, con2.request().method());
    }

    @Test public void actionWithNoValue() {
        String html = "<form><input name='q'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = ((FormElement) doc.select("form").first());
        Connection con = form.submit();

        assertEquals("http://example.com/", con.request().url().toExternalForm());
    }

    @Test public void actionWithNoBaseUri() {
        String html = "<form><input name='q'></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = ((FormElement) doc.select("form").first());


        boolean threw = false;
        try {
            form.submit();
        } catch (IllegalArgumentException e) {
            threw = true;
            assertEquals("Could not determine a form action URL for submit. Ensure you set a base URI when parsing.",
                    e.getMessage());
        }
        assertTrue(threw);
    }

    @Test public void formsAddedAfterParseAreFormElements() {
        Document doc = Jsoup.parse("<body />");
        doc.body().html("<form action='http://example.com/search'><input name='q' value='search'>");
        Element formEl = doc.select("form").first();
        assertTrue(formEl instanceof FormElement);

        FormElement form = (FormElement) formEl;
        assertEquals(1, form.elements().size());
    }

    @Test public void controlsAddedAfterParseAreLinkedWithForms() {
        Document doc = Jsoup.parse("<body />");
        doc.body().html("<form />");

        Element formEl = doc.select("form").first();
        formEl.append("<input name=foo value=bar>");

        assertTrue(formEl instanceof FormElement);
        FormElement form = (FormElement) formEl;
        assertEquals(1, form.elements().size());

        List<Connection.KeyVal> data = form.formData();
        assertEquals("foo=bar", data.get(0).toString());
    }

    @Test public void usesOnForCheckboxValueIfNoValueSet() {
        Document doc = Jsoup.parse("<form><input type=checkbox checked name=foo></form>");
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();
        assertEquals("on", data.get(0).value());
        assertEquals("foo", data.get(0).key());
    }

    @Test public void adoptedFormsRetainInputs() {
        // test for https://github.com/jhy/jsoup/issues/249
        String html = "<html>\n" +
                "<body>  \n" +
                "  <table>\n" +
                "      <form action=\"/hello.php\" method=\"post\">\n" +
                "      <tr><td>User:</td><td> <input type=\"text\" name=\"user\" /></td></tr>\n" +
                "      <tr><td>Password:</td><td> <input type=\"password\" name=\"pass\" /></td></tr>\n" +
                "      <tr><td><input type=\"submit\" name=\"login\" value=\"login\" /></td></tr>\n" +
                "   </form>\n" +
                "  </table>\n" +
                "</body>\n" +
                "</html>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("user", data.get(0).key());
        assertEquals("pass", data.get(1).key());
        // login is a submit button, and so is not included in the form data
    }

    @Test public void removeFormElement() {
        String html = "<html>\n" +
                "  <body> \n" +
                "      <form action=\"/hello.php\" method=\"post\">\n" +
                "      User:<input type=\"text\" name=\"user\" />\n" +
                "      Password:<input type=\"password\" name=\"pass\" />\n" +
                "      <input type=\"submit\" name=\"login\" value=\"login\" />\n" +
                "   </form>\n" +
                "  </body>\n" +
                "</html>  ";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        Element pass = form.selectFirst("input[name=pass]");
        pass.remove();

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("user", data.get(0).key());
        // login is a submit button, and so is not included in the form data
        assertNull(doc.selectFirst("input[name=pass]"));
    }

    @Test public void formSubmissionCarriesCookiesFromSession() throws IOException {
        String echoUrl = TestServer.origin().echo.url();
        Document cookieDoc = Jsoup.connect(TestServer.origin().cookie.url())
            .data(CookieRoute.SetCookiesParam, "1")
            .get();
        Document formDoc = cookieDoc.connection().newRequest() // carries cookies from above set
            .url(TestServer.origin().file.url("/htmltests/upload-form.html"))
            .get();
        FormElement form = formDoc.select("form").forms().get(0);
        Document echo = form.submit().post();

        assertEquals(echoUrl, echo.location());
        Elements els = echo.select("th:contains(Cookie: One)");
        // ensure that the cookies are there and in path-specific order (two with same name)
        assertEquals("Echo", els.get(0).nextElementSibling().text());
        assertEquals("Root", els.get(1).nextElementSibling().text());

        // make sure that the session following kept unique requests
        assertTrue(cookieDoc.connection().response().url().toExternalForm().contains("Cookie"));
        assertTrue(formDoc.connection().response().url().toExternalForm().contains("upload-form"));
        assertTrue(echo.connection().response().url().toExternalForm().contains("Echo"));
    }

    @Test void formElementsAreLive() {
        final String html = "<html><body><form><div id=d1><input id=foo name=foo value=none></div><input id=bar name=bar value=one></form></body></html>";
        final Document doc = Jsoup.parse(html);
        doc.select("#d1").remove();
        final FormElement form = (FormElement) doc.selectFirst("form");
        form.appendElement("input").attr("id", "baz").attr("name", "baz").attr("value", "two");
        SelectorTest.assertSelectedIds(form.elements(), "bar", "baz");

        List<Connection.KeyVal> keyVals = form.formData();
        assertEquals("one", keyVals.get(0).value());
        assertEquals("two", keyVals.get(1).value());
    }

    @Test void multipleSelectSubmitsAllSelectedOptions() {
        String html = "<form><select name=multi multiple>" +
            "<option value=a selected><option value=b><option value=c selected><option value=d selected disabled>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("multi=a", data.get(0).toString());
        assertEquals("multi=c", data.get(1).toString());
        // b is not selected; d is disabled
    }

    @Test void multipleSelectWithNoSelectionSubmitsNothing() {
        String html = "<form><select name=multi multiple><option value=a><option value=b></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        assertEquals(0, form.formData().size());
    }

    @Test void singleSelectDefaultsToFirstEnabledOption() {
        String html = "<form><select name=one><option value=a disabled><option value=b><option value=c selected></select>" +
            "<select name=two><option value=d disabled><option value=e></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("one=c", data.get(0).toString()); // explicit selection wins
        assertEquals("two=e", data.get(1).toString()); // no selection: first non-disabled option
    }

    @Test void disabledOptgroupDisablesSelectedOptions() {
        String html = "<form><select name=multi multiple>" +
            "<option value=a selected>" +
            "<optgroup disabled><option value=b selected><option value=c></optgroup>" +
            "<optgroup><option value=d selected></optgroup>" +
            "<option value=e selected disabled>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("multi=a", data.get(0).toString());
        assertEquals("multi=d", data.get(1).toString());
        // b is in a disabled optgroup; e is disabled itself
    }

    @Test void singleSelectFallsBackPastDisabledOptgroup() {
        String html = "<form>" +
            "<select name=one><optgroup disabled><option value=a selected></optgroup><option value=b></select>" +
            "<select name=two><optgroup disabled><option value=c><option value=d selected></optgroup></select>" +
            "<select name=three><optgroup disabled><option value=e selected></optgroup><optgroup><option value=f></optgroup></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("one=b", data.get(0).toString()); // selected a is optgroup-disabled: falls back to first enabled
        assertEquals("three=f", data.get(1).toString());
        // two has no enabled option at all: the field does not appear
    }

    @Test void optgroupDisabledChangesAreReflectedOnEachRead() {
        String html = "<form><select name=sel><optgroup id=g><option value=a selected></optgroup><option value=b></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        assertEquals("sel=a", form.formData().get(0).toString());

        doc.selectFirst("optgroup").attr("disabled", "");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("sel=b", data.get(0).toString()); // a now disabled via optgroup: falls back

        doc.selectFirst("optgroup").removeAttr("disabled");
        assertEquals("sel=a", form.formData().get(0).toString());

        // mutating the returned list must not affect subsequent reads
        data.clear();
        assertEquals(1, form.formData().size());
    }

    @Test void singleSelectSubmitsOnlyFirstSelectedEnabledOption() {
        String html = "<form><select name=s>" +
            "<option value=a selected><option value=b selected><option value=c selected>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size()); // later selected options must not produce duplicate key-values
        assertEquals("s=a", data.get(0).toString());
    }

    @Test void singleSelectFallbackOnlyWhenEverySelectedIsDisabled() {
        // a selected-but-disabled option before an enabled selected option does not trigger the fallback:
        // the first *enabled* selected option wins
        String html = "<form><select name=one>" +
            "<option value=a selected disabled><option value=b><option value=c selected>" +
            "</select>" +
            "<select name=two>" +
            "<option value=d selected disabled><option value=e selected disabled><option value=f>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("one=c", data.get(0).toString()); // b (unselected fallback) is skipped because c is selected and enabled
        assertEquals("two=f", data.get(1).toString()); // every selected option is disabled: fall back to first enabled
    }

    @Test void singleSelectFallbackSkipsSelfAndOptgroupDisabledOptions() {
        String html = "<form><select name=s>" +
            "<option selected disabled value=a>" +
            "<optgroup disabled><option value=b><option value=c selected></optgroup>" +
            "<optgroup><option value=d></optgroup>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("s=d", data.get(0).toString()); // a self-disabled, b/c group-disabled: first enabled is d
    }

    @Test void selectStructureMutationsAreReflectedOnEachRead() {
        String html = "<form><select id=s name=s>" +
            "<option id=oa value=a selected><option id=ob value=b selected>" +
            "</select><input name=after value=z></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        Element select = doc.selectFirst("#s");
        Element oa = doc.selectFirst("#oa");
        Element ob = doc.selectFirst("#ob");

        // single select initially: only the first selected option
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("s=a", data.get(0).toString());
        assertEquals("after=z", data.get(1).toString());

        // toggling multiple immediately changes how many key-values the field yields
        select.attr("multiple", "");
        data = form.formData();
        assertEquals(3, data.size());
        assertEquals("s=a", data.get(0).toString());
        assertEquals("s=b", data.get(1).toString()); // duplicate names are kept, in DOM order
        select.removeAttr("multiple");
        assertEquals("s=a", form.formData().get(0).toString());

        // moving b ahead of a makes b the first selected option
        select.insertChildren(0, ob);
        data = form.formData();
        assertEquals("s=b", data.get(0).toString());

        // disabling the control drops its whole field, but later fields still submit
        select.attr("disabled", "");
        data = form.formData();
        assertEquals(1, data.size());
        assertEquals("after=z", data.get(0).toString());
        select.removeAttr("disabled");

        // renaming and changing the selected option is honored immediately
        select.attr("name", "renamed");
        ob.removeAttr("selected");
        data = form.formData();
        assertEquals(2, data.size());
        assertEquals("renamed=a", data.get(0).toString()); // oa is now first selected (ob precedes it but isn't selected)
        assertEquals("after=z", data.get(1).toString());
    }

    @Test void movingOptionAcrossOptgroupsChangesItsSubmittability() {
        String html = "<form><select name=s multiple>" +
            "<optgroup id=off disabled><option id=oa value=a selected></optgroup>" +
            "<optgroup id=on><option id=ob value=b selected></optgroup>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("s=b", data.get(0).toString()); // a is disabled through its optgroup

        // move a into the enabled group: it becomes submittable immediately
        doc.selectFirst("#on").appendChild(doc.selectFirst("#oa"));
        data = form.formData();
        assertEquals(2, data.size());
        assertEquals("s=b", data.get(0).toString()); // document order: b's group is now entirely ahead of a
        assertEquals("s=a", data.get(1).toString());

        // move b into the disabled group: only a remains, and no extra key-values appear
        doc.selectFirst("#off").appendChild(doc.selectFirst("#ob"));
        data = form.formData();
        assertEquals(1, data.size());
        assertEquals("s=a", data.get(0).toString());
    }

    @Test void mixedControlsWithSelectStayInDocumentOrder() {
        String html = "<form>" +
            "<input name=i value=1>" +
            "<select name=s multiple><option value=a selected><option value=b selected></select>" +
            "<select name=t><option value=x selected><option value=y selected></select>" +
            "<textarea name=u>txt</textarea>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();

        assertEquals(5, data.size());
        assertEquals("i=1", data.get(0).toString());
        assertEquals("s=a", data.get(1).toString());
        assertEquals("s=b", data.get(2).toString());
        assertEquals("t=x", data.get(3).toString()); // single select contributes one value amid the others
        assertEquals("u=txt", data.get(4).toString());
    }

    @Test void emptyOptionValueDoesNotInterruptLaterFields() {
        String html = "<form>" +
            "<select name=s><option selected value=''>empty</option><option value=b></select>" +
            "<input name=n value=2>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();

        assertEquals(2, data.size());
        assertEquals("s", data.get(0).key());
        assertEquals("", data.get(0).value()); // the empty value is submitted, not skipped
        assertEquals("n=2", data.get(1).toString());
    }

    @Test void returnedSelectDataIsASnapshot() {
        String html = "<form><select name=s multiple><option value=a selected><option value=b selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        data.get(0).key("x").value("y");
        data.clear();

        List<Connection.KeyVal> again = form.formData();
        assertEquals(2, again.size());
        assertEquals("s=a", again.get(0).toString());
        assertEquals("s=b", again.get(1).toString());
        assertEquals("<option value=\"a\" selected></option>",
            form.selectFirst("option").outerHtml()); // reading did not rewrite the option
    }

    @Test void selectSubmissionSurvivesSerializationRoundTrip() {
        String html = "<form><select name=one>" +
            "<option value=a selected><option value=b selected>" +
            "<optgroup disabled><option value=c selected></optgroup>" +
            "</select>" +
            "<select name=two multiple>" +
            "<option value=d selected disabled><option value=e selected>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> before = form.formData();

        Document reparse = Jsoup.parse(doc.html());
        List<Connection.KeyVal> after = ((FormElement) reparse.selectFirst("form")).formData();

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).key(), after.get(i).key());
            assertEquals(before.get(i).value(), after.get(i).value());
        }
        assertEquals(2, after.size()); // one=a (single, first selected only); two=e (d disabled)
        assertEquals("one=a", after.get(0).toString());
        assertEquals("two=e", after.get(1).toString());
    }

    @Test void legendExceptedSelectStillFiltersDisabledOptgroup() {
        String html = "<form><fieldset disabled><legend>" +
            "<select name=s multiple><optgroup disabled><option value=a selected></optgroup><option value=b selected></select>" +
            "</legend></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("s=b", data.get(0).toString());
    }

    @Test void disabledFieldsetDisablesDescendantControls() {
        String html = "<form>" +
            "<fieldset disabled><input name=a value=1><select name=b><option value=2></select><textarea name=c>3</textarea></fieldset>" +
            "<input name=d value=4>" +
            "<fieldset><input name=e value=5></fieldset>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("d=4", data.get(0).toString());
        assertEquals("e=5", data.get(1).toString());
    }

    @Test void disabledFieldsetFirstLegendControlsRemainSubmittable() {
        String html = "<form>" +
            "<fieldset disabled>" +
            "<legend><input name=a value=1><select name=b><option value=2></select></legend>" +
            "<input name=c value=3>" +
            "<div><input name=d value=4></div>" +
            "</fieldset>" +
            "<input name=e value=5>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(3, data.size());
        assertEquals("a=1", data.get(0).toString());
        assertEquals("b=2", data.get(1).toString());
        assertEquals("e=5", data.get(2).toString());
    }

    @Test void onlyFirstDirectChildLegendIsExcepted() {
        String html = "<form><fieldset disabled>" +
            "<div><legend><input name=a value=1></legend></div>" + // nested legend: not a direct child
            "<legend><input name=b value=2></legend>" + // first direct-child legend
            "<legend><input name=c value=3></legend>" + // subsequent legend: not excepted
            "</fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("b=2", data.get(0).toString());
    }

    @Test void legendExceptionDoesNotLiftOtherDisabledFieldsets() {
        String html = "<form>" +
            "<fieldset disabled><legend>" +
            "<fieldset disabled><input name=a value=1></fieldset>" + // inner fieldset still disables
            "<input name=b value=2>" + // only inside outer's first legend: submittable
            "</legend></fieldset>" +
            "<fieldset disabled><legend></legend>" +
            "<fieldset disabled><legend><input name=c value=3></legend></fieldset>" + // inner legend exception does not lift outer
            "</fieldset>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("b=2", data.get(0).toString());
    }

    @Test void fieldsetDisabledStateTracksLiveDom() {
        String html = "<form><fieldset disabled>" +
            "<legend><input name=a value=1></legend>" +
            "<legend><input name=b value=2></legend>" +
            "<input name=c value=3>" +
            "</fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        Element fieldset = doc.selectFirst("fieldset");
        Element firstLegend = doc.selectFirst("legend");
        Element inputA = doc.selectFirst("input[name=a]");
        Element inputC = doc.selectFirst("input[name=c]");

        // removing the first legend promotes the next direct-child legend
        firstLegend.remove();
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("b=2", data.get(0).toString());

        // moving a control into the (now first) legend restores it; moving one out excludes it
        firstLegend.appendChild(inputC); // reinsert old legend after the current one: no longer first
        fieldset.appendChild(firstLegend);
        data = form.formData();
        assertEquals(1, data.size());
        assertEquals("b=2", data.get(0).toString());

        // enabling the fieldset restores all controls in DOM order
        fieldset.removeAttr("disabled");
        data = form.formData();
        assertEquals(3, data.size());
        assertEquals("b=2", data.get(0).toString());
        assertEquals("a=1", data.get(1).toString());
        assertEquals("c=3", data.get(2).toString());

        // moving a control into a now-disabled fieldset excludes it again
        fieldset.attr("disabled", "");
        doc.selectFirst("form").appendChild(fieldset); // keep structure; move b's input out of legend
        Element inputB = doc.selectFirst("input[name=b]");
        fieldset.appendChild(inputB);
        data = form.formData();
        assertEquals(0, data.size());

        // moving a control out of the disabled fieldset restores it
        doc.selectFirst("form").appendChild(inputA);
        data = form.formData();
        assertEquals(1, data.size());
        assertEquals("a=1", data.get(0).toString());
    }

    @Test void buttonSubmitResetTypesAreNotSubmitted() {
        String html = "<form>" +
            "<input type=submit name=s value=1><input type=reset name=r value=2>" +
            "<input type=button name=b value=3><button name=c value=4>Go</button>" +
            "<input type=text name=t value=5>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("t=5", data.get(0).toString());
    }

    @Test void checkboxDefaultsToOnAndRespectsChecked() {
        String html = "<form>" +
            "<input type=checkbox name=a checked>" + // no value -> on
            "<input type=checkbox name=b value=yes checked>" +
            "<input type=checkbox name=c>" + // not checked -> skipped
            "<input type=radio name=d value=1 checked><input type=radio name=d value=2>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(3, data.size());
        assertEquals("a=on", data.get(0).toString());
        assertEquals("b=yes", data.get(1).toString());
        assertEquals("d=1", data.get(2).toString());
    }

    @Test void submitThrowsOnUnresolvableAction() {
        String html = "<form action='not-resolvable-without-base'><input name='q'></form>";
        Document doc = Jsoup.parse(html); // no base URI, so the relative action cannot be made absolute
        FormElement form = (FormElement) doc.selectFirst("form");

        boolean threw = false;
        try {
            form.submit();
        } catch (IllegalArgumentException e) {
            threw = true;
            assertEquals("Could not determine a form action URL for submit. Ensure you set a base URI when parsing.",
                e.getMessage());
        }
        assertTrue(threw);

        // formData() does not validate the action URL
        assertEquals(1, form.formData().size());
    }

    @Test void submitUsesBaseUriWhenNoAction() {
        String html = "<form><input name='q' value='jsoup'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/page");
        FormElement form = (FormElement) doc.selectFirst("form");
        Connection con = form.submit();

        assertEquals("http://example.com/page", con.request().url().toExternalForm());
        assertEquals(Connection.Method.GET, con.request().method());
    }

    @Test void externalControlWithFormAttrIsSubmitted() {
        String html = "<input name='before' value='1' form='f'>" +
            "<form id='f'><input name='inside' value='2'></form>" +
            "<div><input name='after' value='3' form='f'></div>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(3, data.size());
        assertEquals("before=1", data.get(0).toString()); // external before the form
        assertEquals("inside=2", data.get(1).toString());
        assertEquals("after=3", data.get(2).toString()); // external, nested in a div after the form
    }

    @Test void externalControlsCoverAllControlTypesAndPreserveMultiValues() {
        String html = "<form id='f'><input name='text' value='t'></form>" +
            "<input type='hidden' name='hid' value='h' form='f'>" +
            "<textarea name='area' form='f'>txt</textarea>" +
            "<select name='multi' multiple form='f'><option value='a' selected><option value='b' selected></select>" +
            "<input name='dup' value='' form='f'><input name='dup' value='x' form='f'>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(7, data.size()); // empty values and duplicate names are not merged
        assertEquals("text=t", data.get(0).toString());
        assertEquals("hid=h", data.get(1).toString());
        assertEquals("area=txt", data.get(2).toString());
        assertEquals("multi=a", data.get(3).toString()); // multi options in document order
        assertEquals("multi=b", data.get(4).toString());
        assertEquals("dup=", data.get(5).toString());
        assertEquals("dup=x", data.get(6).toString());
    }

    @Test void formWithoutIdAbsorbsNoExternalControls() {
        String html = "<form><input name='inside' value='1'></form><input name='out' value='2' form='f'>" +
            "<form id=''><input name='inside2' value='3'></form>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        List<Connection.KeyVal> data1 = forms.get(0).formData();
        assertEquals(1, data1.size());
        assertEquals("inside=1", data1.get(0).toString());

        List<Connection.KeyVal> data2 = forms.get(1).formData(); // empty id
        assertEquals(1, data2.size());
        assertEquals("inside2=3", data2.get(0).toString());
    }

    @Test void invalidFormReferencesAreSafelyIgnored() {
        String html = "<form id='f'><input name='ok' value='1'></form>" +
            "<input name='missing' value='2' form='nope'>" +
            "<input type='submit' name='btn' value='go' form='f'>" + // submit still excluded
            "<input name='noname' value='3' form='f' disabled>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = assertDoesNotThrow(form::formData);

        assertEquals(1, data.size());
        assertEquals("ok=1", data.get(0).toString()); // valid descendants still submit
    }

    @Test void formAttributePointingAtNonFormTargetIsIgnored() {
        String html = "<div id='f'></div>" +
            "<form id='f'><input name='inside' value='1'></form>" +
            "<input name='out' value='2' form='f'>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("inside=1", data.get(0).toString()); // the div wins the id; form absorbs nothing external
    }

    @Test void externalControlGoesToReferencedFormOnly() {
        String html = "<form id='a'><input name='a1' value='1'></form>" +
            "<form id='b'><input name='b1' value='2'></form>" +
            "<input name='x' value='3' form='b'>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        List<Connection.KeyVal> dataA = forms.get(0).formData();
        assertEquals(1, dataA.size());
        assertEquals("a1=1", dataA.get(0).toString());

        List<Connection.KeyVal> dataB = forms.get(1).formData();
        assertEquals(2, dataB.size());
        assertEquals("b1=2", dataB.get(0).toString());
        assertEquals("x=3", dataB.get(1).toString());
    }

    @Test void externalControlInsideAnotherFormFollowsExplicitReference() {
        String html = "<form id='a'></form>" +
            "<form id='b'><input name='x' value='1' form='a'></form>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        assertEquals(1, forms.get(0).formData().size()); // explicit association overrides parser nesting
        assertEquals("x=1", forms.get(0).formData().get(0).toString());
        assertEquals(0, forms.get(1).formData().size());
    }

    @Test void descendantWithFormAttrElsewhereIsExcluded() {
        String html = "<form id='f'><input name='keep' value='1'><input name='gone' value='2' form='other'></form>" +
            "<form id='other'></form>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        List<Connection.KeyVal> data = forms.get(0).formData();
        assertEquals(1, data.size());
        assertEquals("keep=1", data.get(0).toString());

        List<Connection.KeyVal> other = forms.get(1).formData();
        assertEquals(1, other.size());
        assertEquals("gone=2", other.get(0).toString());
    }

    @Test void emptyFormAttributeResolvesToNothing() {
        String html = "<form id='f'><input name='a' value='1'><input name='b' value='2' form=''></form>" +
            "<input name='c' value='3' form=''>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size()); // an explicit empty form attribute associates with nothing, even for descendants
        assertEquals("a=1", data.get(0).toString());
    }

    @Test void descendantWithFormAttrToOwnFormIsNotDuplicated() {        String html = "<form id='f'><input name='a' value='1' form='f'><input name='b' value='2'></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("a=1", data.get(0).toString());
        assertEquals("b=2", data.get(1).toString());
    }

    @Test void duplicateFormIdsOnlyFirstAbsorbsExternalControls() {
        String html = "<form id='f'><input name='a' value='1'></form>" +
            "<form id='f'><input name='b' value='2'></form>" +
            "<input name='x' value='3' form='f'>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        List<Connection.KeyVal> first = forms.get(0).formData();
        assertEquals(2, first.size());
        assertEquals("a=1", first.get(0).toString());
        assertEquals("x=3", first.get(1).toString());

        List<Connection.KeyVal> second = forms.get(1).formData();
        assertEquals(1, second.size());
        assertEquals("b=2", second.get(0).toString());
    }

    @Test void associationsAreRecomputedFromLiveDomOnEachCall() {
        String html = "<form id='f'><input name='a' value='1'></form>" +
            "<input id='ext' name='x' value='2' form='f' type='checkbox'>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        Element ext = doc.selectFirst("#ext");

        assertEquals(1, form.formData().size()); // x is an unchecked checkbox, so only a submits

        // checkbox semantics apply to external controls too
        ext.attr("checked", "");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("x=2", data.get(1).toString());

        // changing the form attribute immediately dissociates / reassociates
        ext.attr("form", "nope");
        assertEquals(1, form.formData().size());
        ext.attr("form", "f");
        assertEquals(2, form.formData().size());

        // removing the form's id stops absorption immediately
        form.removeAttr("id");
        assertEquals(1, form.formData().size());
        form.attr("id", "f");
        assertEquals(2, form.formData().size());

        // name / disabled / type changes are honored immediately
        ext.removeAttr("checked");
        ext.attr("name", "");
        assertEquals(1, form.formData().size());
        ext.attr("name", "x");
        ext.attr("disabled", "");
        assertEquals(1, form.formData().size());
        ext.removeAttr("disabled");
        ext.attr("type", "reset");
        assertEquals(1, form.formData().size());
        ext.attr("type", "text");
        assertEquals(2, form.formData().size());

        // detaching the control from the document removes it; re-attaching restores it
        ext.remove();
        assertEquals(1, form.formData().size());
        doc.body().appendChild(ext);
        assertEquals(2, form.formData().size());
    }

    @Test void movingControlsReordersResultsByDocumentPosition() {
        String html = "<input id='ext' name='x' value='2' form='f'>" +
            "<form id='f'><input id='in1' name='a' value='1'></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals("x=2", data.get(0).toString());
        assertEquals("a=1", data.get(1).toString());

        // move the external control to after the form
        form.after(doc.selectFirst("#ext"));
        data = form.formData();
        assertEquals("a=1", data.get(0).toString());
        assertEquals("x=2", data.get(1).toString());
    }

    @Test void selectSelectedChangesOnExternalControlAreLive() {
        String html = "<form id='f'></form>" +
            "<select id='s' name='s' form='f'><option value='a'><option value='b' selected></select>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        assertEquals("s=b", form.formData().get(0).toString());

        doc.selectFirst("option[value=a]").attr("selected", "");
        doc.selectFirst("option[value=b]").removeAttr("selected");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("s=a", data.get(0).toString());
    }

    @Test void crossDocumentControlsAreNeverMixed() {
        Document doc1 = Jsoup.parse("<form id='f'><input name='a' value='1'></form><input name='x' value='2' form='f'>");
        Document doc2 = Jsoup.parse("<form id='f'><input name='b' value='3'></form><input name='y' value='4' form='f'>");

        List<Connection.KeyVal> data1 = doc1.select("form").forms().get(0).formData();
        assertEquals(2, data1.size());
        assertEquals("a=1", data1.get(0).toString());
        assertEquals("x=2", data1.get(1).toString());

        List<Connection.KeyVal> data2 = doc2.select("form").forms().get(0).formData();
        assertEquals(2, data2.size());
        assertEquals("b=3", data2.get(0).toString());
        assertEquals("y=4", data2.get(1).toString());
    }

    @Test void formDataDoesNotMutateDom() {
        String html = "<input name='x' value='1' form='f'><form id='f'><input name='a' value='2'></form>";
        Document doc = Jsoup.parse(html);
        String expected = doc.html();

        List<Connection.KeyVal> data1 = doc.select("form").forms().get(0).formData();
        data1.clear(); // mutating the returned list must not affect anything
        List<Connection.KeyVal> data2 = doc.select("form").forms().get(0).formData();

        assertEquals(2, data2.size());
        assertEquals(expected, doc.html()); // no nodes, attributes, or structure changed
    }

    @Test void externalAssociationsSurviveSerializationRoundTrip() {
        String html = "<input name='x' value='1' form='f'>" +
            "<form id='f'><input name='a' value='2'></form>" +
            "<select name='s' multiple form='f'><option value='q' selected></select>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> before = form.formData();

        Document reparse = Jsoup.parse(doc.html());
        List<Connection.KeyVal> after = reparse.select("form").forms().get(0).formData();

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).key(), after.get(i).key());
            assertEquals(before.get(i).value(), after.get(i).value());
        }
    }

    @Test void textareaNewlinesAreNormalizedToCrlf() {
        // lone CR, lone LF, and CRLF pair, in source order, each become one CRLF
        String html = "<form><textarea name='a'>x\ry\rz\nw\r\nq</textarea></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(1, data.size());
        assertEquals("a", data.get(0).key());
        assertEquals("x\r\ny\r\nz\r\nw\r\nq", data.get(0).value());
    }

    @Test void textareaPreservesOtherCharactersVerbatim() {
        // spaces, tabs, Unicode, and consecutive blank lines survive; only line breaks change
        String html = "<form><textarea name='a'>  a\tb ☺ \n\n\r\n  \r c </textarea></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        assertEquals("  a\tb ☺ \r\n\r\n\r\n  \r\n c ", form.formData().get(0).value());
    }

    @Test void textareaEmptyValueStaysEmpty() {
        String html = "<form><textarea name='a'></textarea><textarea name='b'>   </textarea></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();

        assertEquals(2, data.size());
        assertEquals("", data.get(0).value());
        assertEquals("   ", data.get(1).value()); // whitespace-only value is not empty
    }

    @Test void textareaNewlineNormalizationIsIdempotentAndDoesNotMutateDom() {
        String html = "<form><textarea name='a'>x\ry\nz\r\nw</textarea></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        String expectedValue = "x\r\ny\r\nz\r\nw";
        String originalHtml = doc.html();

        List<Connection.KeyVal> first = form.formData();
        assertEquals(expectedValue, first.get(0).value());
        // repeated reads must not accumulate or otherwise change the newlines
        assertEquals(expectedValue, form.formData().get(0).value());
        assertEquals(expectedValue, form.formData().get(0).value());
        // the textarea's own text is untouched by reading
        assertEquals("x\ry\nz\r\nw", form.selectFirst("textarea").wholeText());
        assertEquals(originalHtml, doc.html());
    }

    @Test void textareaNewlineNormalizationAppliesToValuesSetViaDom() {
        Document doc = Jsoup.parse("<form><textarea name='a'></textarea></form>");
        FormElement form = (FormElement) doc.selectFirst("form");
        form.selectFirst("textarea").val("one\rtwo\nthree\r\nfour");

        assertEquals("one\r\ntwo\r\nthree\r\nfour", form.formData().get(0).value());
        // setting and reading does not rewrite the underlying text
        assertEquals("one\rtwo\nthree\r\nfour", form.selectFirst("textarea").wholeText());
    }

    @Test void textareaNewlineNormalizationDoesNotTouchOtherControls() {
        String html = "<form>" +
            "<input name='i' value='a\nb'>" + // input value is not normalized
            "<select name='s'><option value='x\ny'>x</option></select>" + // option value is not normalized
            "<textarea name='t'>a\nb</textarea>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();

        assertEquals("i", data.get(0).key());
        assertEquals("a\nb", data.get(0).value());
        assertEquals("s", data.get(1).key());
        assertEquals("x\ny", data.get(1).value());
        assertEquals("t", data.get(2).key());
        assertEquals("a\r\nb", data.get(2).value()); // only the textarea is normalized
    }

    @Test void disabledTextareaWithNewlinesIsNotSubmitted() {
        String html = "<form>" +
            "<textarea name='a' disabled>x\ny</textarea>" +
            "<fieldset disabled><textarea name='b'>p\rq</textarea></fieldset>" +
            "<fieldset disabled><legend><textarea name='c'>m\nn</textarea></legend></fieldset>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size()); // only the textarea in the disabled fieldset's first legend submits
        assertEquals("c", data.get(0).key());
        assertEquals("m\r\nn", data.get(0).value());

        // re-enabling / moving reflects only the current DOM; the value is then normalized
        doc.selectFirst("textarea[name=a]").removeAttr("disabled");
        Element b = doc.selectFirst("textarea[name=b]");
        b.attr("name", "b2");
        form.appendChild(b); // moved out of the disabled fieldset: now submittable
        data = form.formData();
        assertEquals(3, data.size());
        assertEquals("a", data.get(0).key());
        assertEquals("x\r\ny", data.get(0).value());
        assertEquals("c", data.get(1).key());
        assertEquals("b2", data.get(2).key());
        assertEquals("p\r\nq", data.get(2).value());
    }

    @Test void externalTextareaNewlinesAreNormalizedInDocumentOrder() {
        String html = "<textarea name='ext1' form='f'>a\rb</textarea>" +
            "<form id='f'><input name='mid' value='1'><textarea name='in'>c\nd</textarea></form>" +
            "<div><textarea name='ext2' form='f'>e\r\nf</textarea></div>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(4, data.size());
        assertEquals("ext1", data.get(0).key());
        assertEquals("a\r\nb", data.get(0).value());
        assertEquals("mid=1", data.get(1).toString());
        assertEquals("in", data.get(2).key());
        assertEquals("c\r\nd", data.get(2).value());
        assertEquals("ext2", data.get(3).key());
        assertEquals("e\r\nf", data.get(3).value());

        // a textarea associated with a different form never mixes in
        Document other = Jsoup.parse("<form id='g'><textarea name='g' form='g'>x\ny</textarea></form>");
        assertEquals(1, ((FormElement) other.selectFirst("form")).formData().size());
    }

    @Test void returnedTextareaDataIsASnapshot() {
        String html = "<form><textarea name='a'>x\ny</textarea></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        data.get(0).key("changed").value("also changed");
        data.clear();

        List<Connection.KeyVal> again = form.formData();
        assertEquals(1, again.size());
        assertEquals("a", again.get(0).key());
        assertEquals("x\r\ny", again.get(0).value());
        assertEquals("x\ny", form.selectFirst("textarea").wholeText());
    }

    @Test void textareaNormalizationSurvivesSerializationRoundTrip() {
        String html = "<form><textarea name='a'>x\ry\nz\r\nw</textarea></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> before = form.formData();

        Document reparse = Jsoup.parse(doc.html());
        List<Connection.KeyVal> after = ((FormElement) reparse.selectFirst("form")).formData();

        assertEquals(before.size(), after.size());
        assertEquals(before.get(0).key(), after.get(0).key());
        assertEquals(before.get(0).value(), after.get(0).value());
        assertEquals("x\r\ny\r\nz\r\nw", after.get(0).value());
    }

    @Test void oneUnusualTextareaValueDoesNotSwallowLaterFields() {
        // mixed/edge-case line breaks in an early value must not prevent subsequent controls from submitting
        String html = "<form>" +
            "<textarea name='a'>x\r\r\n\n\r</textarea>" +
            "<input name='b' value='2'>" +
            "<textarea name='c'>p\r\nq</textarea>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();

        assertEquals(3, data.size());
        assertEquals("x\r\n\r\n\r\n\r\n", data.get(0).value());
        assertEquals("b=2", data.get(1).toString());
        assertEquals("p\r\nq", data.get(2).value());
    }

    @Test void textareaCrlfIsCarriedInGetAndPostSubmissions() throws IOException {
        String echoUrl = TestServer.origin().echo.url();

        // GET: the CRLF must survive percent-encoding in the query string
        Document getDoc = Jsoup.parse(
            "<form action='" + echoUrl + "'><textarea name='q'>line1\nline2</textarea></form>", echoUrl);
        Document getEcho = ((FormElement) getDoc.selectFirst("form")).submit().get();
        assertEquals("q=line1%0D%0Aline2",
            getEcho.select("th:contains(Query String)").next().text());

        // POST: the CRLF must survive in the url-encoded request body
        Document postDoc = Jsoup.parse(
            "<form action='" + echoUrl + "' method='post'><textarea name='q'>line1\rline2</textarea></form>", echoUrl);
        Document postEcho = ((FormElement) postDoc.selectFirst("form")).submit().post();
        assertEquals("q=line1%0D%0Aline2",
            postEcho.select("th:contains(Post Data)").next().text());
    }
}
