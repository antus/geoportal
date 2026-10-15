// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.editing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The checks of a WFS transaction the catalog forwards to GeoServer for a surveyor (GP-036): a
 * {@code Transaction} of WFS 1.0, 1.1 or 2.0 whose every action (insert, update, delete, replace) names
 * only the one layer it is sent to, qualified or not, in the namespace of the organization when it names
 * one; no {@code Native} action, no DTD and no external entity. A pure function, so that what is
 * refused is tested without GeoServer.
 */
public final class WfsTransaction {

    /** The largest transaction the catalog forwards. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final String WFS = "http://www.opengis.net/wfs";
    private static final String WFS2 = "http://www.opengis.net/wfs/2.0";

    private WfsTransaction() {}

    /** What a transaction does, by action: how many inserts, updates, deletes and replaces. */
    public record Summary(Map<String, Integer> actions) {}

    /** A transaction the catalog does not forward, and why. */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * Checks a transaction for a layer.
     *
     * @param workspace the workspace of the organization, a prefix GeoServer accepts undeclared
     * @param namespace the namespace URI of the workspace of the organization
     * @return what it does
     * @throws Refused when it is not a transaction on that layer alone
     */
    public static Summary check(byte[] body, String layer, String workspace, String namespace) throws Refused {
        if (body == null || body.length == 0) {
            throw new Refused("Send a WFS Transaction in the body.");
        }
        if (body.length > MAX_BYTES) {
            throw new Refused("The transaction is larger than " + MAX_BYTES / (1024 * 1024) + " MB.");
        }
        Document doc = parse(body);
        Element root = doc.getDocumentElement();
        if (!"Transaction".equals(root.getLocalName()) || !isWfs(root.getNamespaceURI())) {
            throw new Refused("Only a WFS Transaction is accepted.");
        }
        Map<String, Integer> actions = new LinkedHashMap<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element action)) {
                continue;
            }
            if (!isWfs(action.getNamespaceURI())) {
                throw new Refused("Unknown element " + action.getLocalName() + " in the transaction.");
            }
            String name = action.getLocalName();
            switch (name) {
                case "Insert", "Replace" -> {
                    boolean any = false;
                    for (Node f = action.getFirstChild(); f != null; f = f.getNextSibling()) {
                        if (f instanceof Element feature) {
                            any = true;
                            requireLayer(feature.getLocalName(), feature.getNamespaceURI(), layer, namespace);
                        }
                    }
                    if (!any) {
                        throw new Refused(name + " without a feature.");
                    }
                }
                case "Update", "Delete" -> {
                    String typeName = action.getAttribute("typeName");
                    if (typeName.isBlank()) {
                        throw new Refused(name + " without typeName.");
                    }
                    String prefix = typeName.contains(":") ? typeName.substring(0, typeName.indexOf(':')) : null;
                    String local = typeName.substring(typeName.indexOf(':') + 1);
                    String uri = prefix == null ? null : action.lookupNamespaceURI(prefix);
                    if (prefix != null && uri == null) {
                        // GeoServer reads an undeclared prefix as the name of a workspace.
                        if (!prefix.equals(workspace)) {
                            throw new Refused("The transaction names " + typeName + " in another workspace.");
                        }
                        uri = namespace;
                    }
                    requireLayer(local, uri, layer, namespace);
                }
                case "LockId" -> {
                    // the lock of the transaction (WFS 1.1)
                }
                case "Native" -> throw new Refused("Native actions are not accepted.");
                default -> throw new Refused("Unknown action " + name + " in the transaction.");
            }
            if (!"LockId".equals(name)) {
                actions.merge(name.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            }
        }
        if (actions.isEmpty()) {
            throw new Refused("The transaction has no action.");
        }
        return new Summary(actions);
    }

    private static void requireLayer(String local, String uri, String layer, String namespace) throws Refused {
        if (!layer.equals(local)) {
            throw new Refused("The transaction names " + local + ": only the layer " + layer + " is edited here.");
        }
        if (uri != null && !uri.isEmpty() && !uri.equals(namespace)) {
            throw new Refused("The transaction names " + local + " in another namespace.");
        }
    }

    private static boolean isWfs(String uri) {
        return WFS.equals(uri) || WFS2.equals(uri);
    }

    private static Document parse(byte[] body) throws Refused {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(body));
        } catch (SAXException | IOException e) {
            throw new Refused("The transaction is not well-formed XML, or declares a DTD.");
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What went wrong in the answer of GeoServer to a transaction, as text; empty when it was applied. */
    public static Optional<String> failure(String answer) {
        if (answer == null) {
            return Optional.of("GeoServer gave no answer.");
        }
        if (answer.contains("ExceptionReport") || answer.contains("ServiceException")) {
            int start = answer.indexOf("ExceptionText>");
            start = start < 0 ? answer.indexOf("ServiceException") : start + "ExceptionText>".length();
            String text = answer.substring(Math.max(0, start)).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            return Optional.of(text.length() > 300 ? text.substring(0, 300) : text);
        }
        if (answer.contains("FAILED")) {
            return Optional.of("GeoServer did not apply the transaction.");
        }
        return Optional.empty();
    }
}
