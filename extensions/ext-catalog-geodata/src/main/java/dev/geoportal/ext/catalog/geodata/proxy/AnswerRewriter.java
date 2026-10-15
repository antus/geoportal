// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Text answers of a service on their way to the browser (GP-334, criterion 4): the addresses of the
 * service become the address of its proxy, so that a map that reads the capabilities asks the proxy
 * for the maps too, never the service itself, which would refuse it without the credential; and
 * anything of the credential the service echoes (some error pages print the request headers) is
 * removed. Images and other binary answers pass unchanged.
 *
 * <p>The body is handled as ISO-8859-1, which maps each byte to one character and back: the
 * addresses are ASCII, so the bytes around them are kept whatever the encoding of the document.
 */
public final class AnswerRewriter {

    static final String REMOVED = "[credential removed]";

    private AnswerRewriter() {}

    /** Whether an answer of this type is rewritten: XML, JSON, text, HTML. */
    public static boolean textual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String t = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.contains("xml") || t.contains("json");
    }

    /**
     * The answer of the forwarder, with its text body rewritten; other answers as they are.
     *
     * @param proxy the absolute address of the proxy of the service
     * @param authorization the Authorization header sent to the service, or null
     */
    public static Response apply(Response answer, URI registered, String proxy, String authorization) {
        if (!(answer.getEntity() instanceof StreamingOutput stream) || !textual(answer.getHeaderString("content-type"))) {
            return answer;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] body;
        try {
            stream.write(buffer);
            body = buffer.toByteArray();
            if ("gzip".equalsIgnoreCase(String.valueOf(answer.getHeaderString("content-encoding")).strip())) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
        } catch (IOException e) {
            return Forwarder.error(502, "The answer of the service cannot be read.");
        }
        byte[] rewritten = rewrite(body, registered, proxy, secrets(authorization));
        return Response.fromResponse(answer)
                .entity(rewritten)
                .header("Content-Encoding", null)
                .header("Content-Length", null)
                .header("Content-Length", rewritten.length)
                .build();
    }

    /** The body with the addresses of the service pointing at the proxy, and the secrets removed. */
    static byte[] rewrite(byte[] body, URI registered, String proxy, List<String> secrets) {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        for (String secret : secrets) {
            text = text.replace(secret, REMOVED);
        }
        text = addresses(text, registered, proxy);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String addresses(String text, URI registered, String proxy) {
        Pattern url = Pattern.compile(
                "(?i)\\b(https?)://" + Pattern.quote(registered.getHost()) + "(?::(\\d+))?(/[^\\s\"'<>?#]*)?");
        String base = registered.getRawPath() == null ? "" : registered.getRawPath();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int port = AddressGuard.port(registered);
        Matcher m = url.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            int found = m.group(2) != null ? Integer.parseInt(m.group(2)) : scheme.equals("https") ? 443 : 80;
            if (!scheme.equalsIgnoreCase(registered.getScheme()) || found != port) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String path = m.group(3) == null ? "" : m.group(3);
            String rest = !base.isEmpty() && (path.equals(base) || path.startsWith(base + "/"))
                    ? path.substring(base.length())
                    : "";
            // Another path of the same server (/wms next to a registered /ows) is asked of the
            // registered address: the proxy never leaves it.
            m.appendReplacement(out, Matcher.quoteReplacement(proxy + rest));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** What of an Authorization header could be echoed: the token, and for Basic the pair and the password. */
    static List<String> secrets(String authorization) {
        List<String> secrets = new ArrayList<>();
        if (authorization == null) {
            return secrets;
        }
        int space = authorization.indexOf(' ');
        String token = space < 0 ? authorization : authorization.substring(space + 1).strip();
        secrets.add(latin1(token));
        if (authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String pair = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
                secrets.add(latin1(pair));
                int colon = pair.indexOf(':');
                if (colon >= 0 && pair.length() - colon - 1 >= 4) {
                    secrets.add(latin1(pair.substring(colon + 1)));
                }
            } catch (IllegalArgumentException e) {
                // not Base64: the token alone
            }
        }
        secrets.removeIf(String::isEmpty);
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        return secrets;
    }

    private static String latin1(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
