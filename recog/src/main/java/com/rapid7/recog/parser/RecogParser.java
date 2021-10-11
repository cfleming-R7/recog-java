package com.rapid7.recog.parser;

import com.rapid7.recog.FingerprintExample;
import com.rapid7.recog.RecogMatcher;
import com.rapid7.recog.RecogMatchers;
import com.rapid7.recog.pattern.JavaRegexRecogPatternMatcher;
import com.rapid7.recog.pattern.RecogPatternMatcher;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses {@link RecogMatcher} objects from XML input. This parser has support for strict or lenient
 * parsing mode. In lenient mode parsing is tolerant and imposes the minimal constraints on input
 * required to produce valid objects as outputs. Errors in input are logged and as many objects are
 * parsed as possible. In strict mode, any errors during validation immediately throw exceptions and
 * halt processing.
 */
public class RecogParser {

  /**
   * Factory used to create the underlying {@link RecogPatternMatcher} used
   * when matching inputs against regular expressions.
   */
  public interface PatternMatcherFactory {
    RecogPatternMatcher create(String pattern, int flags);
  }

  /**
   * The default {@link PatternMatcherFactory} uses java.regex.* packages to evaluate
   * regular expressions.
   */
  public static final PatternMatcherFactory DEFAULT_PATTERN_MATCHER_FACTORY =
      (pattern, flags) -> new JavaRegexRecogPatternMatcher(Pattern.compile(pattern, flags));

  private static final Logger LOGGER = LoggerFactory.getLogger(RecogParser.class);
  private final boolean strictMode;
  private final PatternMatcherFactory patternMatcherFactory;

  /**
   * Constructs a parser to parser with non-strict (lenient) parsing mode.
   */
  public RecogParser() {
    this(false);
  }

  /**
   * Constructs a parser to parser with the specified strictness mode.
   *
   * @param strictMode {@code true} if the parser should throw exceptions when any error is
   *        encountered, {@code false} otherwise.
   */
  public RecogParser(boolean strictMode) {
    this(strictMode, DEFAULT_PATTERN_MATCHER_FACTORY);
  }

  /**
   * Constructs a parser with the specified strictness mode and {@link PatternMatcherFactory}.
   *
   * @param strictMode {@code true} if the parser should throw exceptions when any error is
   *        encountered, {@code false} otherwise.
   * @param patternMatcherFactory The {@link PatternMatcherFactory} to be used during parsing.
   */
  public RecogParser(boolean strictMode, PatternMatcherFactory patternMatcherFactory) {
    this.strictMode = strictMode;
    this.patternMatcherFactory = patternMatcherFactory;
  }

  /**
   * Parses {@link RecogMatchers} from the XML content in the specified {@link File}.
   *
   * @param file The content to read from. Must not be {@code null}.
   * @return {@link RecogMatchers} parsed from the file. Will not be {@code null} but may be empty
   *         if no matchers are defined, or all matchers are invalid and strict mode is disabled.
   * @throws ParseException If an error is encountered and strict-mode is enabled.
   */
  public RecogMatchers parse(File file)
      throws ParseException {
    try (Reader reader = new FileReader(file)) {
      return parse(reader, file.getPath(), file.getName().replaceAll(".xml", ""));
    } catch (Exception exception) {
      throw new ParseException("Failed to parse recog fingerprints from file " + file.getAbsolutePath(), exception);
    }
  }

  /**
   * Parses {@link RecogMatchers} from the XML content in the specified {@link Reader}.
   *
   * @param reader The content to read from. Must not be {@code null}.
   * @param name Value used for {@link RecogMatchers} key if parsed value is null or empty.
   * @return {@link RecogMatchers} parsed from the reader. Will not be {@code null} but may be empty
   *         if no matchers are defined, or all matchers are invalid and strict mode is disabled.
   * @throws ParseException If an error is encountered and strict-mode is enabled.
   */
  public RecogMatchers parse(Reader reader, String name)
      throws ParseException {
    return parse(reader, null, name);
  }

  /**
   * Parses {@link RecogMatchers} from the XML content in the specified {@link Reader}.
   *
   * @param reader The content to read from. Must not be {@code null}.
   * @param path Optional XML content file path.
   * @param name Value used for {@link RecogMatchers} key if parsed value is null or empty.
   * @return {@link RecogMatchers} parsed from the reader. Will not be {@code null} but may be empty
   *         if no matchers are defined, or all matchers are invalid and strict mode is disabled.
   * @throws ParseException If an error is encountered and strict-mode is enabled.
   */
  public RecogMatchers parse(Reader reader, String path, String name)
      throws ParseException {
    Document document;
    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      dbFactory.setAttribute("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      dbFactory.setFeature("http://xml.org/sax/features/validation", false);
      dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      document = dbFactory.newDocumentBuilder().parse(new InputSource(reader));
    } catch (SAXException | IOException | IllegalArgumentException | ParserConfigurationException exception) {
      throw new ParseException("Unable to parse fingerprints from Document", exception);
    }

    Element root = document.getDocumentElement();

    String preferenceValue = root.getAttribute("preference");
    float preference = 0;
    try {
      preference = preferenceValue == null || preferenceValue.isEmpty() ? 0 : Float.parseFloat(preferenceValue);
    } catch (NumberFormatException exception) {
      // ignore - use default
    }

    String recogKey = root.getAttribute("matches");

    if (recogKey.isEmpty()) {
      LOGGER.debug("Recog Matcher Key is Empty or Null. File Name: " + name);
      recogKey = name;
    }

    RecogMatchers matchers = new RecogMatchers(path, recogKey, root.getAttribute("protocol"), root.getAttribute("database_type"), preference);

    NodeList fingerprints = root.getElementsByTagName("fingerprint");
    for (int index = 0; index < fingerprints.getLength(); index++) {
      Element fingerprint = (Element) fingerprints.item(index);
      try {
        // the pattern is required
        String pattern = getRequiredAttribute(fingerprint, "pattern");

        // parse the flags for the regular expression
        int regexFlags = parseFlags(fingerprint.getAttribute("flags"));

        // construct a pattern
        RecogMatcher fingerprintPattern = new RecogMatcher(patternMatcherFactory.create(pattern, regexFlags));

        // description (optional)
        NodeList description = fingerprint.getElementsByTagName("description");
        if (description.getLength() > 0)
          fingerprintPattern.setDescription(description.item(0).getTextContent());

        // example (optional)
        NodeList examples = fingerprint.getElementsByTagName("example");
        for (int examplesIndex = 0; examplesIndex < examples.getLength(); examplesIndex++) {
          Element example = (Element) examples.item(examplesIndex);
          String exampleContent = example.getTextContent();

          if ("base64".equals(example.getAttribute("_encoding"))) {
            // TODO: these are currently ignored as the Base64 decoding isn't working properly
          } else {
            HashMap<String, String> attributeMap = new HashMap<>();
            NamedNodeMap exAttributes = example.getAttributes();

            for (int i = 0; i < exAttributes.getLength(); i++) {
              Node attr = exAttributes.item(i);
              String attrName = attr.getNodeName();
              String attrValue = attr.getNodeValue();
              attributeMap.put(attrName, attrValue);
            }

            fingerprintPattern.addExample(new FingerprintExample(exampleContent, attributeMap));
          }
        }

        // parse and add parameter specifications
        NodeList params = fingerprint.getElementsByTagName("param");
        for (int paramIndex = 0; paramIndex < params.getLength(); paramIndex++) {
          Element parameter = (Element) params.item(paramIndex);
          int position = Integer.parseInt(getRequiredAttribute(parameter, "pos"));

          String paramName = getRequiredAttribute(parameter, "name");

          // zero position indicates a "constant" value
          if (position == 0) {
            String paramValue = getRequiredAttribute(parameter, "value");
            fingerprintPattern.addValue(paramName, paramValue);
          } else {
            // otherwise the position indicates a group match result
            String value = parameter.getAttribute("value");
            if (!value.isEmpty()) {
              throw new ParseException(String.format("Attribute \"%s\" has a non-zero position but specifies a value of \"%s\"", paramName, value));
            }
            fingerprintPattern.addParam(position, paramName);
          }
        }

        matchers.add(fingerprintPattern);
      } catch (ParseException | IllegalArgumentException exception) {
        LOGGER.warn("Failed to parse fingerprint.", exception);
        if (strictMode)
          throw exception;
      }
    }

    return matchers;
  }

  /////////////////////////////////////////////////////////////////////////
  // Non-public methods
  /////////////////////////////////////////////////////////////////////////

  private int parseFlags(String flags) {
    int cflags = 0;
    if (flags != null && flags.length() != 0) {
      StringTokenizer tok = new StringTokenizer(flags, "|,; \t");
      while (tok.hasMoreTokens()) {
        switch (tok.nextToken()) {
          case "REG_ICASE":
          case "IGNORECASE":
            cflags |= Pattern.CASE_INSENSITIVE;
            break;
          case "REG_DOT_NEWLINE":
            cflags |= Pattern.DOTALL;
            break;
          case "REG_MULTILINE":
            cflags |= Pattern.MULTILINE;
            break;
          default:
            // ignore any other flags
            break;
        }
      }
    }

    return cflags;
  }

  private String getRequiredAttribute(Element element, String name) throws ParseException {
    String value = element.getAttribute(name);
    if (value.length() == 0)
      throw new ParseException("Attribute \"" + name + "\" does not exist.");

    return value;
  }
}
