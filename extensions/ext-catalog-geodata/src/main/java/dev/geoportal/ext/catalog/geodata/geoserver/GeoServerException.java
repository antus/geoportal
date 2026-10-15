// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

/**
 * GeoServer refused a call or did not answer, or the geodata database it reads did not. The message
 * names the call and the status, never the credentials; {@link #reason()} is what GeoServer said, as
 * plain text a person can read (GP-115).
 */
public final class GeoServerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;
    private final boolean geodata;

    public GeoServerException(int status, String message) {
        this(status, message, null, false);
    }

    GeoServerException(int status, String message, String reason, boolean geodata) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.geodata = geodata;
    }

    /** The geodata database, not GeoServer, failed: it did not answer or refused the schema. */
    static GeoServerException geodata(String message) {
        return new GeoServerException(0, message, null, true);
    }

    /** The HTTP status GeoServer answered, 0 when it did not answer. */
    public int status() {
        return status;
    }

    /** What GeoServer answered, as readable text without markup or stack trace, or {@code null}. */
    public String reason() {
        return reason;
    }

    /** Whether the geodata database failed rather than GeoServer. */
    public boolean geodata() {
        return geodata;
    }
}
