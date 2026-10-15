// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import dev.geoportal.ext.catalog.geodata.Credentials;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.secrets.SecretValue;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The names of people and the members of teams, read from the published views of Mosaikit (MK-123,
 * {@code mk_published}) with a login that may read nothing else, as the role service of GeoServer reads
 * them (ADR-0016): the GeoFence rules of a person name them as GeoServer receives them, by their user
 * name. The kernel API of extensions gives the account of a grant, not its name, nor the members of
 * every team to a person who does not see them all.
 *
 * <p>Setting {@code geoportal.catalog.published-views.jdbc-url}, by default the database of the
 * installation ({@code quarkus.datasource.jdbc.url}). Credential of the organization
 * {@value #CREDENTIAL}: {@code <user>:<password>} of a login that holds the role
 * {@code mk_<database>_reader}. It is kept a minute per organization, since Mosaikit audits every read.
 */
@ApplicationScoped
public class PublishedViews {

    public static final String CREDENTIAL = "published-views";
    static final Duration KEEP = Duration.ofMinutes(1);

    private final Credentials secrets;
    private final CurrentOrganization organization;
    private final Map<UUID, Login> logins = new ConcurrentHashMap<>();

    private record Login(String user, String password, Instant until) {
        @Override
        public String toString() {
            return "Login[" + user + "]";
        }
    }

    public PublishedViews(Credentials secrets, CurrentOrganization organization) {
        this.secrets = secrets;
        this.organization = organization;
    }

    /** The views cannot be read: no credential, or the database refused it. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /** The user names of active accounts, by account; accounts not found or suspended are missing. */
    public Map<UUID, String> usernames(Collection<UUID> accounts) {
        Map<UUID, String> names = new HashMap<>();
        if (accounts.isEmpty()) {
            return names;
        }
        query("select id, username from mk_published.account where active and id = any(?)", accounts,
                r -> names.put((UUID) r.getObject(1), r.getString(2)));
        return names;
    }

    /** The people of teams, by team: owners, members and guests, as a grant to a group reaches them. */
    public Map<UUID, Set<UUID>> teamMembers(Collection<UUID> teams) {
        Map<UUID, Set<UUID>> members = new HashMap<>();
        if (teams.isEmpty()) {
            return members;
        }
        query("select team_id, account_id from mk_published.team_member where team_id = any(?)", teams,
                r -> members.computeIfAbsent((UUID) r.getObject(1), k -> new HashSet<>()).add((UUID) r.getObject(2)));
        return members;
    }

    /** A person of an organization, by the address they sign in with; empty when not a member of it. */
    public Optional<UUID> member(UUID organization, String username) {
        List<UUID> found = new ArrayList<>();
        query("select a.id from mk_published.account a join mk_published.membership m on m.account_id = a.id"
                + " where a.active and m.organization_id = any(?) and a.username = lower(?)", List.of(organization),
                username, r -> found.add((UUID) r.getObject(1)));
        return found.stream().findFirst();
    }

    /**
     * The roles GeoServer reads for a person in an organization (GP-339), as the role service of the server
     * distribution reads them from the same views (ADR-0016, {@code rolesdml.xml}): the identifier of the
     * organization for its members and administrators (not its guests), the identifier followed by
     * {@code :admin} for its administrators, the identifier of each of its teams of which the person is an
     * owner or a member. Empty for a person without an active account.
     */
    public Set<String> roles(UUID organization, String username) {
        Set<String> roles = new java.util.TreeSet<>();
        query("""
                with p as (select cast(? as uuid[]) orgs, lower(?) username)
                select m.organization_id::text from mk_published.membership m
                  join mk_published.account a on a.id = m.account_id, p
                  where a.active and a.username = p.username and m.organization_id = any(p.orgs)
                    and ('organization-user' = any(m.roles) or 'organization-admin' = any(m.roles))
                union
                select m.organization_id::text || ':admin' from mk_published.membership m
                  join mk_published.account a on a.id = m.account_id, p
                  where a.active and a.username = p.username and m.organization_id = any(p.orgs)
                    and 'organization-admin' = any(m.roles)
                union
                select t.team_id::text from mk_published.team_member t
                  join mk_published.account a on a.id = t.account_id
                  join mk_published.team tm on tm.id = t.team_id, p
                  where a.active and a.username = p.username and tm.organization_id = any(p.orgs)
                    and t.role in ('owner', 'member')""", List.of(organization), username,
                r -> roles.add(r.getString(1)));
        return roles;
    }

    /** The teams of an organization, by identifier, with their names (never the chats). */
    public Map<UUID, String> teams(UUID organization) {
        Map<UUID, String> teams = new LinkedHashMap<>();
        query("select id, name from mk_published.team where organization_id = any(?) order by name", List.of(organization),
                r -> teams.put((UUID) r.getObject(1), r.getString(2)));
        return teams;
    }

    /** A member of the organization, not a guest, with an active account. */
    public record Member(UUID account, String username, String displayName) {}

    /**
     * The members of the organization of the request who are not guests, with an active account, by
     * name: the people a layer can be assigned to (GP-036).
     */
    public java.util.List<Member> members() {
        java.util.List<Member> members = new java.util.ArrayList<>();
        query("select a.id, a.username, a.display_name from mk_published.membership m"
                + " join mk_published.account a on a.id = m.account_id"
                + " where m.organization_id = any(?) and a.active"
                + " and m.roles && array['organization-admin', 'organization-user']::text[]"
                + " order by lower(coalesce(a.display_name, a.username)), a.username",
                java.util.List.of(organization.require()),
                r -> members.add(new Member((UUID) r.getObject(1), r.getString(2), r.getString(3))));
        return members;
    }

    private interface Row {
        void accept(ResultSet r) throws SQLException;
    }

    private void query(String sql, Collection<UUID> ids, Row row) {
        query(sql, ids, null, row);
    }

    private void query(String sql, Collection<UUID> ids, String text, Row row) {
        Login login = login();
        Properties p = new Properties();
        p.setProperty("user", login.user());
        p.setProperty("password", login.password());
        p.setProperty("connectTimeout", "10");
        try (Connection c = DriverManager.getConnection(url(), p);
                PreparedStatement s = c.prepareStatement(sql)) {
            Array array = c.createArrayOf("uuid", ids.toArray());
            s.setArray(1, array);
            if (text != null) {
                s.setString(2, text);
            }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    row.accept(r);
                }
            }
        } catch (SQLException e) {
            logins.remove(organization.require());
            throw new Unavailable("The published views of Mosaikit cannot be read with the credential " + CREDENTIAL
                    + ": " + e.getMessage());
        }
    }

    private static String url() {
        var config = ConfigProvider.getConfig();
        return config.getOptionalValue("geoportal.catalog.published-views.jdbc-url", String.class)
                .filter(s -> !s.isBlank())
                .or(() -> config.getOptionalValue("quarkus.datasource.jdbc.url", String.class))
                .orElseThrow(() -> new Unavailable("No address of the database of the installation"));
    }

    private Login login() {
        UUID org = organization.require();
        Instant now = Instant.now();
        Login kept = logins.get(org);
        if (kept != null && kept.until().isAfter(now)) {
            return kept;
        }
        // The organization's credential, else the installation's (MK-127): the rules are also written in
        // the handler of an organization event, without a person (GP-334).
        Optional<SecretValue> found = secrets.resolve(CREDENTIAL);
        if (found.isEmpty()) {
            throw new Unavailable("Store the credential " + CREDENTIAL + " (<user>:<password> of a login with the role"
                    + " mk_<database>_reader) for the catalog in the installation credentials, or in Administration,"
                    + " Credentials.");
        }
        try (SecretValue value = found.get()) {
            char[] chars = value.chars();
            String text = new String(chars);
            Arrays.fill(chars, '\0');
            int colon = text.indexOf(':');
            if (colon <= 0) {
                throw new Unavailable("The credential " + CREDENTIAL + " must be <user>:<password>.");
            }
            kept = new Login(text.substring(0, colon), text.substring(colon + 1), now.plus(KEEP));
        }
        logins.put(org, kept);
        return kept;
    }
}
