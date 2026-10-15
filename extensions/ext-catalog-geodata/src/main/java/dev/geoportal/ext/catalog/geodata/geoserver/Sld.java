// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Compares two SLD documents as XML, not as text: the indentation, the XML declaration and the
 * whitespace between elements do not count, so that a style GeoServer keeps in another layout is not
 * rewritten at every alignment, while any change of an element, attribute or value is seen.
 */
final class Sld {

    private Sld() {}

    static boolean same(String held, String wanted) {
        Document a = parse(held);
        Document b = parse(wanted);
        if (a == null || b == null) {
            return held.strip().equals(wanted.strip());
        }
        return a.getDocumentElement().isEqualNode(b.getDocumentElement());
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document d = b.parse(new InputSource(new StringReader(xml)));
            strip(d.getDocumentElement());
            d.normalizeDocument();
            return d;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return null;
        }
    }

    /** Removes comments and whitespace-only text, and trims the remaining text. */
    private static void strip(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.COMMENT_NODE
                    || (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().isBlank())) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                child.setNodeValue(child.getNodeValue().strip());
            } else {
                strip(child);
            }
            child = next;
        }
    }
}
