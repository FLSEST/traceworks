package traceworks.selfaudit.build;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A small XML tree from the JDK's SAX parser: elements by their local name, their text and the line they start on.
 * What it reads is data from outside (a POM of the audited project, a registry's metadata), so a document type
 * declaration is refused, no external entity or schema is loaded, and nesting deeper than {@value #MAX_DEPTH} or more
 * than {@value #MAX_ELEMENTS} elements are refused.
 */
final class Xml {

    static final int MAX_DEPTH = 64;
    static final int MAX_ELEMENTS = 200_000;

    /** One element: its local name, the line its start tag ends on, its text (trimmed) and its child elements. */
    record Element(String name, int line, String text, List<Element> children) {

        /** The first child of that name, or {@code null}. */
        Element child(String name) {
            for (Element c : children) {
                if (c.name.equals(name)) return c;
            }
            return null;
        }

        /** The children of that name. */
        List<Element> all(String name) {
            List<Element> out = new ArrayList<>();
            for (Element c : children) {
                if (c.name.equals(name)) out.add(c);
            }
            return out;
        }

        /** The elements at a path of names below this one, such as {@code build/plugins/plugin}. */
        List<Element> path(String path) {
            List<Element> level = List.of(this);
            for (String part : path.split("/")) {
                List<Element> next = new ArrayList<>();
                for (Element e : level) next.addAll(e.all(part));
                level = next;
            }
            return level;
        }

        /** The text of the first child of that name, or {@code ""}. */
        String text(String name) {
            Element c = child(name);
            return c == null ? "" : c.text;
        }
    }

    private Xml() {
    }

    /**
     * The root element of {@code text}.
     *
     * @throws IllegalArgumentException if the text is no well-formed XML, declares a document type, or is too deep or
     *                                  too large
     */
    static Element parse(String text) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Builder builder = new Builder();
            parser.parse(new InputSource(new StringReader(text)), builder);
            if (builder.root == null) throw new IllegalArgumentException("no root element");
            return builder.root;
        } catch (SAXException e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage());
        } catch (ParserConfigurationException | IOException e) {
            throw new IllegalArgumentException("XML not readable: " + e.getMessage());
        }
    }

    private static final class Builder extends DefaultHandler {
        private final Deque<Open> open = new ArrayDeque<>();
        private Locator locator;
        private Element root;
        private int elements;

        private record Open(String name, int line, StringBuilder text, List<Element> children) {
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            if (open.size() >= MAX_DEPTH) throw new SAXException("nested deeper than " + MAX_DEPTH);
            if (++elements > MAX_ELEMENTS) throw new SAXException("more than " + MAX_ELEMENTS + " elements");
            String name = localName == null || localName.isEmpty() ? qName : localName;
            open.push(new Open(name, locator == null ? 0 : locator.getLineNumber(), new StringBuilder(),
                    new ArrayList<>()));
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!open.isEmpty()) open.peek().text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            Open done = open.pop();
            Element element = new Element(done.name, done.line, done.text.toString().strip(),
                    List.copyOf(done.children));
            if (open.isEmpty()) root = element;
            else open.peek().children.add(element);
        }
    }
}
