package io.buzincast.fireos3probe;

import android.os.Build;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Enable supported TLS 1.2 on old clients, without replacing trust or hostname verification. */
public final class LegacyHttps {
    private LegacyHttps() {}
    public static void configure(HttpURLConnection connection) {
        if(Build.VERSION.SDK_INT<20 && connection instanceof HttpsURLConnection) {
            HttpsURLConnection https=(HttpsURLConnection)connection;
            https.setSSLSocketFactory(new Tls12(https.getSSLSocketFactory()));
        }
    }
    private static final class Tls12 extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        Tls12(SSLSocketFactory delegate) {this.delegate=delegate;}
        private Socket enable(Socket socket) throws IOException {
            try {
                if(!(socket instanceof SSLSocket))throw new IOException("Expected TLS socket");
                SSLSocket tls=(SSLSocket)socket;
                List<String> protocols=new ArrayList<>(),ciphers=new ArrayList<>();
                for(String protocol:tls.getSupportedProtocols())if("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol))protocols.add(protocol);
                for(String cipher:tls.getEnabledCipherSuites())if(cipher.contains("_WITH_AES_") && !cipher.contains("_anon_") && !cipher.contains("_NULL_"))ciphers.add(cipher);
                if(protocols.isEmpty() || ciphers.isEmpty())throw new IOException("TLS 1.2 with AES is unavailable");
                tls.setEnabledProtocols(protocols.toArray(new String[0]));tls.setEnabledCipherSuites(ciphers.toArray(new String[0]));
                return socket;
            } catch(IOException | RuntimeException failure) {try {socket.close();}catch(IOException ignored) {}throw failure;}
        }
        @Override public String[] getDefaultCipherSuites() {return delegate.getDefaultCipherSuites();}
        @Override public String[] getSupportedCipherSuites() {return delegate.getSupportedCipherSuites();}
        @Override public Socket createSocket() throws IOException {return enable(delegate.createSocket());}
        @Override public Socket createSocket(Socket s,String h,int p,boolean close) throws IOException {return enable(delegate.createSocket(s,h,p,close));}
        @Override public Socket createSocket(String h,int p) throws IOException {return enable(delegate.createSocket(h,p));}
        @Override public Socket createSocket(String h,int p,InetAddress local,int lp) throws IOException {return enable(delegate.createSocket(h,p,local,lp));}
        @Override public Socket createSocket(InetAddress h,int p) throws IOException {return enable(delegate.createSocket(h,p));}
        @Override public Socket createSocket(InetAddress h,int p,InetAddress local,int lp) throws IOException {return enable(delegate.createSocket(h,p,local,lp));}
    }
}
