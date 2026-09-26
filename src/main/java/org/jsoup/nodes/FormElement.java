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
import java.util.List;

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
     * {@code select[multiple]} submits every selected option, while a single {@code select} with no selected option
     * falls back to its first non-disabled option. An {@code option} is disabled when it has the {@code disabled}
     * attribute itself, or when it descends from a {@code disabled} {@code optgroup}. The list is re-queried from the
     * DOM on each call, so changes to
     * the document are reflected. The returned list is a copy of the data, and changes to the contents of the
     * list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        Elements formEls = elements();
        for (Element el: formEls) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (isDisabled(el)) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (el.nameIs("button") || type.equalsIgnoreCase("button") || type.equalsIgnoreCase("reset")
                || type.equalsIgnoreCase("submit") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these (a submit button only if it was the clicker)

            if (el.nameIs("select")) {
                Elements options = el.select("option[selected]");
                boolean set = false;
                for (Element option: options) {
                    if (isOptionDisabled(option)) continue; // disabled options are not successful
                    data.add(HttpConnection.KeyVal.create(name, option.val()));
                    set = true;
                }
                if (!set && !el.hasAttr("multiple")) {
                    // a single select defaults to its first non-disabled option
                    for (Element option : el.select("option")) {
                        if (!isOptionDisabled(option)) {
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                            break;
                        }
                    }
                }
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
     * An option is disabled if it has the {@code disabled} attribute, or if it descends from an {@code optgroup} that
     * does. Evaluated from the live DOM on each call.
     */
    private static boolean isOptionDisabled(Element option) {
        if (option.hasAttr("disabled")) return true;
        for (Element parent = option.parent(); parent != null && !parent.nameIs("select"); parent = parent.parent()) {
            if (parent.nameIs("optgroup") && parent.hasAttr("disabled")) return true;
        }
        return false;
    }

    /**
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
