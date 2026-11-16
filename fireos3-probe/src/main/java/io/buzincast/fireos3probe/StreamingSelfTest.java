package io.buzincast.fireos3probe;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jmdns.ServiceInfo;
import javax.jmdns.impl.DNSIncoming;
import javax.jmdns.impl.DNSRecord;

/** Exercises real DNS/RTSP sockets and the live decoder with synthetic video, not an Apple sender. */
final class StreamingSelfTest {
    static void run(Context context, Surface surface, String identity, CountDownLatch displayed) throws Exception {
        LegacyReceiverSession session = new LegacyReceiverSession(context, surface, identity, new LegacyReceiverSession.Listener() {
            @Override public void ready(String name) {}
            @Override public void failed(Exception error) {}
            @Override public void stopped() {}
        });
        session.start();
        int port = 0;
        try {
            if (!session.ready.await(20, TimeUnit.SECONDS)) throw new IOException("Receiver startup timed out");
            if (session.failure != null) throw session.failure;
            port = session.port;
            if (port == 0) throw new IOException("Receiver stopped during startup");
            checkDiscovery(session.network.address, port);
            checkRtsp(port, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n");
            // /info exercises callbacks which an OPTIONS-only test cannot reach.
            checkRtsp(port, "GET /info RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n");
            replaySample(context, session);
            if (!session.decoded.await(5, TimeUnit.SECONDS)) throw new IOException("Live decoder produced no frame");
            if (!displayed.await(5, TimeUnit.SECONDS)) throw new IOException("Live video Surface was not updated");
        } finally {
            session.stop();
            if (!session.closed.await(10, TimeUnit.SECONDS)) throw new IOException("Receiver shutdown timed out");
        }
        // A successful stop must close the RTSP listener, rather than merely hide the UI.
        try (Socket connection = new Socket()) {
            try { connection.connect(new InetSocketAddress("127.0.0.1", port), 500); }
            catch (java.net.ConnectException expected) { return; }
            throw new IOException("RTSP listener remained open after stop");
        }
    }

    private static void checkRtsp(int port, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); socket.setSoTimeout(2000);
            socket.getOutputStream().write(request.getBytes("US-ASCII"));
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            if (!"RTSP/1.0 200 OK".equals(input.readLine())) throw new IOException("RTSP smoke request failed");
        }
    }

    private static void checkDiscovery(InetAddress address, int expectedPort) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        DataOutputStream query = new DataOutputStream(data);
        query.writeShort(0xB017); query.writeShort(0); query.writeShort(1);
        query.writeShort(0); query.writeShort(0); query.writeShort(0);
        for (String label : "_airplay._tcp.local".split("\\.")) {
            byte[] name = label.getBytes("US-ASCII"); query.writeByte(name.length); query.write(name);
        }
        query.writeByte(0); query.writeShort(12); query.writeShort(1);
        byte[] bytes = data.toByteArray();
        boolean service = false, properties = false, pointer = false;
        int responses = 0;
        try (MulticastSocket client = new MulticastSocket(new InetSocketAddress(address, 0))) {
            client.setNetworkInterface(NetworkInterface.getByInetAddress(address));
            client.setTimeToLive(255);
            client.setLoopbackMode(false);
            client.setSoTimeout(1500);
            long deadline = SystemClock.elapsedRealtime()+8000;
            while (SystemClock.elapsedRealtime() < deadline) {
                client.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("224.0.0.251"), 5353));
                byte[] buffer = new byte[9000]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { client.receive(packet); } catch (SocketTimeoutException retry) { continue; }
                if (packet.getLength() < 12 || (buffer[0]&255) != 0xB0 || (buffer[1]&255) != 0x17) continue;
                DNSIncoming response = new DNSIncoming(packet);
                if (!response.isResponse()) continue;
                responses++;
                for (DNSRecord record : response.getAllAnswers()) {
                    if (record instanceof DNSRecord.Service && record.getName().endsWith("._airplay._tcp.local.")) {
                        service |= ((DNSRecord.Service)record).getPort() == expectedPort;
                    } else if (record instanceof DNSRecord.Text && record.getName().endsWith("._airplay._tcp.local.")) {
                        ServiceInfo info = record.getServiceInfo(false);
                        String features = info.getPropertyString("features"), key = info.getPropertyString("pk");
                        if (features != null && key != null && key.length() == 64) {
                            long bits = Long.parseLong(features.split(",")[0].substring(2), 16);
                            properties |= (bits & (1L<<7)) != 0 && (bits & ((1L<<9)|1L|(1L<<4))) == 0;
                        }
                    } else if (record instanceof DNSRecord.Pointer) {
                        pointer |= ((DNSRecord.Pointer)record).getAlias().endsWith("._airplay._tcp.local.");
                    }
                }
                if (service && properties && pointer) return;
            }
        }
        throw new IOException("DNS-SD incomplete: responses="+responses+", port="+service+", TXT="+properties+", PTR="+pointer);
    }

    private static void replaySample(Context context, LegacyReceiverSession session) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            try (AssetFileDescriptor sample = context.getResources().openRawResourceFd(R.raw.h264_sample)) {
                extractor.setDataSource(sample.getFileDescriptor(), sample.getStartOffset(), sample.getLength());
            }
            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);
            byte[] sps = copy(format.getByteBuffer("csd-0")), pps = copy(format.getByteBuffer("csd-1"));
            ByteArrayOutputStream config = new ByteArrayOutputStream(); config.write(sps); config.write(pps);
            byte[] parameterSets = config.toByteArray();
            if (AvcConfig.fromAnnexB(parameterSets) == null) throw new IOException("Extractor did not expose Annex-B configuration");
            ByteBuffer input = ByteBuffer.allocateDirect(64*1024);
            for (int i=0; i<5; i++) {
                input.clear(); int length = extractor.readSampleData(input, 0);
                if (length < 0) break;
                byte[] sample = new byte[length]; input.position(0); input.get(sample);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                if (i == 0) frame.write(parameterSets);
                frame.write(toAnnexB(sample));
                session.replay(frame.toByteArray(), extractor.getSampleTime()*1000L);
                extractor.advance();
            }
        } finally { extractor.release(); }
    }

    static byte[] toAnnexB(byte[] packet) throws IOException {
        if (packet.length >= 4 && packet[0] == 0 && packet[1] == 0 &&
                (packet[2] == 1 || (packet[2] == 0 && packet[3] == 1))) return packet;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int index=0; index<packet.length;) {
            if (packet.length-index < 4) throw new IOException("Truncated AVC length prefix");
            long length = ((packet[index]&255L)<<24)|((packet[index+1]&255L)<<16)|((packet[index+2]&255L)<<8)|(packet[index+3]&255L);
            index += 4;
            if (length < 1 || length > packet.length-index) throw new IOException("Invalid AVC NAL length");
            output.write(new byte[]{0,0,0,1}); output.write(packet,index,(int)length); index += (int)length;
        }
        return output.toByteArray();
    }

    private static byte[] copy(ByteBuffer buffer) throws IOException {
        if (buffer == null) throw new IOException("Missing AVC parameter set");
        ByteBuffer view = buffer.duplicate(); byte[] result = new byte[view.remaining()]; view.get(result); return result;
    }
}
