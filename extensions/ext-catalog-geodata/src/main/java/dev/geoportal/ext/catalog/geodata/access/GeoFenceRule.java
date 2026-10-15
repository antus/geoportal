// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule of GeoFence as the catalog writes it (GP-335): who (a user name, a role, or nobody for
 * everyone), where (the workspace of the organization, a layer or the whole workspace), which service
 * and request, and the access: {@code ALLOW}, {@code DENY}, or {@code LIMIT} with an area. An
 * {@code ALLOW} may carry the limits of a data rule: a CQL filter on the rows read and the access to
 * each column, and, for the transactions of a surveyor limited by data rules, a CQL filter on the rows written.
 *
 * <p>Its priority is not part of it: the catalog writes the rules of an organization in order, and the
 * order is the priority.
 *
 * @param area for {@code LIMIT}, the allowed area as {@code SRID=<code>;MULTIPOLYGON(...)}, else null
 * @param cqlRead the CQL filter of the rows read, or null
 * @param attributes the access to each column, {@code NONE} or {@code READONLY}, in the order of the
 *     table; empty for every column
 * @param extras what a rule read back from GeoFence holds that the catalog never writes (an address
 *     range, a write filter, ...), so that a rule changed by hand never matches; empty when written
 */
public record GeoFenceRule(
        String user,
        String role,
        String workspace,
        String layer,
        String service,
        String request,
        Access access,
        String area,
        String cqlRead,
        Map<String, String> attributes,
        Map<String, String> extras) {

    /** The access of a rule. */
    public enum Access {
        ALLOW,
        DENY,
        LIMIT
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    public GeoFenceRule {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(access, "access");
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(extras));
        if (area != null && access != Access.LIMIT) {
            throw new IllegalArgumentException("Only a LIMIT rule has an area");
        }
        if ((cqlRead != null || !attributes.isEmpty()) && access != Access.ALLOW) {
            throw new IllegalArgumentException("Only an ALLOW rule has a row filter or columns");
        }
    }

    static GeoFenceRule allow(String user, String role, String workspace, String layer, String service) {
        return new GeoFenceRule(user, role, workspace, layer, service, null, Access.ALLOW, null, null, null, null);
    }

    static GeoFenceRule deny(String user, String role, String workspace, String layer, String service, String request) {
        return new GeoFenceRule(user, role, workspace, layer, service, request, Access.DENY, null, null, null, null);
    }

    /** Where GeoFence keeps how an area limits: {@code INTERSECT}, the default, or {@code CLIP}. */
    static final String SPATIAL_FILTER = "limits.spatialFilterType";

    /**
     * The same {@code LIMIT} rule, its area clipping what is served instead of selecting what intersects it
     * (GP-309): for a raster, the image is cut to the area. Read back from GeoFence, the rule carries the same
     * {@link #extras}, so the comparison holds.
     */
    public GeoFenceRule clipped() {
        if (access != Access.LIMIT || area == null) {
            return this;
        }
        Map<String, String> more = new TreeMap<>(extras);
        more.put(SPATIAL_FILTER, "CLIP");
        return new GeoFenceRule(user, role, workspace, layer, service, request, access, area, cqlRead, attributes, more);
    }

    /** Where GeoFence keeps the CQL filter of the rows a person may write. */
    static final String WRITE_FILTER = "layerDetails.cqlFilterWrite";

    /**
     * The same {@code ALLOW} rule, with a CQL filter of the rows written (GP-036 with GP-308, ADR-0025): GeoServer
     * refuses an insert of a feature outside it and updates or deletes only the features inside it. Read back
     * from GeoFence, the rule carries the same {@link #extras}, so the comparison holds.
     */
    public GeoFenceRule writing(String cql) {
        if (access != Access.ALLOW || layer == null) {
            throw new IllegalArgumentException("Only an ALLOW rule on a layer has a write filter");
        }
        if (cql == null) {
            return this;
        }
        Map<String, String> more = new TreeMap<>(extras);
        more.put(WRITE_FILTER, cql);
        return new GeoFenceRule(user, role, workspace, layer, service, request, access, area, cqlRead, attributes, more);
    }

    /** The CQL filter of the rows written, or null. */
    public String cqlWrite() {
        return extras.get(WRITE_FILTER);
    }

    /** Whether the rule carries the limits of a data rule (a row filter, columns, or a write filter). */
    public boolean hasDetails() {
        return cqlRead != null || !attributes.isEmpty() || cqlWrite() != null;
    }

    /**
     * What GeoFence takes as the same rule: a second rule with these fields is refused with 500
     * "Duplicate Rule", whatever its priority, and an {@code ALLOW} and a {@code DENY} collide, a
     * {@code LIMIT} does not collide with them (measured on 2.28.5).
     */
    public String duplicateKey() {
        return String.join("|", s(user), s(role), workspace, s(layer), s(service), s(request),
                access == Access.LIMIT ? "LIMIT" : "ACCESS");
    }

    // Writing --------------------------------------------------------------------------------------

    /** The format that keeps the rule: JSON for an area, XML otherwise (it keeps the columns). */
    public boolean writtenAsXml() {
        return area == null;
    }

    /** The body of {@code POST /rest/geofence/rules} at a priority, in {@link #writtenAsXml the format that keeps it}. */
    public String body(long priority) {
        return writtenAsXml() ? xml(priority) : json(priority);
    }

    String json(long priority) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode rule = root.putObject("Rule");
        rule.put("priority", priority);
        putIf(rule, "userName", user);
        putIf(rule, "roleName", role);
        rule.put("workspace", workspace);
        putIf(rule, "layer", layer);
        putIf(rule, "service", service);
        putIf(rule, "request", request);
        rule.put("access", access.name());
        if (area != null) {
            ObjectNode limits = rule.putObject("limits").put("allowedArea", area).put("catalogMode", "HIDE");
            String spatial = extras.get(SPATIAL_FILTER);
            if (spatial != null) {
                limits.put("spatialFilterType", spatial);
            }
        }
        return root.toString();
    }

    String xml(long priority) {
        StringBuilder b = new StringBuilder("<Rule><priority>").append(priority).append("</priority>");
        element(b, "userName", user);
        element(b, "roleName", role);
        element(b, "workspace", workspace);
        element(b, "layer", layer);
        element(b, "service", service);
        element(b, "request", request);
        element(b, "access", access.name());
        if (hasDetails()) {
            b.append("<layerDetails><layerType>VECTOR</layerType>");
            element(b, "cqlFilterRead", cqlRead);
            element(b, "cqlFilterWrite", cqlWrite());
            attributes.forEach((name, accessType) -> {
                b.append("<attribute>");
                element(b, "name", name);
                element(b, "accessType", accessType);
                b.append("</attribute>");
            });
            b.append("</layerDetails>");
        }
        return b.append("</Rule>").toString();
    }

    // Reading back ---------------------------------------------------------------------------------

    /** A rule as GeoFence answers it in {@code GET /rest/geofence/rules.json}. */
    public static GeoFenceRule read(JsonNode rule) {
        Map<String, String> extras = new TreeMap<>();
        for (String field : List.of("addressRange", "validAfter", "validBefore", "subfield", "instance")) {
            extra(extras, field, rule.path(field));
        }
        JsonNode limits = rule.path("limits");
        String area = text(limits.path("allowedArea"));
        if (!limits.isMissingNode() && !limits.isNull()) {
            String spatial = text(limits.path("spatialFilterType"));
            if (spatial != null && !spatial.equals("INTERSECT")) {
                extras.put("limits.spatialFilterType", spatial);
            }
            String mode = text(limits.path("catalogMode"));
            if (mode != null && !mode.equals("HIDE")) {
                extras.put("limits.catalogMode", mode);
            }
        }
        JsonNode details = rule.path("layerDetails");
        String cql = null;
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!details.isMissingNode() && !details.isNull()) {
            cql = text(details.path("cqlFilterRead"));
            for (String field : List.of("cqlFilterWrite", "allowedArea", "defaultStyle", "catalogMode")) {
                extra(extras, "layerDetails." + field, details.path(field));
            }
            String spatial = text(details.path("spatialFilterType"));
            if (spatial != null && !spatial.equals("INTERSECT")) {
                extras.put("layerDetails.spatialFilterType", spatial);
            }
            if (details.path("allowedStyles").size() > 0) {
                extras.put("layerDetails.allowedStyles", details.path("allowedStyles").toString());
            }
            for (JsonNode a : details.path("attributes")) {
                attributes.put(a.path("name").asText(), a.path("accessType").asText());
            }
        }
        String access = rule.path("access").asText("");
        Access kind;
        try {
            kind = Access.valueOf(access);
        } catch (IllegalArgumentException e) {
            kind = Access.DENY;
            extras.put("access", access);
        }
        String ws = text(rule.path("workspace"));
        if (area != null && kind != Access.LIMIT) {
            extras.put("limits.allowedArea", area);
            area = null;
        }
        if ((cql != null || !attributes.isEmpty()) && kind != Access.ALLOW) {
            extras.put("layerDetails", cql + " " + attributes);
            cql = null;
            attributes.clear();
        }
        return new GeoFenceRule(text(rule.path("userName")), text(rule.path("roleName")), ws == null ? "" : ws,
                text(rule.path("layer")), text(rule.path("service")), text(rule.path("request")), kind, area, cql,
                attributes, extras);
    }

    /**
     * The rule in one line, the same for a rule written and the same rule read back: the area with
     * its numbers and spaces normalized (GeoFence answers {@code MULTIPOLYGON (((...} for
     * {@code MULTIPOLYGON(((...}), the columns sorted (GeoFence answers them in another order).
     */
    public String canonical() {
        StringBuilder b = new StringBuilder(access.name());
        b.append(" user=").append(s(user)).append(" role=").append(s(role)).append(" ws=").append(workspace)
                .append(" layer=").append(s(layer)).append(" service=").append(s(service))
                .append(" request=").append(s(request));
        if (area != null) {
            b.append(" area=").append(wkt(area));
        }
        if (cqlRead != null) {
            b.append(" cql=").append(cqlRead.strip());
        }
        if (!attributes.isEmpty()) {
            b.append(" columns=").append(new TreeMap<>(attributes));
        }
        if (!extras.isEmpty()) {
            b.append(" extras=").append(extras);
        }
        return b.toString();
    }

    /** What a person reads in the reconciliation report: who, where, what. */
    public String describe() {
        StringBuilder b = new StringBuilder(access.name());
        if (user != null) {
            b.append(" user ").append(user);
        } else if (role != null) {
            b.append(" role ").append(role);
        } else {
            b.append(" everyone");
        }
        b.append(" on ").append(layer == null ? workspace : layer);
        if (service != null) {
            b.append(' ').append(service);
        }
        if (request != null) {
            b.append(' ').append(request);
        }
        if (area != null) {
            b.append(" with an area");
        }
        if (cqlRead != null) {
            b.append(" rows ").append(cqlRead);
        }
        long hidden = attributes.values().stream().filter("NONE"::equals).count();
        if (hidden > 0) {
            b.append(" hiding ").append(hidden).append(hidden == 1 ? " column" : " columns");
        }
        if (cqlWrite() != null) {
            b.append(" writing rows ").append(cqlWrite());
        }
        Map<String, String> others = new TreeMap<>(extras);
        others.remove(WRITE_FILTER);
        if (!others.isEmpty()) {
            b.append(" ").append(others.keySet());
        }
        return b.toString();
    }

    /** A WKT with one space between coordinates, none around brackets and commas, numbers as written by BigDecimal. */
    static String wkt(String wkt) {
        String upper = wkt.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),;=])\\s*", "$1");
        Matcher m = NUMBER.matcher(upper);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String n;
            try {
                n = new BigDecimal(m.group()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                n = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(n));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void extra(Map<String, String> extras, String name, JsonNode value) {
        String v = text(value);
        if (v != null) {
            extras.put(name, v);
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String v = n.isValueNode() ? n.asText() : n.toString();
        return v.isEmpty() ? null : v;
    }

    private static String s(String v) {
        return v == null ? "*" : v;
    }

    private static void putIf(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static void element(StringBuilder b, String name, String value) {
        if (value != null) {
            b.append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>');
        }
    }

    static String escape(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** The rules in canonical form, for a fingerprint or a comparison. */
    static List<String> canonical(List<GeoFenceRule> rules) {
        List<String> out = new ArrayList<>(rules.size());
        rules.forEach(r -> out.add(r.canonical()));
        return out;
    }
}
