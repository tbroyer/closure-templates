/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.shared.restricted;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.template.soy.data.Dir;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.NumberData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.internal.base.CharEscapers;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;


/**
 * Java implementations of functions that escape, normalize, and filter untrusted strings to
 * allow them to be safely embedded in particular contexts.
 * These correspond to the {@code soy.$$escape*}, {@code soy.$$normalize*}, and
 * {@code soy.$$filter*} functions defined in "soyutils.js".
 *
 * @author Mike Samuel
 */
public final class Sanitizers {

  /** Receives messages about unsafe values that were filtered out. */
  private static final Logger LOGGER = Logger.getLogger(Sanitizers.class.getName());

  private Sanitizers() {
    // Not instantiable.
  }


  /**
   * Converts the input to HTML by entity escaping.
   */
  public static String escapeHtml(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      return value.coerceToString();
    }
    return escapeHtml(value.coerceToString());
  }


  /**
   * Converts plain text to HTML by entity escaping.
   */
  public static String escapeHtml(String value) {
    return EscapingConventions.EscapeHtml.INSTANCE.escape(value);
  }


  /**
   * Normalizes the input HTML while preserving "safe" tags and the known directionality.
   *
   * @return the normalized input, in the form of {@link SanitizedContent} of
   *         {@link ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(SoyValue value) {
    Dir valueDir = null;
    if (value instanceof SanitizedContent) {
      SanitizedContent sanitizedContent = (SanitizedContent) value;
      if (sanitizedContent.getContentKind() == SanitizedContent.ContentKind.HTML) {
        return (SanitizedContent) value;
      }
      valueDir = sanitizedContent.getContentDirection();
    }
    return cleanHtml(value.coerceToString(), valueDir);
  }


  /**
   * Normalizes the input HTML while preserving "safe" tags. The content directionality is unknown.
   *
   * @return the normalized input, in the form of {@link SanitizedContent} of
   *         {@link ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(String value) {
    return cleanHtml(value, null);
  }


  /**
   * Normalizes the input HTML of a given directionality while preserving "safe" tags.
   *
   * @return the normalized input, in the form of {@link SanitizedContent} of
   *         {@link ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(String value, Dir contentDir) {
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        stripHtmlTags(value, TagWhitelist.FORMATTING, true), ContentKind.HTML, contentDir);
  }


  /**
   * Converts the input to HTML suitable for use inside {@code <textarea>} by entity escaping.
   */
  public static String escapeHtmlRcdata(SoyValue value) {

    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // We can't allow tags in the output, because that would allow safe HTML containing
      // "<textarea>" to prematurely close the textarea.
      // Instead, we normalize which is semantics preserving in RCDATA.
      return normalizeHtml(value.coerceToString());
    }

    return escapeHtml(value.coerceToString());
  }


  /**
   * Converts plain text to HTML by entity escaping.
   */
  public static String escapeHtmlRcdata(String value) {
    return EscapingConventions.EscapeHtml.INSTANCE.escape(value);
  }


  /**
   * Normalizes HTML to HTML making sure quotes and other specials are entity encoded.
   */
  public static String normalizeHtml(SoyValue value) {
    return normalizeHtml(value.coerceToString());
  }


  /**
   * Normalizes HTML to HTML making sure quotes and other specials are entity encoded.
   */
  public static String normalizeHtml(String value) {
    return EscapingConventions.NormalizeHtml.INSTANCE.escape(value);
  }


  /**
   * Normalizes HTML to HTML making sure quotes, spaces and other specials are entity encoded
   * so that the result can be safely embedded in a valueless attribute.
   */
  public static String normalizeHtmlNospace(SoyValue value) {
    return normalizeHtmlNospace(value.coerceToString());
  }


  /**
   * Normalizes HTML to HTML making sure quotes, spaces and other specials are entity encoded
   * so that the result can be safely embedded in a valueless attribute.
   */
  public static String normalizeHtmlNospace(String value) {
    return EscapingConventions.NormalizeHtmlNospace.INSTANCE.escape(value);
  }


  /**
   * Converts the input to HTML by entity escaping, stripping tags in sanitized content so the
   * result can safely be embedded in an HTML attribute value.
   */
  public static String escapeHtmlAttribute(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // |escapeHtmlAttribute should only be used on attribute values that cannot have tags.
      return stripHtmlTags(value.coerceToString(), null, true);
    }
    return escapeHtmlAttribute(value.coerceToString());
  }


  /**
   * Converts plain text to HTML by entity escaping so the result can safely be embedded in an HTML
   * attribute value.
   */
  public static String escapeHtmlAttribute(String value) {
    return EscapingConventions.EscapeHtml.INSTANCE.escape(value);
  }


  /**
   * Converts plain text to HTML by entity escaping, stripping tags in sanitized content so the
   * result can safely be embedded in an unquoted HTML attribute value.
   */
  public static String escapeHtmlAttributeNospace(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // |escapeHtmlAttributeNospace should only be used on attribute values that cannot have tags.
      return stripHtmlTags(value.coerceToString(), null, false);
    }
    return escapeHtmlAttributeNospace(value.coerceToString());
  }


  /**
   * Converts plain text to HTML by entity escaping so the result can safely be embedded in an
   * unquoted HTML attribute value.
   */
  public static String escapeHtmlAttributeNospace(String value) {
    return EscapingConventions.EscapeHtmlNospace.INSTANCE.escape(value);
  }


  /**
   * Converts the input to the body of a JavaScript string by using {@code \n} style escapes.
   */
  public static String escapeJsString(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.JS_STR_CHARS)) {
      return value.coerceToString();
    }
    return escapeJsString(value.coerceToString());
  }


  /**
   * Converts plain text to the body of a JavaScript string by using {@code \n} style escapes.
   */
  public static String escapeJsString(String value) {
    return EscapingConventions.EscapeJsString.INSTANCE.escape(value);
  }


  /**
   * Converts the input to a JavaScript expression.  The resulting expression can be a boolean,
   * number, string literal, or {@code null}.
   */
  public static String escapeJsValue(SoyValue value) {
    // We surround values with spaces so that they can't be interpolated into identifiers
    // by accident.  We could use parentheses but those might be interpreted as a function call.
    if (NullData.INSTANCE == value) {
      // The JS counterpart of this code in soyutils.js emits " null " for both null and the special
      // JS value undefined.
      return " null ";
    } else if (value instanceof NumberData) {
      // This will emit references to NaN and Infinity.  Client code should not redefine those
      // to store sensitive data.
      return " " + value.numberValue() + " ";
    } else if (value instanceof BooleanData) {
      return " " + value.booleanValue() + " ";
    } else if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.JS)) {
      return value.coerceToString();
    } else {
      return escapeJsValue(value.coerceToString());
    }
  }


  /**
   * Converts plain text to a quoted javaScript string value.
   */
  public static String escapeJsValue(String value) {
    return value != null ? "'" + escapeJsString(value) + "'" : " null ";
  }


  /**
   * Converts the input to the body of a JavaScript regular expression literal.
   */
  public static String escapeJsRegex(SoyValue value) {
    return escapeJsRegex(value.coerceToString());
  }


  /**
   * Converts plain text to the body of a JavaScript regular expression literal.
   */
  public static String escapeJsRegex(String value) {
    return EscapingConventions.EscapeJsRegex.INSTANCE.escape(value);
  }


  /**
   * Converts the input to the body of a CSS string literal.
   */
  public static String escapeCssString(SoyValue value) {
    return escapeCssString(value.coerceToString());
  }


  /**
   * Converts plain text to the body of a CSS string literal.
   */
  public static String escapeCssString(String value) {
    return EscapingConventions.EscapeCssString.INSTANCE.escape(value);
  }


  /**
   * Makes sure that the input is a valid CSS identifier part, CLASS or ID part, quantity, or
   * CSS keyword part.
   */
  public static String filterCssValue(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.CSS)) {
      return value.coerceToString();
    }
    return NullData.INSTANCE == value ? "" : filterCssValue(value.coerceToString());
  }


  /**
   * Makes sure that the input is a valid CSS identifier part, CLASS or ID part, quantity, or
   * CSS keyword part.
   */
  public static String filterCssValue(String value) {
    if (EscapingConventions.FilterCssValue.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    LOGGER.log(Level.WARNING, "|filterCssValue received bad value {0}", value);
    return EscapingConventions.FilterCssValue.INSTANCE.getInnocuousOutput();
  }


  /**
   * Converts the input to a piece of a URI by percent encoding assuming a UTF-8 encoding.
   */
  public static String escapeUri(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.URI)) {
      return normalizeUri(value);
    }
    return escapeUri(value.coerceToString());
  }


  /**
   * Converts plain text to a piece of a URI by percent encoding assuming a UTF-8 encoding.
   */
  public static String escapeUri(String value) {
    return CharEscapers.uriEscaper(false).escape(value);
  }


  /**
   * Converts a piece of URI content to a piece of URI content that can be safely embedded
   * in an HTML attribute by percent encoding.
   */
  public static String normalizeUri(SoyValue value) {
    return normalizeUri(value.coerceToString());
  }


  /**
   * Converts a piece of URI content to a piece of URI content that can be safely embedded
   * in an HTML attribute by percent encoding.
   */
  public static String normalizeUri(String value) {
    return EscapingConventions.NormalizeUri.INSTANCE.escape(value);
  }


  /**
   * Makes sure that the given input doesn't specify a dangerous protocol and also
   * {@link #normalizeUri normalizes} it.
   */
  public static String filterNormalizeUri(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.URI)) {
      return normalizeUri(value);
    }
    return filterNormalizeUri(value.coerceToString());
  }


  /**
   * Makes sure that the given input doesn't specify a dangerous protocol and also
   * {@link #normalizeUri normalizes} it.
   */
  public static String filterNormalizeUri(String value) {
    if (EscapingConventions.FilterNormalizeUri.INSTANCE.getValueFilter().matcher(value).find()) {
      return EscapingConventions.FilterNormalizeUri.INSTANCE.escape(value);
    }
    LOGGER.log(Level.WARNING, "|filterNormalizeUri received bad value {0}", value);
    return EscapingConventions.FilterNormalizeUri.INSTANCE.getInnocuousOutput();
  }


  /**
   * Makes sure that the given input is a data URI corresponding to an image.
   *
   * SanitizedContent kind does not apply -- the directive is also used to ensure no foreign
   * resources are loaded.
   */
  public static SanitizedContent filterImageDataUri(SoyValue value) {
    return filterImageDataUri(value.coerceToString());
  }


  /**
   * Makes sure that the given input is a data URI corresponding to an image.
   */
  public static SanitizedContent filterImageDataUri(String value) {
    if (EscapingConventions.FilterImageDataUri.INSTANCE.getValueFilter().matcher(value).find()) {
      // NOTE: No need to escape.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    LOGGER.log(Level.WARNING, "|filterImageDataUri received bad value {0}", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterImageDataUri.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }


  /**
   * Checks that the input is a valid HTML attribute name with normal keyword or textual content
   * or known safe attribute content.
   */
  public static String filterHtmlAttributes(SoyValue value) {
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.ATTRIBUTES)) {
      // We're guaranteed to be in a case where key=value pairs are expected. However, if it would
      // cause issues to directly abut this with more attributes, add a space. For example:
      // {$a}{$b} where $a is foo=bar and $b is boo=baz requires a space in between to be parsed
      // correctly, but not in the case where $a is foo="bar".
      // TODO: We should be able to get rid of this if the compiler can guarantee spaces between
      // adjacent print statements in attribute context at compile time.
      String content = value.coerceToString();
      if (content.length() > 0) {
        char lastChar = content.charAt(content.length() - 1);
        if (lastChar != '"' && lastChar != '\'' && !Character.isWhitespace(lastChar)) {
          content += ' ';
        }
      }
      return content;
    }
    return filterHtmlAttributes(value.coerceToString());
  }


  /**
   * Checks that the input is a valid HTML attribute name with normal keyword or textual content.
   */
  public static String filterHtmlAttributes(String value) {
    if (EscapingConventions.FilterHtmlAttributes.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    LOGGER.log(Level.WARNING, "|filterHtmlAttributes received bad value {0}", value);
    return EscapingConventions.FilterHtmlAttributes.INSTANCE.getInnocuousOutput();
  }


  /**
   * Checks that the input is part of the name of an innocuous element.
   */
  public static String filterHtmlElementName(SoyValue value) {
    return filterHtmlElementName(value.coerceToString());
  }


  /**
   * Checks that the input is part of the name of an innocuous element.
   */
  public static String filterHtmlElementName(String value) {
    if (EscapingConventions.FilterHtmlElementName.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    LOGGER.log(Level.WARNING, "|filterHtmlElementName received bad value {0}", value);
    return EscapingConventions.FilterHtmlElementName.INSTANCE.getInnocuousOutput();
  }

  /**
   * Filters noAutoescape input from explicitly tainted content.
   *
   * SanitizedContent.ContentKind.TEXT is used to explicitly mark input that is never meant to be
   * used unescaped. Specifically, {let} and {param} blocks of kind "text" are explicitly
   * forbidden from being noAutoescaped to avoid XSS regressions during application transition.
   */
  public static SoyValue filterNoAutoescape(SoyValue value) {
    // TODO: Consider also checking for things that are never valid, like null characters.
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.TEXT)) {
      LOGGER.log(Level.WARNING,
          "|noAutoescape received value explicitly tagged as ContentKind.TEXT: {0}", value);
      return StringData.forValue(EscapingConventions.INNOCUOUS_OUTPUT);
    }
    return value;
  }


  /**
   * True iff the given value is sanitized content of the given kind.
   */
  private static boolean isSanitizedContentOfKind(
      SoyValue value, SanitizedContent.ContentKind kind) {
    return value instanceof SanitizedContent && kind == ((SanitizedContent) value).getContentKind();
  }


  /**
   * Given a snippet of HTML, returns a snippet that has the same text content but only whitelisted
   * tags.
   *
   * @param safeTags the tags that are allowed in the output.
   *   A {@code null} white-list is the same as the empty white-list.
   *   If {@code null} or empty, then the output can be embedded in an attribute value.
   *   If the output is to be embedded in an attribute, {@code safeTags} should be {@code null}.
   * @param rawSpacesAllowed true if spaces are allowed in the output unescaped as is the case when
   *   the output is embedded in a regular text node, or in a quoted attribute.
   */
  @VisibleForTesting
  static String stripHtmlTags(String value, TagWhitelist safeTags, boolean rawSpacesAllowed) {
    EscapingConventions.CrossLanguageStringXform normalizer = rawSpacesAllowed ?
        EscapingConventions.NormalizeHtml.INSTANCE :
        EscapingConventions.NormalizeHtmlNospace.INSTANCE;

    Matcher matcher = EscapingConventions.HTML_TAG_CONTENT.matcher(value);
    if (!matcher.find()) {
      // Normalize so that the output can be embedded in an HTML attribute.
      return normalizer.escape(value);
    }

    StringBuilder out = new StringBuilder(value.length() - matcher.end() + matcher.start());
    Appendable normalizedOut = normalizer.escape(out);
    // We do some very simple tag balancing by dropping any close tags for unopened tags and at the
    // end emitting close tags for any still open tags.
    // This is sufficient (in HTML) to prevent embedded content with safe tags from breaking layout
    // when, for example, stripHtmlTags("</table>") is embedded in a page that uses tables for
    // formatting.
    List<String> openTags = null;
    try {
      int pos = 0;  // Such that value[:pos] has been sanitized onto out.
      do {
        int start = matcher.start();

        if (pos < start) {
          normalizedOut.append(value, pos, start);

          // More aggressively normalize ampersands at the end of a chunk so that
          //   "&<b>amp;</b>" -> "&amp;amp;" instead of "&amp;".
          if (value.charAt(start - 1) == '&') {
            out.append("amp;");
          }
        }

        if (safeTags != null) {
          String tagName = matcher.group(1);
          if (tagName != null) {
            // Use locale so that <I> works when the default locale is Turkish
            tagName = tagName.toLowerCase(Locale.ENGLISH);
            if (safeTags.isSafeTag(tagName)) {
              boolean isClose = value.charAt(start + 1) == '/';
              // TODO: Do we need to make an exception for dir="rtl" and dir="ltr"?
              if (isClose) {
                if (openTags != null) {
                  int lastIdx = openTags.lastIndexOf(tagName);
                  if (lastIdx >= 0) {
                    // Close contained tags as well.
                    // If we didn't, then we would convert "<ul><li></ul>" to "<ul><li></ul></li>"
                    // which could lead to broken layout for embedding HTML that uses lists for
                    // formatting.
                    // This leads to observably different behavior for adoption-agency dependent
                    // tag combinations like "<b><i>Foo</b> Bar</b>" but fails safe.
                    // http://www.whatwg.org/specs/web-apps/current-work/multipage/the-end.html#misnested-tags:-b-i-/b-/i
                    closeTags(openTags.subList(lastIdx, openTags.size()), out);
                  }
                }
              } else {
                // Emit the tag on the un-normalized channel dropping any
                // attributes on the floor.
                out.append('<').append(tagName).append('>');
                if (!HTML5_VOID_ELEMENTS.contains(tagName)) {
                  if (openTags == null) {
                    openTags = Lists.newArrayList();
                  }
                  openTags.add(tagName);
                }
              }
            }
          }
        }
        pos = matcher.end();
      } while (matcher.find());
      normalizedOut.append(value, pos, value.length());
      // Emit close tags, so that safeTags("<table>") can't break the layout of embedding HTML that
      // uses tables for layout.
      if (openTags != null) {
        closeTags(openTags, out);
      }
    } catch (IOException ex) {
      // Writing to a StringBuilder should not throw.
      throw new AssertionError(ex);
    }
    return out.toString();
  }

  private static void closeTags(List<String> openTags, StringBuilder out) {
    for (int i = openTags.size(); --i >= 0;) {
      out.append("</").append(openTags.get(i)).append('>');
    }
    openTags.clear();
  }

  /** From http://www.w3.org/TR/html-markup/syntax.html#syntax-elements */
  private static final Set<String> HTML5_VOID_ELEMENTS = ImmutableSet.of(
      "area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link",
      "meta", "param", "source", "track", "wbr");
}
