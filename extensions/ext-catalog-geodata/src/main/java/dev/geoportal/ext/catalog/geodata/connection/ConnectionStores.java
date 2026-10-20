// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import dev.geoportal.ext.catalog.geodata.Credentials;
import dev.geoportal.ext.catalog.geodata.geoserver.ConnectionStore;
import dev.mosaikit.kernel.api.secrets.SecretValue;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * GP-986, ADR-0030: the stores through which GeoServer reads the tables of connections, for the
 * alignment: the address the resolution of the reference found, as GeoServer reaches it, and the
 * password of the credential of the connection, read from the secrets service at the moment of the
 * alignment and handed to GeoServer only.
 *
 * <p>The credential is read as the geodata: the administrators decide whether they may, by meaning it for
 * no extension or for the geodata (MK-062). One meant for the connections only cannot be read, and the
 * store is neither made nor updated; its layers fail their health check, saying so.
 *
 * <p>Setting {@code geoportal.geoserver.connection-hosts}: where GeoServer reaches a source that the
 * platform reaches elsewhere, as {@code host:port=host:port} separated by commas, for installations whose
 * GeoServer runs in another network (Docker): {@code 127.0.0.1:15434=postgres:5432}.
 */
@ApplicationScoped
public class ConnectionStores {

    private static final Logger LOG = Logger.getLogger(ConnectionStores.class);

    private final ConnectionLayerRepository layers;
    private final Credentials credentials;

    public ConnectionStores(ConnectionLayerRepository layers, Credentials credentials) {
        this.layers = layers;
        this.credentials = credentials;
    }

    /** The stores of these names, each with the source of its latest resolution and its password when readable. */
    public Map<String, ConnectionStore> stores(Set<String> names) {
        Map<String, ConnectionStore> stores = new HashMap<>();
        if (names.isEmpty()) {
            return stores;
        }
        Map<String, ConnectionLayer> latest = new HashMap<>();
        layers.all().stream()
                .filter(l -> names.contains(l.source().store()))
                .sorted(Comparator.comparing(ConnectionLayer::resolvedAt))
                .forEach(l -> latest.put(l.source().store(), l));
        latest.forEach((name, layer) -> {
            Source source = layer.source();
            String[] where = reached(source.host(), source.port());
            stores.put(name, new ConnectionStore(name, where[0], Integer.parseInt(where[1]), source.database(),
                    source.schema(), source.user(), password(source.credential()), source.sslMode()));
        });
        return stores;
    }

    /** The password of a credential that the geodata may read, or {@code null}. */
    private char[] password(String credential) {
        try {
            Optional<SecretValue> value = credentials.resolve(credential);
            if (value.isEmpty()) {
                LOG.warnf("The credential %s of a connection cannot be read by the geodata: mean it for every"
                        + " extension, or for the geodata, so that GeoServer reads its tables", credential);
                return null;
            }
            try (SecretValue secret = value.get()) {
                return secret.chars();
            }
        } catch (RuntimeException e) {
            LOG.warnf("The credential %s of a connection cannot be read now: %s", credential, e.getMessage());
            return null;
        }
    }

    /** Host and port as GeoServer reaches them, after {@code geoportal.geoserver.connection-hosts}. */
    static String[] reached(String host, int port) {
        String from = host + ":" + port;
        String mapping = ConfigProvider.getConfig()
                .getOptionalValue("geoportal.geoserver.connection-hosts", String.class).orElse("");
        for (String pair : mapping.split(",")) {
            String[] sides = pair.strip().split("=");
            if (sides.length == 2 && sides[0].strip().equalsIgnoreCase(from)) {
                String to = sides[1].strip();
                int colon = to.lastIndexOf(':');
                if (colon > 0) {
                    return new String[] {to.substring(0, colon), to.substring(colon + 1)};
                }
            }
        }
        return new String[] {host, Integer.toString(port)};
    }
}
