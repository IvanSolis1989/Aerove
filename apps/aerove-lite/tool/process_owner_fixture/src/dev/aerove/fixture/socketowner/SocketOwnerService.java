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
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SocketOwnerService extends Service {
    private HandlerThread worker;
    private Messenger messenger;
    private Socket tcp;
    private DatagramSocket udp;
    private String token;
    private int caller = -1;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("socket-owner-fixture");
        worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), new Handler.Callback() {
            @Override public boolean handleMessage(Message message) {
                return SocketOwnerService.this.handle(message);
            }
        }));
    }

    private boolean handle(Message message) {
        Bundle response = new Bundle();
        try {
            Bundle input = message.getData();
            if (!Arrays.asList(getPackageManager().getPackagesForUid(message.sendingUid))
                    .contains("dev.aerove.lite")) throw new SecurityException("Only the target test may own this fixture");
            String requested = input.getString("token");
            if (message.what == 1) {
                if (token != null) throw new IllegalStateException("Fixture is already owned");
                if (requested == null || !requested.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Missing nonce");
                caller = message.sendingUid;
                token = requested;
                tcp = new Socket();
                tcp.connect(new InetSocketAddress("10.0.2.2", input.getInt("tcpPort")), 5000);
                tcp.setSoTimeout(5000);
                byte[] payload = token.getBytes(StandardCharsets.US_ASCII);
                tcp.getOutputStream().write(payload);
                byte[] received = new byte[payload.length];
                int offset = 0;
                while (offset < received.length) {
                    int size = tcp.getInputStream().read(received, offset, received.length - offset);
                    if (size < 0) throw new IllegalStateException("TCP echo closed before nonce");
                    offset += size;
                }
                if (!Arrays.equals(payload, received)) throw new IllegalStateException("TCP nonce differs");
                udp = new DatagramSocket();
                udp.connect(InetAddress.getByName("10.0.2.2"), input.getInt("udpPort"));
                udp.setSoTimeout(5000);
                udp.send(new DatagramPacket(payload, payload.length));
                DatagramPacket packet = new DatagramPacket(new byte[payload.length + 1], payload.length + 1);
                udp.receive(packet);
                if (packet.getLength() != payload.length || !Arrays.equals(payload, Arrays.copyOf(packet.getData(), packet.getLength())))
                    throw new IllegalStateException("UDP nonce differs");
            } else if (caller != message.sendingUid || token == null || !token.equals(requested)) {
                throw new SecurityException("Fixture lease differs");
            }
            if (message.what == 3) {
                closeSockets();
                response.putBoolean("closed", true);
            } else if (message.what == 1 || message.what == 2) {
                if (tcp == null || tcp.isClosed() || !tcp.isConnected() || udp == null || udp.isClosed() || !udp.isConnected())
                    throw new IllegalStateException("Owned sockets are not live");
                response.putString("tcpSource", endpoint(tcp.getLocalAddress(), tcp.getLocalPort()));
                response.putString("tcpTarget", endpoint(tcp.getInetAddress(), tcp.getPort()));
                response.putString("udpSource", endpoint(udp.getLocalAddress(), udp.getLocalPort()));
                response.putString("udpTarget", endpoint(udp.getInetAddress(), udp.getPort()));
                response.putBoolean("live", true);
            } else throw new IllegalArgumentException("Unknown fixture operation");
            response.putString("token", requested);
            response.putInt("uid", Process.myUid());
            response.putInt("pid", Process.myPid());
            response.putString("package", getPackageName());
        } catch (Exception failure) {
            closeSockets();
            response.putString("error", failure.toString());
        }
        Log.i("AEROVE_SOCKET_OWNER", "operation=" + message.what + " uid=" + Process.myUid() + " result=" + response);
        try {
            Message reply = Message.obtain(null, message.what);
            reply.setData(response);
            message.replyTo.send(reply);
        } catch (Exception failure) { closeSockets(); }
        return true;
    }

    private static String endpoint(InetAddress address, int port) {
        return "[" + address.getHostAddress() + "]:" + port;
    }

    private synchronized void closeSockets() {
        if (tcp != null) try { tcp.close(); } catch (Exception ignored) { }
        if (udp != null) udp.close();
        tcp = null;
        udp = null;
        token = null;
        caller = -1;
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { closeSockets(); return false; }
    @Override public void onDestroy() { closeSockets(); worker.quitSafely(); super.onDestroy(); }
}
