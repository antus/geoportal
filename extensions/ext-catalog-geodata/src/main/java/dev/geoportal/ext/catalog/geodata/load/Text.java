// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Text and XML of uploaded files: encodings, and XML read without DTDs or external entities. */
final class Text {

    private Text() {}

    /** UTF-8 without its BOM, or ISO-8859-1 when the bytes are not UTF-8. */
    static String decode(byte[] content) {
        int offset = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, offset, content.length - offset, StandardCharsets.ISO_8859_1);
        }
    }

    static Document xml(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(content));
        } catch (Exception e) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not valid XML: " + e.getMessage());
        }
    }

    /** An element as XML text, with the namespaces it uses. */
    static String serialize(Element element) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            java.io.StringWriter out = new java.io.StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new LoadRefused(Code.UNREADABLE, "A geometry cannot be read: " + e.getMessage());
        }
    }

    static List<Element> children(Node parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    static Element child(Node parent, String localName) {
        for (Element e : children(parent)) {
            if (localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    /** The descendants with a local name, in document order. */
    static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        var nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static final java.util.regex.Pattern INTEGER = java.util.regex.Pattern.compile("^-?(?:0|[1-9]\\d{0,17})$");
    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern.compile(
            "^-?(?:0|[1-9]\\d*)?\\.\\d+(?:[eE][-+]?\\d+)?$|^-?(?:0|[1-9]\\d*)[eE][-+]?\\d+$");
    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * The value of a text cell: a whole number, a decimal number (with a point), a boolean, an ISO
     * date, or the text itself. Codes with leading zeros stay text.
     */
    static Object guess(String cell) {
        if (cell == null) {
            return null;
        }
        String s = cell.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (INTEGER.matcher(s).matches()) {
            return Long.parseLong(s);
        }
        if (DECIMAL.matcher(s).matches()) {
            return Double.parseDouble(s);
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        if (DATE.matcher(s).matches()) {
            try {
                return java.time.LocalDate.parse(s);
            } catch (java.time.format.DateTimeParseException e) {
                return cell;
            }
        }
        return cell;
    }

    static String text(Element element) {
        return element == null ? null : element.getTextContent().strip();
    }
}
