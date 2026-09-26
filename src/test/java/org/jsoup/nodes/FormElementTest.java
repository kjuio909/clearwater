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

        assertEquals(6, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString());
        assertEquals("three=five", data.get(2).toString());
        assertEquals("six=seven", data.get(3).toString());
        assertEquals("seven=on", data.get(4).toString()); // set
        assertEquals("eight=on", data.get(5).toString()); // default
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
}
