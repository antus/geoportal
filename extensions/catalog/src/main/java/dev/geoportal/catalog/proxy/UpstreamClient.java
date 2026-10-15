// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.proxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * An HTTP/1.1 client that connects to an address already checked by {@link AddressGuard}, never
 * resolving the name again, while it still sends the name as {@code Host}, as SNI and checks it
 * against the certificate of the server. The clients of the JDK resolve by themselves, which is
 * exactly the second resolution a DNS rebinding needs.
 *
 * <p>One request per connection ({@code Connection: close}), no redirect followed (the caller
 * decides, with the guard), headers of at most 64 KiB, and a read timeout on every read.
 */
public final class UpstreamClient {

    /** Longest status line and headers of an answer. */
    static final int MAX_HEADER_BYTES = 64 * 1024;

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final SSLSocketFactory tls;

    public UpstreamClient(int connectTimeoutMillis, int readTimeoutMillis, SSLSocketFactory tls) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.tls = Objects.requireNonNull(tls, "tls");
    }

    /** A request: method, absolute address, headers (without Host and Connection) and body. */
    public record Request(String method, URI uri, Map<String, String> headers, byte[] body) {

        public Request {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(uri, "uri");
            headers = new LinkedHashMap<>(headers == null ? Map.of() : headers);
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    /** The answer of the service: status, headers by lowercase name, and the body as it arrives. */
    public static final class Answer implements Closeable {
        private final Socket socket;
        private final int status;
        private final Map<String, List<String>> headers;
        private final InputStream body;
        private final long length;

        Answer(Socket socket, int status, Map<String, List<String>> headers, InputStream body, long length) {
            this.socket = socket;
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.length = length;
        }

        public int status() {
            return status;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        /** The body, de-chunked; empty for HEAD, 204 and 304. */
        public InputStream body() {
            return body;
        }

        /** The length the service declared with Content-Length, or -1. */
        public long length() {
            return length;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                // nothing to do: the connection is gone either way
            }
        }
    }

    /**
     * Sends the request to the first address that accepts the connection, in the order given, and
     * reads the status and headers of the answer; the body is read by the caller, then closed.
     */
    public Answer send(Request request, List<InetAddress> addresses) throws IOException {
        if (addresses.isEmpty()) {
            throw new IOException("No address to connect to");
        }
        URI uri = request.uri();
        String host = AddressGuard.host(uri);
        int port = AddressGuard.port(uri);
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        IOException last = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
                socket.setSoTimeout(readTimeoutMillis);
                Socket channel = secure ? secure(socket, host, port) : socket;
                write(channel.getOutputStream(), request, host, port, secure);
                return read(channel, request.method());
            } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
                close(socket);
                last = e;
                if (socket.isConnected()) {
                    throw e;
                }
            } catch (IOException | RuntimeException e) {
                close(socket);
                throw e;
            }
        }
        throw last;
    }

    private Socket secure(Socket socket, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) tls.createSocket(socket, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        // The certificate must be for the registered name, although the connection goes to an address.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.contains(":") && !host.matches("[0-9.]+")) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static void write(OutputStream raw, Request request, String host, int port, boolean secure)
            throws IOException {
        URI uri = request.uri();
        String target = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        StringBuilder head = new StringBuilder(512);
        head.append(token(request.method())).append(' ').append(clean(target)).append(" HTTP/1.1\r\n");
        boolean defaultPort = port == (secure ? 443 : 80);
        String hostHeader = host.contains(":") ? "[" + host + "]" : host;
        head.append("Host: ").append(clean(defaultPort ? hostHeader : hostHeader + ":" + port)).append("\r\n");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            head.append(token(header.getKey())).append(": ").append(clean(header.getValue())).append("\r\n");
        }
        byte[] body = request.body();
        if (body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        OutputStream out = new java.io.BufferedOutputStream(raw, 8192);
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) {
            out.write(body);
        }
        out.flush();
    }

    /** A header name or method: letters, digits and the token characters of HTTP only. */
    private static String token(String value) {
        if (value == null || !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Not an HTTP token");
        }
        return value;
    }

    /** A header value or request target: no control characters, so nothing can be injected. */
    private static String clean(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f || c > 0xff) {
                throw new IllegalArgumentException("Control or non-Latin-1 character in a header");
            }
        }
        return value;
    }

    private static Answer read(Socket socket, String method) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
        int[] budget = {MAX_HEADER_BYTES};
        while (true) {
            String status = line(in, budget);
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || !parts[1].matches("\\d{3}")) {
                throw new IOException("The service did not answer HTTP/1.x");
            }
            int code = Integer.parseInt(parts[1]);
            Map<String, List<String>> headers = new TreeMap<>();
            for (String header = line(in, budget); !header.isEmpty(); header = line(in, budget)) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    throw new IOException("Malformed header in the answer of the service");
                }
                String name = header.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                headers.computeIfAbsent(name, k -> new java.util.ArrayList<>())
                        .add(header.substring(colon + 1).strip());
            }
            if (code >= 100 && code < 200) {
                continue; // 100 Continue and the like: the real answer follows
            }
            boolean empty = method.equals("HEAD") || code == 204 || code == 304;
            String encoding = first(headers, "transfer-encoding");
            String declared = first(headers, "content-length");
            long length = -1;
            if (declared != null) {
                try {
                    length = Long.parseLong(declared.strip());
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed Content-Length in the answer of the service");
                }
                if (length < 0) {
                    throw new IOException("Malformed Content-Length in the answer of the service");
                }
            }
            InputStream body;
            if (empty) {
                body = InputStream.nullInputStream();
            } else if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                body = new Chunked(in);
                length = -1; // Content-Length next to chunked is ignored, as HTTP says
            } else if (length >= 0) {
                body = new Bounded(in, length);
            } else {
                body = in; // until the service closes the connection
            }
            return new Answer(socket, code, headers, body, empty ? -1 : length);
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** One line of status or header, without CRLF, within what remains of the budget of headers. */
    static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The service closed the connection in the headers");
            }
            if (--budget[0] < 0) {
                throw new IOException("The headers of the answer of the service are too long");
            }
            if (b == '\n') {
                byte[] read = bytes.toByteArray();
                int end = read.length > 0 && read[read.length - 1] == '\r' ? read.length - 1 : read.length;
                return new String(read, 0, end, StandardCharsets.ISO_8859_1);
            }
            bytes.write(b);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing to do
        }
    }

    /** A body framed by Content-Length. */
    static final class Bounded extends FilterInputStream {
        private long remaining;

        Bounded(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The service closed the connection before the end of the body");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n < 0) {
                throw new EOFException("The service closed the connection before the end of the body");
            }
            remaining -= n;
            return n;
        }
    }

    /** A body in chunks, read back as the bytes it carries; trailers are dropped. */
    static final class Chunked extends InputStream {
        private final InputStream in;
        private long chunk;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (done) {
                return -1;
            }
            if (chunk == 0) {
                int[] budget = {1024};
                String size = line(in, budget);
                int semicolon = size.indexOf(';');
                String hex = (semicolon < 0 ? size : size.substring(0, semicolon)).strip();
                if (!hex.matches("[0-9a-fA-F]{1,15}")) {
                    throw new IOException("Malformed chunk in the answer of the service");
                }
                chunk = Long.parseLong(hex, 16);
                if (chunk == 0) {
                    int[] trailers = {MAX_HEADER_BYTES};
                    while (!line(in, trailers).isEmpty()) {
                        // trailers are not forwarded
                    }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(buffer, offset, (int) Math.min(length, chunk));
            if (n < 0) {
                throw new EOFException("The service closed the connection inside a chunk");
            }
            chunk -= n;
            if (chunk == 0) {
                int[] budget = {2};
                if (!line(in, budget).isEmpty()) {
                    throw new IOException("Malformed chunk in the answer of the service");
                }
            }
            return n;
        }
    }
}
