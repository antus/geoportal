// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import dev.geoportal.ext.catalog.geodata.access.PublishedViews;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data rules where they cannot be applied (GP-309): a rule on a layer of an external service makes that
 * layer unavailable to the people it names, directly or through a team, since another server cannot be
 * made to filter what it answers; a rule on the whole service makes the service unavailable. The proxy
 * refuses their requests that name such a layer, and says why; a protection that silently gives up on the
 * hard case is worse than none.
 *
 * <p>What a person may not use is computed once per service every {@link #KEEP} at most, since a map asks
 * the proxy for tiles by the dozen; a rule added or removed is seen at once ({@link #changed}).
 */
@ApplicationScoped
public class ServiceRules {

    /** How long the layers a person may not use are kept. */
    static final Duration KEEP = Duration.ofSeconds(15);

    /** The parameters of OGC requests that name layers. */
    static final Set<String> LAYER_PARAMETERS = Set.of("layers", "layer", "query_layers", "typename", "typenames",
            "coverage", "coverageid", "identifier");

    private final ServiceDataRules rules;
    private final PublishedViews views;
    private final CurrentOrganization organization;
    private final SecurityIdentity identity;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    private record Kept(Unavailable unavailable, Instant until) {}

    public ServiceRules(ServiceDataRules rules, PublishedViews views, CurrentOrganization organization,
            SecurityIdentity identity) {
        this.rules = rules;
        this.views = views;
        this.organization = organization;
        this.identity = identity;
    }

    /**
     * What a person may not use of a service because of data rules.
     *
     * @param whole whether a rule names the whole service
     * @param layers the layers named by rules, as the service names them
     */
    public record Unavailable(boolean whole, Set<String> layers) {
        static final Unavailable NONE = new Unavailable(false, Set.of());

        public boolean none() {
            return !whole && layers.isEmpty();
        }
    }

    /** Why a request is refused: the layer, null for the whole service, and the message. */
    public record Refusal(String layer, String message) {}

    /** The message that says why a layer, or the whole service when null, is not available. */
    public static String message(String layer) {
        return (layer == null
                ? "A data rule limits what you see of this service"
                : "A data rule limits what you see of the layer " + layer)
                + ": the service is outside Geoportal and cannot apply it, so it is not available to you.";
    }

    /** A data rule was added or removed: what people may not use is computed again. */
    public void changed() {
        UUID org = organization.require();
        kept.keySet().removeIf(k -> k.startsWith(org + "|"));
    }

    /** What the person of the request may not use of a service. */
    @Transactional
    public Unavailable unavailable(UUID service) {
        String me = identity.isAnonymous() ? null : identity.getPrincipal().getName();
        if (me == null) {
            return Unavailable.NONE;
        }
        UUID org = organization.require();
        String key = org + "|" + service + "|" + me.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Kept k = kept.get(key);
        if (k != null && k.until().isAfter(now)) {
            return k.unavailable();
        }
        Unavailable u = compute(org, service, me);
        kept.put(key, new Kept(u, now.plus(KEEP)));
        return u;
    }

    private Unavailable compute(UUID org, UUID service, String me) {
        List<ServiceDataRule> of = rules.ofService(service);
        if (of.isEmpty()) {
            return Unavailable.NONE;
        }
        Optional<UUID> account;
        Map<UUID, Set<UUID>> members;
        try {
            account = views.member(org, me);
            Set<UUID> teams = new HashSet<>();
            of.stream().filter(r -> ServiceDataRule.TEAM.equals(r.subjectKind())).forEach(r -> teams.add(r.subjectId()));
            members = teams.isEmpty() ? Map.of() : views.teamMembers(teams);
        } catch (PublishedViews.Unavailable e) {
            // Fail closed: who the rules name cannot be told, so the service is unavailable to everybody but
            // the rules say why.
            return new Unavailable(true, Set.of());
        }
        if (account.isEmpty()) {
            return Unavailable.NONE;
        }
        boolean whole = false;
        Set<String> layers = new LinkedHashSet<>();
        for (ServiceDataRule r : of) {
            boolean applies = ServiceDataRule.PERSON.equals(r.subjectKind())
                    ? r.subjectId().equals(account.get())
                    : members.getOrDefault(r.subjectId(), Set.of()).contains(account.get());
            if (applies) {
                if (r.layer() == null) {
                    whole = true;
                } else {
                    layers.add(r.layer());
                }
            }
        }
        return new Unavailable(whole, layers);
    }

    /**
     * Why a request to the proxy of a service is refused by data rules, if it is: when a rule names the whole
     * service, any request; else a request that names one of the layers of the rules in its query or in its
     * path, and a POST, whose body the proxy does not read.
     */
    public static Optional<Refusal> refusal(Unavailable u, String method, String path, String rawQuery) {
        if (u.none()) {
            return Optional.empty();
        }
        if (u.whole()) {
            return Optional.of(new Refusal(null, message(null)));
        }
        if ("POST".equals(method)) {
            String layer = u.layers().iterator().next();
            return Optional.of(new Refusal(layer, message(layer)));
        }
        for (String named : named(path, rawQuery)) {
            for (String layer : u.layers()) {
                if (same(named, layer)) {
                    return Optional.of(new Refusal(layer, message(layer)));
                }
            }
        }
        return Optional.empty();
    }

    /** The layer names a request carries: the values of the layer parameters, and the segments of its path. */
    static List<String> named(String path, String rawQuery) {
        List<String> names = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
                if (eq < 0 || !LAYER_PARAMETERS.contains(key)) {
                    continue;
                }
                for (String v : decode(pair.substring(eq + 1)).split(",")) {
                    if (!v.isBlank()) {
                        names.add(v.strip());
                    }
                }
            }
        }
        if (path != null) {
            for (String segment : path.split("/")) {
                if (!segment.isBlank()) {
                    names.add(decode(segment));
                }
            }
        }
        return names;
    }

    /** The same layer, qualified or not: {@code ns:roads}, {@code roads}. */
    static boolean same(String named, String layer) {
        if (named.equalsIgnoreCase(layer)) {
            return true;
        }
        String a = named.substring(named.lastIndexOf(':') + 1);
        String b = layer.substring(layer.lastIndexOf(':') + 1);
        return a.equalsIgnoreCase(b) && (!named.contains(":") || !layer.contains(":"));
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
