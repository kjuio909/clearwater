package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator submittable = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", "));

    /**
     * Create a new, standalone form element.
     *
     * @param tag        tag of this element
     * @param baseUri    the base URI
     * @param attributes initial attributes
     */
    public FormElement(Tag tag, @Nullable String baseUri, @Nullable Attributes attributes) {
        super(tag, baseUri, attributes);
    }

    /**
     * Get the list of form control elements associated with this form.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions them:
        Elements els = select(submittable); // current form children
        for (Element linkedEl : linkedEls) {
            if (linkedEl.ownerDocument() != null && !els.contains(linkedEl)) {
                els.add(linkedEl); // adds previously linked elements, that weren't previously removed from the DOM
            }
        }

        return els;
    }

    /**
     * Add a form control element to this form.
     * @param element form control to add
     * @return this form element, for chaining
     */
    public FormElement addElement(Element element) {
        linkedEls.add(element);
        return this;
    }

    /**
     * Resolve the controls that submit with this form, in document order.
     * <p>In addition to descendant controls (and controls linked to this form at parse time), this includes controls
     * anywhere else in the same Document whose {@code form} attribute references this form's {@code id}, per the
     * HTML form-association rules. A control carrying a {@code form} attribute belongs solely to its referenced form:
     * a descendant with a {@code form} attribute pointing elsewhere is excluded, and an unresolvable reference (no
     * such id, or the target is not a form) associates it with nothing. When several forms share an id, only the
     * first such form in document order absorbs externally-associated controls. Computed fresh from the live DOM on
     * every call; the form must have a non-empty id to absorb any external controls.</p>
     */
    private Elements formControls() {
        Document doc = ownerDocument();
        if (doc == null)
            return elements(); // a detached form has no document to resolve external associations against

        String id = id();
        // Per the HTML spec, a form attribute resolves to the first element in tree order with that id, and only
        // associates when that element is a form. So this form absorbs external controls solely when it itself is
        // that first element: a missing id, a non-form target, another form, or an earlier same-id form all fail.
        boolean absorbsExternals = id.length() > 0 && doc.getElementById(id) == this;

        Set<Element> descendants = new HashSet<>(this.getAllElements());
        Set<Element> linked = new HashSet<>(linkedEls);
        Elements controls = new Elements();
        for (Element el : doc.getAllElements()) { // a single document-order pass merges descendants and external controls
            if (!el.tag().isFormSubmittable()) continue;
            boolean belongs;
            if (el.hasAttr("form")) // an explicit association decides, even for descendants (form="" resolves to nothing)
                belongs = absorbsExternals && el.attr("form").equals(id);
            else
                belongs = descendants.contains(el) || linked.contains(el); // implicit: descendant, or linked at parse time
            if (belongs) controls.add(el);
        }
        return controls;
    }

    @Override
    protected void removeChild(Node out) {
        super.removeChild(out);
        linkedEls.remove(out);
    }

    /**
     Prepare to submit this form. A Connection object is created with the request set up from the form values. This
     Connection will inherit the settings and the cookies (etc) of the connection/session used to request this Document
     (if any), as available in {@link Document#connection()}
     <p>You can then set up other options (like user-agent, timeout, cookies), then execute it.</p>

     @return a connection prepared from the values of this form, in the same session as the one used to request it
     @throws IllegalArgumentException if the form's absolute action URL cannot be determined. Make sure you pass the
     document's base URI when parsing.
     */
    public Connection submit() {
        String action = hasAttr("action") ? absUrl("action") : baseUri();
        Validate.notEmpty(action, "Could not determine a form action URL for submit. Ensure you set a base URI when parsing.");
        Connection.Method method = attr("method").equalsIgnoreCase("POST") ?
                Connection.Method.POST : Connection.Method.GET;

        Document owner = ownerDocument();
        Connection connection = owner != null? owner.connection().newRequest() : Jsoup.newSession();
        return connection.url(action)
                .data(formData())
                .method(method);
    }

    /**
     * Get the data that this form submits, using the browser's <i>successful controls</i> semantics: controls are
     * returned in DOM order; controls without a {@code name}, disabled controls (including those inside a disabled
     * {@code fieldset}, excepting descendants of that fieldset's first {@code legend} child), and {@code button} /
     * {@code reset} / {@code submit} / {@code image} inputs are excluded;
     * checkboxes and radios are only included when {@code checked} (defaulting to the value {@code "on"}); a
     * {@code select[multiple]} submits every selected option, a single {@code select} submits only the first selected
     * option in DOM order (later {@code selected} options do not create duplicate values), and when no selected option
     * is submittable it falls back to its first non-disabled option (or omits the field if none exists). Options that
     * are disabled, either directly or by a disabled {@code optgroup} ancestor, are never submitted.
     * <p>A {@code textarea}'s value is submitted verbatim (spaces, tabs, Unicode characters and blank lines are
     * preserved), except that its line breaks &mdash; a lone {@code \r}, a lone {@code \n}, or a {@code \r\n} pair
     * &mdash; are each normalized to {@code \r\n}, as a browser does before submitting. This does not modify the
     * textarea's DOM text. Field names and the values of inputs, options and files are left untouched.</p>
     * <p>In addition to the form's descendant controls, controls elsewhere in the same Document that carry a
     * {@code form} attribute whose value is this form's {@code id} are submitted as well (matching the browser's
     * <a href="https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#association-of-controls-and-forms">
     * form-associated elements</a> rules). All controls are merged in document order. A control with a {@code form}
     * attribute is handled solely by that explicit association, even when it is also a descendant: an unresolvable
     * value (no such id, the target is not a form, or an earlier form in the Document shares this id) excludes it.
     * Forms without an id absorb no external controls.</p>
     * The list is re-queried from the DOM on each call, so changes to the document are reflected. The returned list is a copy of the data, and changes to the contents of the
     * list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el: formControls()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (isDisabled(el)) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (el.nameIs("button") || type.equalsIgnoreCase("button") || type.equalsIgnoreCase("reset")
                || type.equalsIgnoreCase("submit") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these (a submit button only if it was the clicker)

            if (el.nameIs("select")) {
                boolean multiple = el.hasAttr("multiple");
                Elements options = el.select("option"); // document order, re-queried from the live DOM on each call
                if (multiple) {
                    // a multiple select submits every option that is both selected and enabled, in DOM order;
                    // duplicate field names are kept, and when none qualify the field is omitted entirely
                    for (Element option : options) {
                        if (option.hasAttr("selected") && !isDisabledOption(option))
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                    }
                } else {
                    // a single select submits at most one value: the first selected-and-enabled option in DOM order,
                    // even when several options carry selected. Later selected options must not add duplicate values.
                    Element selected = null;
                    for (Element option : options) {
                        if (option.hasAttr("selected") && !isDisabledOption(option)) {
                            selected = option;
                            break;
                        }
                    }
                    if (selected == null) {
                        // nothing selected is submittable (no selection, or every selected option/optgroup is disabled):
                        // fall back to the first option that is enabled both itself and via its optgroup; if no such
                        // option exists, the field is omitted
                        for (Element option : options) {
                            if (!isDisabledOption(option)) {
                                selected = option;
                                break;
                            }
                        }
                    }
                    if (selected != null)
                        data.add(HttpConnection.KeyVal.create(name, selected.val()));
                }
            } else if (el.nameIs("textarea")) {
                // checked before the input-only type branches: a type attribute is non-conforming on a textarea and
                // never changes its control type. wholeText() preserves spaces, tabs and blank lines verbatim
                data.add(HttpConnection.KeyVal.create(name, normalizeTextareaNewlines(el.wholeText())));
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked")) {
                    final String val = el.val().length() >  0 ? el.val() : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    /**
     * Normalize the newlines of a submitted textarea value to the platform-independent {@code CRLF} sequence, per the
     * browser's <a href="https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#the-textarea-element-2">
     * textarea wrapping / line break normalization</a>: a lone {@code \r}, a lone {@code \n}, or a {@code \r\n} pair each
     * become a single {@code \r\n}. Every other character (spaces, tabs, Unicode, and consecutive blank lines) is copied
     * verbatim, and an empty value stays an empty string. Applied only when reading the value, so the DOM is never
     * mutated and repeated reads produce the same result (the mapping is idempotent).
     */
    private static String normalizeTextareaNewlines(String value) {
        if (value.indexOf('\r') == -1 && value.indexOf('\n') == -1) return value; // fast path: nothing to normalize

        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r') {
                out.append("\r\n");
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') i++; // consume the LF of a CRLF pair
            } else if (c == '\n') {
                out.append("\r\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * An option is disabled if it has the {@code disabled} attribute itself, or if it descends from a {@code optgroup}
     * that does. Evaluated from the live DOM on each call.
     */
    private static boolean isDisabledOption(Element option) {
        if (option.hasAttr("disabled")) return true;
        for (Element parent = option.parent(); parent != null && !parent.nameIs("select"); parent = parent.parent()) {
            if (parent.nameIs("optgroup") && parent.hasAttr("disabled"))
                return true;
        }
        return false;
    }

    /**
     * A form control is disabled if it has the {@code disabled} attribute, or if it is a descendant of a
     * {@code fieldset} that does. Controls descending from the fieldset's first {@code legend} element child are
     * excepted from that fieldset's disabling (but may still be disabled by their own attribute or another
     * ancestor fieldset). Evaluated from the live DOM on each call.
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        for (Element parent = el.parent(); parent != null; parent = parent.parent()) {
            if (parent.nameIs("fieldset") && parent.hasAttr("disabled") && !inFirstLegend(parent, el))
                return true;
        }
        return false;
    }

    /**
     * Tests if {@code el} descends from the first {@code legend} element child of {@code fieldset} (which must be an
     * ancestor of {@code el}). Only that first direct-child legend is excepted; later or nested legends are not.
     */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        Element legend = null;
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) {
                legend = child;
                break;
            }
        }
        if (legend == null) return false;
        for (Element parent = el.parent(); parent != null && parent != fieldset; parent = parent.parent()) {
            if (parent == legend) return true;
        }
        return false;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
