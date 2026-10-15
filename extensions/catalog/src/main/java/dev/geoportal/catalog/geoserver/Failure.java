// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * A failure of GeoServer, of the geodata database or of the catalog, explained where the action
 * started (GP-115): a code the page translates, what to do (retry, or correct something first), the
 * reason GeoServer gave as readable text, and a reference that the same failure carries in the log,
 * so that whoever reads the log finds it from what the person saw. Never a stack trace, never the XML
 * of GeoServer, never a credential.
 *
 * @param status the HTTP status of the answer: 400 invalid, 404, 409 conflict, 502 GeoServer refused,
 *     503 GeoServer or the database did not answer
 * @param code what failed: {@code geoserver-unreachable}, {@code geoserver-refused}, {@code
 *     geodata-unreachable}, {@code credential-missing}, {@code name-in-use}, {@code name-taken},
 *     {@code not-loaded}, {@code not-vector}, {@code invalid}, {@code not-found}
 * @param action {@code retry}: the same action may succeed later; {@code correct}: something must
 *     change first (the name, the data, a credential)
 * @param detail what happened, in English, for clients of the API
 * @param cause what GeoServer answered, as readable text, or {@code null}
 * @param reference the reference of the failure in the log
 */
public record Failure(int status, String code, String action, String detail, String cause, String reference) {

    private static final Logger LOG = Logger.getLogger(Failure.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String RETRY = "retry";
    public static final String CORRECT = "correct";

    /** A failure of the request itself, with what to correct. */
    public static Failure correct(int status, String code, String detail) {
        return logged(new Failure(status, code, CORRECT, detail, null, newReference()), null);
    }

    /** A request GeoServer answered with a refusal, such as a WFS transaction it did not apply (GP-036). */
    public static Failure refusedBy(String detail, String cause) {
        return logged(new Failure(502, "geoserver-refused", CORRECT, detail, cause, newReference()), cause);
    }

    /** A change that GeoServer refused during an alignment. */
    public static Failure refused(Alignment.Change change, String what) {
        String detail = "GeoServer refused to " + change.action() + " the " + change.kind() + " " + change.name()
                + " while " + what + ".";
        Failure f = new Failure(502, "geoserver-refused", CORRECT, detail,
                change.cause() != null ? change.cause() : readable(change.error()), newReference());
        return logged(f, change.error());
    }

    /** What an exception of GeoServer, of the database or of the credentials means for the person. */
    public static Failure of(RuntimeException e, String what) {
        if (e instanceof GeoServerException g) {
            if (g.geodata()) {
                return logged(new Failure(503, "geodata-unreachable", RETRY,
                        "The geodata database did not answer while " + what + ".", null, newReference()), g.getMessage());
            }
            if (g.status() == 0) {
                return logged(new Failure(503, "geoserver-unreachable", RETRY,
                        "GeoServer did not answer while " + what + ".", null, newReference()), g.getMessage());
            }
            return logged(new Failure(502, "geoserver-refused", g.status() >= 500 ? RETRY : CORRECT,
                    "GeoServer refused a call while " + what + " (" + g.status() + ").", g.reason(), newReference()),
                    g.getMessage());
        }
        if (e instanceof GeoServerGovernance.MissingCredentialException) {
            return logged(new Failure(409, "credential-missing", CORRECT, e.getMessage(), null, newReference()), null);
        }
        if (e instanceof IllegalArgumentException) {
            return logged(new Failure(400, "invalid", CORRECT, e.getMessage(), null, newReference()), null);
        }
        if (e instanceof IllegalStateException) {
            return logged(new Failure(409, "conflict", CORRECT, e.getMessage(), null, newReference()), null);
        }
        throw e;
    }

    /** The answer of the API: a problem detail with the code, the action, the cause and the reference. */
    public Response response() {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 503 -> "Service Unavailable";
            default -> "Bad Gateway";
        });
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        problem.put("action", action);
        problem.put("cause", cause);
        problem.put("reference", reference);
        return Response.status(status).type("application/problem+json").entity(problem).build();
    }

    /** As a map, for an answer that carries a failure among other things (a health check). */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("action", action);
        m.put("detail", detail);
        m.put("cause", cause);
        m.put("reference", reference);
        return m;
    }

    /** Written to the log with its reference and the technical message, which the person does not see. */
    private static Failure logged(Failure f, String technical) {
        LOG.warnf("[%s] %s %s%s%s", f.reference, f.code, f.detail,
                f.cause == null ? "" : " GeoServer: " + f.cause,
                technical == null ? "" : " (" + technical + ")");
        return f;
    }

    private static String readable(String error) {
        return error == null ? null : GeoServerClient.reason(error);
    }

    static String newReference() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return "GP-" + HexFormat.of().withUpperCase().formatHex(b);
    }
}
