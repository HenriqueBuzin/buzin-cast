package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import io.buzincast.fireos3probe.LegacyHttps;
import io.buzincast.fireos3probe.LegacyDacp;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.fireos3probe.LegacyUrlDownload;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicReference;

/** Public media with platform roots, plus isolated negative trust/hostname fixtures. */
final class LegacyHttpsScenario {
    static void check(Instrumentation runner,Activity activity) throws Exception {
        JSONArray results=new JSONArray();JSONObject evidence=new JSONObject().put("probes",results);
        try {
            for(String location:new String[]{"https://media.w3.org/2010/05/sintel/trailer.mp4","https://www.amazon.com/","https://www.google.com/"}) {
                for(boolean tls12:new boolean[]{false,true}) {
                    JSONObject row=new JSONObject().put("url",location).put("tls12_enabled",tls12);results.put(row);
                    HttpsURLConnection connection=(HttpsURLConnection)new URL(location).openConnection();
                    connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod("HEAD");
                    if(tls12)LegacyHttps.configure(connection);
                    try {row.put("status",connection.getResponseCode()).put("cipher",connection.getCipherSuite());}
                    catch(Exception failure) {row.put("error",failure.toString());}
                    finally {connection.disconnect();}
                }
            }
            JSONObject media=results.getJSONObject(1);
            if(media.optInt("status")!=200)throw new AssertionError("Authenticated public media unavailable: "+media);
            policy(activity,evidence);
            play(runner,activity,evidence);
            android.graphics.Bitmap photo=LegacyPhotoLoader.load(activity,"https://www.w3.org/Icons/w3c_home.png");
            if(photo.getWidth()<1 || photo.getHeight()<1)throw new AssertionError("HTTPS photo was empty");
            photo.recycle();evidence.put("photo_https_redirect","pass");
            if(BuildConfig.VIDEO_DOWNLOAD_ENABLED)download(activity,evidence);
            else evidence.put("download","restricted_edition_not_invoked");
        } finally {
            try(FileOutputStream output=new FileOutputStream(new File(activity.getFilesDir(),"legacy-https.json"))) {
                output.write(evidence.toString(2).getBytes("UTF-8"));
            }
        }
        boolean worked=false;
        for(int i=0;i<results.length();i++) {JSONObject row=results.getJSONObject(i);if(row.getBoolean("tls12_enabled") && row.has("status"))worked=true;}
        if(!worked)throw new AssertionError("No authenticated public HTTPS connection; inspect legacy-https.json");
    }
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(30);}
        throw new AssertionError("HTTPS state did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static void play(Instrumentation runner,Activity activity,JSONObject evidence) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {LegacyDownloadScenario.ui(runner,()-> {try {owner.set((LegacyReceiverService)LegacyDownloadScenario.field(activity,"service"));}catch(Exception error) {throw new AssertionError(error);}});return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        try {
            // API17 emulator's software decoder rejects the Sintel High Profile
            // stream. Exercise a public Baseline stream here; still export
            // the original High Profile stream without decoding it below.
            LegacyDownloadScenario.ui(runner,()->service.loadUrl(new LegacyUrlRequest("https://media.w3.org/2010/05/video/movie_300.mp4",0,null)));
            await(()-> {
                LegacyUrlPlayer.State state=LegacyDownloadScenario.player(runner,service);
                if(state!=null && state.failure!=null)throw new AssertionError("HTTPS player failed",state.failure);
                return state!=null && state.phase==LegacyUrlPlayer.Phase.READY && state.width>0 && state.playing && state.positionMs>500;
            });
            LegacyDownloadScenario.ui(runner,()->service.control(LegacyDacp.Command.PAUSE,null));
            await(()->!LegacyDownloadScenario.player(runner,service).playing);
            LegacyDownloadScenario.ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));
            await(()->LegacyDownloadScenario.player(runner,service).playing);
            evidence.put("media3_public_https_play_pause_resume","baseline_320x240_pass");
        } finally {LegacyDownloadScenario.ui(runner,service::stopUrl);}
    }
    private static void download(Activity activity,JSONObject evidence) throws Exception {
        File file=new File(activity.getCacheDir(),"https-check.mp4");
        AtomicReference<LegacyUrlDownload.State> state=new AtomicReference<>();
        LegacyUrlDownload download=new LegacyUrlDownload(activity,state::set);
        try {
            download.start(new LegacyUrlRequest("https://media.w3.org/2010/05/sintel/trailer.mp4",0,null),file);
            await(()-> {
                LegacyUrlDownload.State value=state.get();
                if(value!=null && value.phase==LegacyUrlDownload.Phase.FAILED)throw new AssertionError("HTTPS download failed",value.failure);
                return value!=null && value.phase==LegacyUrlDownload.Phase.SAVED;
            });
            if(file.length()<1024 || file.length()>16*1024*1024)throw new AssertionError("Unexpected HTTPS export size");
            // Export this public sample solely for independent host validation.
            // Record API17's view diagnostically; the host must prove both
            // encoded tracks and fully decode the result before this test passes.
            byte[] exported=new byte[(int)file.length()];
            try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(file))) {input.readFully(exported);}
            LegacyPinScenario.write(activity,"legacy-https-export.b64",android.util.Base64.encodeToString(exported,android.util.Base64.NO_WRAP));
            android.media.MediaExtractor extractor=new android.media.MediaExtractor();
            try {
                extractor.setDataSource(file.getPath());JSONArray formats=new JSONArray();
                for(int i=0;i<extractor.getTrackCount();i++)formats.put(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME));
                evidence.put("api17_export_extractor_mimes",formats);
            } finally {extractor.release();}
            evidence.put("media3_public_https_mp4_export","saved_requires_independent_host_check");
        } finally {
            download.close();await(()->state.get()!=null && state.get().phase==LegacyUrlDownload.Phase.CLOSED);
            if(file.exists() && !file.delete())throw new AssertionError("HTTPS test file cleanup failed");
            if(new File(file.getPath()+".part").exists())throw new AssertionError("HTTPS export left a partial file");
        }
    }
    private static void policy(Activity activity,JSONObject evidence) throws Exception {
        HttpsURLConnection probe=(HttpsURLConnection)new URL("https://localhost/").openConnection();
        javax.net.ssl.HostnameVerifier verifier=probe.getHostnameVerifier();
        SSLSocketFactory global=HttpsURLConnection.getDefaultSSLSocketFactory();LegacyHttps.configure(probe);
        if(probe.getHostnameVerifier()!=verifier || HttpsURLConnection.getDefaultSSLSocketFactory()!=global)throw new AssertionError("Global TLS or hostname policy changed");
        try(SSLSocket socket=(SSLSocket)probe.getSSLSocketFactory().createSocket()) {
            if(!java.util.Arrays.equals(socket.getEnabledProtocols(),new String[]{"TLSv1.2"}))throw new AssertionError("Unexpected TLS protocol policy");
            for(String cipher:socket.getEnabledCipherSuites())if(!cipher.contains("_WITH_AES_") || cipher.contains("_anon_") || cipher.contains("_NULL_"))throw new AssertionError("Weak cipher enabled");
        }
        try(Fixture fixture=new Fixture(activity)) {
            HttpsURLConnection untrusted=fixture.connection();LegacyHttps.configure(untrusted);
            try {untrusted.getResponseCode();throw new AssertionError("Self-signed identity was trusted");}
            catch(javax.net.ssl.SSLHandshakeException expected) {evidence.put("untrusted_certificate_rejected","pass");}
            finally {untrusted.disconnect();}
            javax.net.ssl.SSLContext trusted=fixture.trusted(activity);
            // Establish independently that this test anchor is accepted, before
            // requiring the same trusted identity to fail HTTPS hostname checks.
            try(SSLSocket raw=(SSLSocket)trusted.getSocketFactory().createSocket("127.0.0.1",fixture.server.getLocalPort())) {
                raw.setEnabledProtocols(new String[]{"TLSv1.2"});raw.setSoTimeout(5000);raw.startHandshake();
            }
            HttpsURLConnection wrongName=fixture.connection();wrongName.setSSLSocketFactory(trusted.getSocketFactory());LegacyHttps.configure(wrongName);
            try {wrongName.getResponseCode();throw new AssertionError("Wrong certificate hostname accepted");}
            catch(java.io.IOException expected) {
                // API17 reports this as IOException, rather than the newer
                // SSLPeerUnverifiedException. Require its exact hostname error.
                if(!"Hostname '127.0.0.1' was not verified".equals(expected.getMessage()))throw expected;
                evidence.put("trusted_wrong_hostname_rejected","pass");
            }
            finally {wrongName.disconnect();}
        }
        await(()->io.buzincast.fireos3probe.LegacyNativeTls.liveSockets()==0);
        evidence.put("scoped_tls12_aes_policy","pass");
    }
    private static final class Fixture implements java.io.Closeable {
        final javax.net.ssl.SSLServerSocket server;final Thread worker;volatile SSLSocket peer;
        Fixture(Activity activity) throws Exception {
            server=(javax.net.ssl.SSLServerSocket)io.buzincast.fireos3probe.LegacyTlsIdentity.nativeContext(activity).getServerSocketFactory().createServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(10000);
            worker=new Thread(()-> {
                for(int i=0;i<3;i++)try {
                    peer=(SSLSocket)server.accept();peer.setSoTimeout(5000);peer.startHandshake();
                    peer.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.Charset.forName("US-ASCII")));
                }catch(Exception expected) {}finally {if(peer!=null)try {peer.close();}catch(Exception ignored) {}peer=null;}
            },"https-owned-fixture");worker.start();
        }
        HttpsURLConnection connection() throws Exception {
            HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.getLocalPort()+"/").openConnection();
            c.setConnectTimeout(5000);c.setReadTimeout(5000);return c;
        }
        javax.net.ssl.SSLContext trusted(Activity activity) throws Exception {
            java.security.cert.Certificate cert;
            try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(new File(activity.getFilesDir(),"cast-v2-identity.bin")))) {
                if(in.readInt()!=0x42435c01)throw new AssertionError("Identity format");int key=in.readInt();if(key<1 || key>16384 || in.skipBytes(key)!=key)throw new AssertionError("Identity key length");
                int size=in.readInt();if(size<1 || size>16384)throw new AssertionError("Certificate length");byte[] data=new byte[size];in.readFully(data);
                cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(data));
            }
            java.security.KeyStore store=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("fixture",cert);
            javax.net.ssl.TrustManagerFactory trust=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            javax.net.ssl.SSLContext context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
        }
        @Override public void close() throws java.io.IOException {
            server.close();SSLSocket current=peer;if(current!=null)current.close();
            try {worker.join(7000);}catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
            if(worker.isAlive())throw new java.io.IOException("HTTPS fixture did not stop");
        }
    }
}
