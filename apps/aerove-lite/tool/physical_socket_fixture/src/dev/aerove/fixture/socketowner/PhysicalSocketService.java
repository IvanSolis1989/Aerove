package dev.aerove.fixture.socketowner;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Dedicated lab APK; the original emulator fixture is intentionally unchanged. */
public final class PhysicalSocketService extends Service {
    private static final String AEROVE = "dev.aerove.lite";
    private static final int OPEN = 1, OBSERVE = 2, CLOSE = 3;
    private HandlerThread worker;
    private Handler handler;
    private Messenger messenger;
    private Socket socket;
    private BufferedInputStream wire;
    private String token;
    private int owner = -1;
    private boolean held;
    private boolean destroyed;
    private final Runnable expiry = new Runnable() {
        @Override public void run() { clear(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("physical-socket-fixture");
        worker.start();
        handler = new Handler(worker.getLooper(), new Handler.Callback() {
            @Override public boolean handleMessage(Message message) { return handle(message); }
        });
        messenger = new Messenger(handler);
    }

    private boolean authorized(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null && packages.length == 1 && AEROVE.equals(packages[0]);
    }

    private synchronized boolean handle(Message message) {
        if (message.replyTo == null) return true;
        Bundle out = new Bundle();
        boolean ownsRequest = false;
        try {
            if (destroyed || !authorized(message.sendingUid))
                throw new IllegalArgumentException("caller-not-authorized");
            Bundle in = message.getData();
            String requested = in.getString("nonce");
            if (message.what == OPEN) {
                if (token != null) throw new IllegalArgumentException("lease-already-open");
                if (requested == null || !UUID.fromString(requested).toString().equals(requested))
                    throw new IllegalArgumentException("invalid-nonce");
                String stage = in.getString("stage", "");
                if (!("on".equals(stage) || "off-before".equals(stage) || "off-after".equals(stage)))
                    throw new IllegalArgumentException("invalid-stage");
                byte[] target = privateIpv4(in.getString("host", ""));
                int port = in.getInt("port");
                if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid-port");
                ownsRequest = true;
                owner = message.sendingUid;
                token = requested;
                held = "on".equals(stage);
                open(InetAddress.getByAddress(target), port, out);
                handler.postDelayed(expiry, 7_000);
            } else {
                if (owner != message.sendingUid || requested == null || !requested.equals(token))
                    throw new IllegalArgumentException("lease-mismatch");
                if (message.what != OBSERVE && message.what != CLOSE)
                    throw new IllegalArgumentException("unsupported-operation");
                ownsRequest = true;
                if (message.what == OBSERVE) {
                    if (!held) throw new IllegalArgumentException("not-active-stage");
                    requireNoEof();
                    out.putLong("wireObservedNs", SystemClock.elapsedRealtimeNanos());
                    tuple(out);
                } else {
                    if (held) {
                        requireNoEof();
                        out.putLong("wireObservedNs", SystemClock.elapsedRealtimeNanos());
                    }
                    socket.close();
                    if (!socket.isClosed()) throw new IOException("socket-not-closed");
                    clear();
                    out.putLong("closedNs", SystemClock.elapsedRealtimeNanos());
                    out.putBoolean("socketClosed", true);
                }
            }
            out.putString("nonce", requested);
            out.putInt("fixtureUid", Process.myUid());
            out.putInt("fixturePid", Process.myPid());
            out.putString("fixturePackage", getPackageName());
        } catch (IllegalArgumentException | IOException failure) {
            if (ownsRequest) clear();
            out.clear();
            out.putString("error", "physical-socket-request-rejected");
        }
        try {
            Message reply = Message.obtain(null, message.what);
            reply.setData(out);
            message.replyTo.send(reply);
        } catch (android.os.RemoteException ignored) {
            if (ownsRequest) clear();
        }
        return true;
    }

    private void open(InetAddress target, int port, Bundle out) throws IOException {
        socket = new Socket(java.net.Proxy.NO_PROXY);
        socket.connect(new InetSocketAddress(target, port), 2_000);
        out.putLong("openedNs", SystemClock.elapsedRealtimeNanos());
        String request = "GET /echo/" + token + " HTTP/1.0\r\nHost: " +
                target.getHostAddress() + ":" + port + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        wire = new BufferedInputStream(socket.getInputStream());
        byte[] expected = ("AEROVE_PHYSICAL_HTTP:" + token).getBytes(StandardCharsets.US_ASCII);
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        String status = line(deadline);
        if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("status");
        int length = -1;
        for (int headers = 0;; headers++) {
            if (headers == 32) throw new IOException("too-many-headers");
            String header = line(deadline);
            if (header.isEmpty()) break;
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                if (length != -1 || !header.substring(15).trim().equals(Integer.toString(expected.length)))
                    throw new IOException("length");
                length = expected.length;
            } else if (lower.startsWith("transfer-encoding:") || lower.startsWith("content-encoding:")) {
                throw new IOException("unsupported-framing");
            }
        }
        if (length != expected.length) throw new IOException("no-length");
        for (byte b : expected)
            if (readByte(deadline) != (b & 255)) throw new IOException("wrong-nonce");
        out.putInt("httpStatus", 200);
        out.putString("body", new String(expected, StandardCharsets.US_ASCII));
        out.putBoolean("explicitProxy", false);
        out.putBoolean("redirected", false);
        tuple(out);
    }

    private int readByte(long deadline) throws IOException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IOException("response-expired");
        socket.setSoTimeout((int) remaining);
        return wire.read();
    }

    private String line(long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 256; i++) {
            int value = readByte(deadline);
            if (value == '\r') {
                if (readByte(deadline) != '\n') throw new IOException("invalid-eol");
                return new String(output.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126) throw new IOException("invalid-header");
            output.write(value);
        }
        throw new IOException("header-too-long");
    }

    private void requireNoEof() throws IOException {
        socket.setSoTimeout(100);
        try {
            int b = wire.read();
            throw new IOException(b == -1 ? "peer-closed" : "unexpected-bytes");
        } catch (SocketTimeoutException stillOpen) {
            // No EOF/data yet. The Native tuple plus the host receipt remain required.
        }
    }

    private void tuple(Bundle out) {
        out.putString("sourceIp", socket.getLocalAddress().getHostAddress());
        out.putInt("sourcePort", socket.getLocalPort());
        out.putString("destinationIp", socket.getInetAddress().getHostAddress());
        out.putInt("destinationPort", socket.getPort());
    }

    static byte[] privateIpv4(String host) {
        String[] fields = host.split("\\.", -1);
        if (fields.length != 4 || "10.0.2.2".equals(host)) throw new IllegalArgumentException("host");
        byte[] bytes = new byte[4];
        int[] parts = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!fields[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("host");
            parts[i] = Integer.parseInt(fields[i]);
            if (parts[i] > 255) throw new IllegalArgumentException("host");
            bytes[i] = (byte) parts[i];
        }
        if (!(parts[0] == 10 || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31 ||
                parts[0] == 192 && parts[1] == 168)) throw new IllegalArgumentException("host");
        return bytes;
    }

    private synchronized void clear() {
        handler.removeCallbacks(expiry);
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        socket = null;
        wire = null;
        token = null;
        owner = -1;
        held = false;
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { clear(); return false; }
    @Override public synchronized void onDestroy() {
        destroyed = true;
        clear();
        worker.quitSafely();
        super.onDestroy();
    }
}
