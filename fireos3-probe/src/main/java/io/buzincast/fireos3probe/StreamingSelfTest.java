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
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real DNS/RTSP and registered audio/video callbacks with synthetic packets, not an Apple sender. */
final class StreamingSelfTest {
    static void run(Context context, Surface surface, String identity, CountDownLatch displayed) throws Exception {
        LegacyReceiverSession session = new LegacyReceiverSession(context, surface, identity, new LegacyReceiverSession.Listener() {
            @Override public void ready(String name) {}
            @Override public void failed(Exception error) {}
            @Override public void stopped() {}
        }, true);
        session.start();
        int port = 0;
        try {
            AudioSelfTest.run(context);
            if (!session.ready.await(20, TimeUnit.SECONDS)) throw new IOException("Receiver startup timed out");
            if (session.failure != null) throw session.failure;
            port = session.port;
            if (port == 0) throw new IOException("Receiver stopped during startup");
            checkDiscovery(session.network.address, port);
            checkRtsp(port, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n");
            // /info exercises callbacks which an OPTIONS-only test cannot reach.
            checkRtsp(port, "GET /info RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n");
            checkVolume(port, session);
            replayAudio(context, session);
            checkAvSync(context, session);
            replaySample(context, session);
            if (!session.decoded.await(5, TimeUnit.SECONDS)) throw new IOException("Live decoder produced no frame");
            if (!displayed.await(5, TimeUnit.SECONDS)) throw new IOException("Live video Surface was not updated");
        } finally {
            session.stop();
            if (!session.closed.await(10, TimeUnit.SECONDS)) throw new IOException("Receiver shutdown timed out");
            if (session.failure != null) throw session.failure;
            if (session.audioPlayer.running || session.audioPlayer.outputActive) throw new IOException("Audio survived receiver stop");
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
        query.writeShort(0xB017); query.writeShort(0); query.writeShort(2);
        query.writeShort(0); query.writeShort(0); query.writeShort(0);
        for (String type : new String[]{"_airplay._tcp.local", "_raop._tcp.local"}) {
            for (String label : type.split("\\.")) {
                byte[] name = label.getBytes("US-ASCII"); query.writeByte(name.length); query.write(name);
            }
            query.writeByte(0); query.writeShort(12); query.writeShort(1);
        }
        byte[] bytes = data.toByteArray();
        boolean service = false, properties = false, pointer = false;
        boolean audioService = false, audioProperties = false, audioPointer = false;
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
                    } else if (record instanceof DNSRecord.Service && record.getName().endsWith("._raop._tcp.local.")) {
                        audioService |= ((DNSRecord.Service)record).getPort() == expectedPort;
                    } else if (record instanceof DNSRecord.Text && record.getName().endsWith("._raop._tcp.local.")) {
                        ServiceInfo info = record.getServiceInfo(false);
                        audioProperties |= "1,2,3".equals(info.getPropertyString("cn")) &&
                                "44100".equals(info.getPropertyString("sr")) && info.getPropertyString("md") == null;
                    } else if (record instanceof DNSRecord.Text && record.getName().endsWith("._airplay._tcp.local.")) {
                        ServiceInfo info = record.getServiceInfo(false);
                        String features = info.getPropertyString("features"), key = info.getPropertyString("pk");
                        if (features != null && key != null && key.length() == 64) {
                            long bits = Long.parseLong(features.split(",")[0].substring(2), 16);
                            properties |= (bits & (1L<<7)) != 0 && (bits & (1L<<9)) != 0 && (bits & (1L|(1L<<4)|(1L<<18))) == 0;
                        }
                    } else if (record instanceof DNSRecord.Pointer) {
                        pointer |= ((DNSRecord.Pointer)record).getAlias().endsWith("._airplay._tcp.local.");
                        audioPointer |= ((DNSRecord.Pointer)record).getAlias().endsWith("._raop._tcp.local.");
                    }
                }
                if (service && properties && pointer && audioService && audioProperties && audioPointer) return;
            }
        }
        throw new IOException("DNS-SD incomplete: responses="+responses+", port="+service+", TXT="+properties+", PTR="+pointer+
                ", audio="+audioService+"/"+audioProperties+"/"+audioPointer);
    }

    private static void checkVolume(int port, LegacyReceiverSession session) throws IOException {
        String payload = "volume: -6.0\r\n";
        checkRtsp(port, "SET_PARAMETER * RTSP/1.0\r\nCSeq: 3\r\nContent-Type: text/parameters\r\nContent-Length: "+
                payload.length()+"\r\n\r\n"+payload);
        if (session.senderVolume() != -6) throw new IOException("RTSP sender volume callback failed");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); socket.setSoTimeout(2000);
            String request = "GET_PARAMETER * RTSP/1.0\r\nCSeq: 4\r\nContent-Type: text/parameters\r\nContent-Length: 8\r\n\r\nvolume\r\n";
            socket.getOutputStream().write(request.getBytes("US-ASCII"));
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            if (!"RTSP/1.0 200 OK".equals(input.readLine())) throw new IOException("RTSP volume query failed");
            String line;
            do { line = input.readLine(); } while (line != null && !line.isEmpty());
            String volume = input.readLine();
            if (volume == null || !volume.startsWith("volume:") || Double.parseDouble(volume.substring(7).trim()) != -6)
                throw new IOException("RTSP returned the wrong sender volume");
        }
    }

    private static void replayAudio(Context context, LegacyReceiverSession session) throws Exception {
        JSONArray fixtures = AudioSelfTest.fixtures(context);
        for (int f=0; f<fixtures.length(); f++) {
            JSONObject fixture = fixtures.getJSONObject(f);
            int codec = fixture.getInt("codec"), spf = fixture.getInt("samplesPerFrame");
            JSONArray packets = fixture.getJSONArray("packets");
            int baseline = session.audioPlayer.playEvents;
            long pts = System.currentTimeMillis()*1000000L;
            for (int n=0; n<packets.length(); n++) {
                session.replayAudio(Base64.decode(packets.getString(n), Base64.DEFAULT), codec, n==0 ? spf : 0,
                        pts, -12, n==0);
                pts += spf*1000000000L/44100;
            }
            long deadline = SystemClock.elapsedRealtime()+5000;
            while (session.audioPlayer.playEvents == baseline && SystemClock.elapsedRealtime() < deadline) {
                if (session.failure != null) throw session.failure;
                Thread.sleep(20);
            }
            if (session.audioPlayer.playEvents <= baseline || session.audioPlayer.decodedSamples <= 0 ||
                    session.senderVolume() != -12 || session.audioPlayer.playedFrames == 0)
                throw new IOException("Native receiver audio did not play: " + codec + "/" + spf);
        }
        // An abruptly vanished sender must not leave a PCM writer running forever.
        long idleDeadline = SystemClock.elapsedRealtime()+4000;
        while (session.audioPlayer.outputActive && SystemClock.elapsedRealtime() < idleDeadline) Thread.sleep(20);
        if (session.audioPlayer.outputActive) throw new IOException("Idle audio output was not released");
        JSONObject last = fixtures.getJSONObject(fixtures.length()-1);
        int baseline = session.audioPlayer.playEvents;
        JSONArray resumed = last.getJSONArray("packets");
        long pts = System.currentTimeMillis()*1000000L;
        for (int n=0; n<resumed.length(); n++) {
            session.replayAudio(Base64.decode(resumed.getString(n), Base64.DEFAULT), last.getInt("codec"),
                    0, pts, -12, false);
            pts += last.getInt("samplesPerFrame")*1000000000L/44100;
        }
        long restartDeadline = SystemClock.elapsedRealtime()+3000;
        while (session.audioPlayer.playEvents == baseline && SystemClock.elapsedRealtime() < restartDeadline) Thread.sleep(20);
        if (session.audioPlayer.playEvents <= baseline) throw new IOException("Idle audio did not restart");
        // A flush must stop output even without a following packet/format.
        session.replayAudio(new byte[0], 2, 0, 0, -144, true);
        long deadline = SystemClock.elapsedRealtime()+2000;
        while (session.audioPlayer.outputActive && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        if (session.audioPlayer.outputActive) throw new IOException("Audio flush kept the output alive");
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

    private static void checkAvSync(Context context, LegacyReceiverSession session) throws Exception {
        byte[] video = firstVideoPacket(context);
        // The API17 decoder needs a flow of input frames before its first output.
        // Warm it up outside the measured scheduling interval.
        int initial=session.videoPlayer.renderedFrames;
        replaySample(context,session);
        long warmup=SystemClock.elapsedRealtime()+3000;
        while (session.videoPlayer.renderedFrames == initial && SystemClock.elapsedRealtime()<warmup) {
            if (session.failure != null) throw session.failure;
            Thread.sleep(5);
        }
        if (session.videoPlayer.renderedFrames == initial) throw new IOException("H.264 clock test decoder did not warm up");
        JSONObject fixture = AudioSelfTest.fixtures(context).getJSONObject(0);
        byte[] packet = Base64.decode(fixture.getJSONArray("packets").getString(0), Base64.DEFAULT);
        long pts = System.currentTimeMillis()*1000000L;
        // Bounded bursts build about a second of timestamped PCM without flooding
        // the network handoff. No wall-clock comparison is used by the renderer.
        for (int i=0;i<150;i++) {
            session.replayAudio(packet,2,i==0?352:0,pts,-12,i==0);
            pts += 352*1000000000L/44100;
            if (i%4==3) Thread.sleep(5);
        }
        long deadline=SystemClock.elapsedRealtime()+3000;
        while (session.playbackClock.positionNs(System.nanoTime()) == AudioPlaybackClock.UNKNOWN &&
                SystemClock.elapsedRealtime()<deadline) Thread.sleep(5);
        long audio=session.playbackClock.positionNs(System.nanoTime());
        if (audio == AudioPlaybackClock.UNKNOWN) throw new IOException("Live PCM has no playback clock");
        int waited=session.videoPlayer.waitedFrames, rendered=session.videoPlayer.renderedFrames;
        long target=audio+150000000L;
        for (int i=0;i<3;i++) session.replay(video,target+i*1000000L);
        deadline=SystemClock.elapsedRealtime()+2000;
        while ((session.videoPlayer.waitedFrames<=waited || session.videoPlayer.lastRenderedPtsNs<target) &&
                SystemClock.elapsedRealtime()<deadline) {
            if (session.failure != null) throw session.failure;
            Thread.sleep(5);
        }
        if (session.videoPlayer.waitedFrames<=waited || session.videoPlayer.renderedFrames<=rendered || session.videoPlayer.lastRenderedPtsNs<target ||
                session.videoPlayer.lastRenderDelayNs == AudioPlaybackClock.UNKNOWN || Math.abs(session.videoPlayer.lastRenderDelayNs)>25000000L)
            throw new IOException("Ahead-of-audio video was not synchronized: waited="+session.videoPlayer.waitedFrames+
                    ", rendered="+session.videoPlayer.renderedFrames+", dropped="+session.videoPlayer.droppedFrames+
                    ", queued="+session.videoPlayer.queuedFrames+", outputs="+session.videoPlayer.outputFrames+
                    ", delay="+session.videoPlayer.lastDelayNs+", clock="+session.playbackClock.positionNs(System.nanoTime()));
        int dropped=session.videoPlayer.droppedFrames;
        audio=session.playbackClock.positionNs(System.nanoTime());
        if (audio == AudioPlaybackClock.UNKNOWN) throw new IOException("Audio clock expired before late-frame test");
        for (int i=0;i<3;i++) session.replay(video,audio-500000000L+i*1000000L);
        deadline=SystemClock.elapsedRealtime()+2000;
        while (session.videoPlayer.droppedFrames == dropped && SystemClock.elapsedRealtime()<deadline) Thread.sleep(5);
        if (session.videoPlayer.droppedFrames<=dropped) throw new IOException("Late video was not dropped");
        long beforeFlush=session.playbackClock.generation();
        session.replayAudio(new byte[0],2,0,0,-144,true);
        deadline=SystemClock.elapsedRealtime()+200;
        while (session.playbackClock.generation() == beforeFlush &&
                SystemClock.elapsedRealtime()<deadline) Thread.sleep(5);
        if (session.playbackClock.generation() == beforeFlush || session.playbackClock.positionNs(System.nanoTime()) != AudioPlaybackClock.UNKNOWN)
            throw new IOException("Flush preserved the old audio clock");
    }

    private static byte[] firstVideoPacket(Context context) throws IOException {
        MediaExtractor extractor=new MediaExtractor();
        try {
            try (AssetFileDescriptor sample=context.getResources().openRawResourceFd(R.raw.h264_sample)) {
                extractor.setDataSource(sample.getFileDescriptor(),sample.getStartOffset(),sample.getLength());
            }
            extractor.selectTrack(0);
            MediaFormat format=extractor.getTrackFormat(0);
            ByteArrayOutputStream frame=new ByteArrayOutputStream();
            frame.write(copy(format.getByteBuffer("csd-0"))); frame.write(copy(format.getByteBuffer("csd-1")));
            ByteBuffer buffer=ByteBuffer.allocateDirect(64*1024);
            int length=extractor.readSampleData(buffer,0);
            if (length<=0) throw new IOException("Missing synthetic IDR frame");
            byte[] bytes=new byte[length]; buffer.position(0); buffer.get(bytes);
            frame.write(toAnnexB(bytes));
            byte[] result=frame.toByteArray();
            if (AvcConfig.fromAnnexB(result) == null) throw new IOException("Missing synthetic video configuration");
            return result;
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
