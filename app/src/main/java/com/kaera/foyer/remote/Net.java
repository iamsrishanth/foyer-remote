package com.kaera.foyer.remote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Tiny HTTP + UDP helpers. No third-party libraries. */
public final class Net {

    public static final int DISCOVERY_PORT = 8485;
    private static final String PROBE = "FOYER_DISCOVER";

    private Net() {
    }

    public static String get(String url, int timeoutMs) throws Exception {
        return getResult(url, timeoutMs).body;
    }

    /** GET with the status kept, for callers that must tell 200 from 503. */
    public static Resp getResult(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        try {
            return readResult(c);
        } finally {
            c.disconnect();
        }
    }

    public static String post(String url, String json, int timeoutMs) throws Exception {
        return postResult(url, json, timeoutMs).body;
    }

    /**
     * A reply with its HTTP status kept. Callers need the status: the old helper handed a 401/503
     * error body back as if it were a normal answer, which is how a rejected command used to look
     * like success.
     */
    public static final class Resp {
        public final int code;
        public final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    public static Resp postResult(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] body = json.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.flush();
        os.close();
        try {
            return readResult(c);
        } finally {
            c.disconnect();
        }
    }

    private static Resp readResult(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return new Resp(code, "");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new Resp(code, new String(bo.toByteArray(), "UTF-8"));
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    public static class Device {
        public final String ip;
        public final int port;
        public final String name;
        public final boolean paired;

        Device(String ip, int port, String name, boolean paired) {
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.paired = paired;
        }

        public String label() {
            return name + "  ·  " + ip;
        }
    }

    // ---- classified command POST -------------------------------------------------------------

    /** How a command left the phone. Only REFUSED/NOT_SENT prove the key did not land. */
    public enum Kind {
        /** A complete HTTP reply arrived. */
        OK,
        /** Connection refused: the projector is not listening. Definitely not delivered. */
        REFUSED,
        /** Nothing was written (host unknown, network unreachable, local failure). */
        NOT_SENT,
        /** Written but no complete reply: the key may or may not have been applied. */
        UNKNOWN,
        /** The request was refused by the HTTP layer before any reply (bad URL). */
        REJECTED
    }

    public static final class Reply {
        public final Kind kind;
        public final int code;
        public final String body;

        Reply(Kind kind, int code, String body) {
            this.kind = kind;
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return kind == Kind.OK && code >= 200 && code < 300;
        }
    }

    /**
     * A command POST that keeps "never sent" and "sent but unconfirmed" apart.
     *
     * <p>{@link HttpURLConnection} reports both a failed connect and a stalled read as
     * {@code SocketTimeoutException}, which is exactly the distinction the remote needs: a connect
     * failure may be retried or routed elsewhere, a read failure must never be replayed because the
     * key may already have been injected. Speaking HTTP/1.1 over a socket with separate connect and
     * read timeouts keeps that difference visible.
     *
     * <p>Deliberately minimal: one request per connection ({@code Connection: close}), no chunked
     * encoding, no redirects. The projector's server answers exactly this shape.
     */
    public static Reply postClassified(String host, int port, String path, String json,
                                       int connectMs, int readMs) {
        java.net.Socket socket = new java.net.Socket();
        try {
            socket.connect(new java.net.InetSocketAddress(host, port), connectMs);
        } catch (java.net.ConnectException refused) {
            closeQuietly(socket);
            return new Reply(Kind.REFUSED, 0, "");
        } catch (Exception other) {
            closeQuietly(socket);
            return new Reply(Kind.NOT_SENT, 0, "");
        }
        try {
            socket.setSoTimeout(readMs);
            byte[] body = json.getBytes("UTF-8");
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
            // Past this point the request is out of our hands: every failure is UNKNOWN.
            byte[] raw = readFully(socket.getInputStream());
            return parseHttp(raw);
        } catch (Exception unknown) {
            return new Reply(Kind.UNKNOWN, 0, "");
        } finally {
            closeQuietly(socket);
        }
    }

    private static Reply parseHttp(byte[] raw) {
        if (raw == null || raw.length == 0) return new Reply(Kind.UNKNOWN, 0, "");
        String text = new String(raw, java.nio.charset.Charset.forName("UTF-8"));
        int lineEnd = text.indexOf('\n');
        if (lineEnd < 0) return new Reply(Kind.UNKNOWN, 0, "");
        String statusLine = text.substring(0, lineEnd).trim();
        int code = 0;
        try {
            String[] parts = statusLine.split(" ");
            if (parts.length >= 2) code = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
            return new Reply(Kind.UNKNOWN, 0, "");
        }
        if (code == 0) return new Reply(Kind.UNKNOWN, 0, "");
        int split = text.indexOf("\r\n\r\n");
        String body = split >= 0 ? text.substring(split + 4) : "";
        return new Reply(Kind.OK, code, body);
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static void closeQuietly(java.net.Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    /** Broadcasts a probe on the local network and collects replies for a moment. */
    public static List<Device> discover(int waitMs) {
        List<Device> out = new ArrayList<Device>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(350);
            byte[] probe = PROBE.getBytes("UTF-8");
            socket.send(new DatagramPacket(probe, probe.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));

            long until = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < until) {
                try {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    socket.receive(r);
                    String msg = new String(r.getData(), 0, r.getLength(), "UTF-8").trim();
                    String[] parts = msg.split("\\|");
                    if (parts.length >= 5 && "FOYER".equals(parts[0])) {
                        out.add(new Device(r.getAddress().getHostAddress(),
                                Integer.parseInt(parts[2]), parts[3], "paired".equals(parts[4])));
                    }
                } catch (Exception ignored) {
                    // timeout slice, keep listening until the window closes
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
        }
        return out;
    }
}
