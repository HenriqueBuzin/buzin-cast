package io.buzincast.fireos3probe;

import java.io.*;
import java.net.*;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/** Internal server-only socket binding used by the shared Cast transport.
 * Records use bundled OpenSSL, not API17's obsolete system cipher provider. */
public final class LegacyNativeTls {
    private static final String[] PROTOCOLS={"TLSv1.2"};
    private static final String[] CIPHERS={"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256","TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"};
    private final PrivateKey key;private final X509Certificate certificate;
    LegacyNativeTls(PrivateKey key,X509Certificate certificate) {this.key=key;this.certificate=certificate;}
    public static int liveSockets() {return nLive();}
    public SSLServerSocketFactory getServerSocketFactory() {
        return new SSLServerSocketFactory() {
            public String[] getDefaultCipherSuites() {return CIPHERS.clone();}
            public String[] getSupportedCipherSuites() {return CIPHERS.clone();}
            public java.net.ServerSocket createServerSocket() throws IOException {return new Server(key,certificate);}
            public java.net.ServerSocket createServerSocket(int port) throws IOException {return createServerSocket(port,50,null);}
            public java.net.ServerSocket createServerSocket(int port,int backlog) throws IOException {return createServerSocket(port,backlog,null);}
            public java.net.ServerSocket createServerSocket(int port,int backlog,InetAddress address) throws IOException {
                Server server=new Server(key,certificate);
                try {server.bind(new InetSocketAddress(address,port),backlog);return server;}catch(IOException error) {server.close();throw error;}
            }
        };
    }
    private static void protocols(String[] values) {
        if(!Arrays.equals(PROTOCOLS,values))throw new IllegalArgumentException("The Cast binding requires TLS1.2");
    }
    private static void ciphers(String[] values) {
        if(!Arrays.equals(CIPHERS,values))throw new IllegalArgumentException("This internal binding uses its fixed cipher policy");
    }
    private static void serverMode(boolean client) {if(client)throw new IllegalArgumentException("This binding is server-only");}
    private static void clientAuth(boolean required) {if(required)throw new IllegalArgumentException("Cast client certificates are not supported");}
    private static void creation(boolean enabled) {if(!enabled)throw new IllegalArgumentException("Cast sessions require a TLS handshake");}
    private static final class Server extends SSLServerSocket {
        private final AtomicLong handle=new AtomicLong();private final X509Certificate certificate;
        private volatile boolean bound;private InetAddress address;private int port;
        Server(PrivateKey key,X509Certificate certificate) throws IOException {
            super();this.certificate=certificate;byte[] encoded=key.getEncoded();
            try {System.loadLibrary("fireos3_probe");handle.set(nCreate(encoded,certificate.getEncoded()));}
            catch(Exception error) {super.close();throw new IOException("Native TLS server initialization failed",error);}
            finally {if(encoded!=null)Arrays.fill(encoded,(byte)0);}
        }
        public synchronized void bind(SocketAddress endpoint,int backlog) throws IOException {
            if(isClosed() || bound || !(endpoint instanceof InetSocketAddress))throw new SocketException("Invalid TLS bind");
            InetSocketAddress target=(InetSocketAddress)endpoint;
            if(target.isUnresolved() || !(target.getAddress() instanceof Inet4Address))throw new SocketException("Cast requires the selected IPv4 interface");
            port=nBind(handle.get(),target.getAddress().getAddress(),target.getPort(),Math.max(1,Math.min(128,backlog)));
            address=target.getAddress();bound=true;
        }
        public void bind(SocketAddress endpoint) throws IOException {bind(endpoint,50);}
        public Socket accept() throws IOException {
            if(!bound)throw new SocketException("TLS listener is not bound");
            long[] accepted=nAccept(handle.get());long child=accepted[0];
            boolean transferred=false;
            try {if(isClosed())throw new SocketException("TLS listener is closed");Peer peer=new Peer(child,accepted,certificate);transferred=true;return peer;}
            finally {if(!transferred)nClose(child);}
        }
        public void close() throws IOException {long id=handle.getAndSet(0);if(id!=0)nClose(id);super.close();}
        public boolean isClosed() {return handle.get()==0;}
        public boolean isBound() {return bound;}
        public InetAddress getInetAddress() {return address;}
        public int getLocalPort() {return bound?port:-1;}
        public SocketAddress getLocalSocketAddress() {return bound?new InetSocketAddress(address,port):null;}
        public String[] getSupportedCipherSuites() {return CIPHERS.clone();}
        public String[] getEnabledCipherSuites() {return CIPHERS.clone();}
        public void setEnabledCipherSuites(String[] values) {ciphers(values);}
        public String[] getSupportedProtocols() {return PROTOCOLS.clone();}
        public String[] getEnabledProtocols() {return PROTOCOLS.clone();}
        public void setEnabledProtocols(String[] values) {protocols(values);}
        public void setNeedClientAuth(boolean value) {clientAuth(value);}
        public boolean getNeedClientAuth() {return false;}
        public void setWantClientAuth(boolean value) {clientAuth(value);}
        public boolean getWantClientAuth() {return false;}
        public void setUseClientMode(boolean value) {serverMode(value);}
        public boolean getUseClientMode() {return false;}
        public void setEnableSessionCreation(boolean value) {creation(value);}
        public boolean getEnableSessionCreation() {return true;}
    }
    private static InetAddress address(long value) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value});
    }
    private static final class Peer extends SSLSocket {
        final AtomicLong handle;final InetAddress remote,local;final int remotePort,localPort;
        final X509Certificate certificate;final Object handshake=new Object();
        final List<HandshakeCompletedListener> listeners=new ArrayList<>();
        volatile String cipher;volatile int timeout;volatile boolean noDelay;
        Peer(long id,long[] endpoints,X509Certificate certificate) throws IOException {
            super();handle=new AtomicLong(id);remote=address(endpoints[1]);remotePort=(int)endpoints[2];
            local=address(endpoints[3]);localPort=(int)endpoints[4];this.certificate=certificate;
        }
        public void startHandshake() throws IOException {
            synchronized(handshake) {
                if(isClosed())throw new SocketException("TLS socket is closed");
                if(cipher!=null)return;cipher=nHandshake(handle.get());
                for(HandshakeCompletedListener listener:new ArrayList<>(listeners))listener.handshakeCompleted(new HandshakeCompletedEvent(this,getSession()));
            }
        }
        public InputStream getInputStream() throws IOException {
            if(isClosed())throw new SocketException("TLS socket is closed");
            return new InputStream() {
                public int read() throws IOException {byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&255;}
                public int read(byte[] bytes,int offset,int count) throws IOException {
                    if(bytes==null)throw new NullPointerException();
                    if(offset<0 || count<0 || offset>bytes.length || count>bytes.length-offset)throw new IndexOutOfBoundsException();
                    if(count==0)return 0;startHandshake();return nRead(handle.get(),bytes,offset,count);
                }
                public void close() throws IOException {Peer.this.close();}
            };
        }
        public OutputStream getOutputStream() throws IOException {
            if(isClosed())throw new SocketException("TLS socket is closed");
            return new OutputStream() {
                public void write(int value) throws IOException {write(new byte[]{(byte)value});}
                public void write(byte[] bytes,int offset,int count) throws IOException {
                    if(bytes==null)throw new NullPointerException();
                    if(offset<0 || count<0 || offset>bytes.length || count>bytes.length-offset)throw new IndexOutOfBoundsException();
                    if(count==0)return;
                    startHandshake();while(count>0) {int block=Math.min(count,65536);nWrite(handle.get(),bytes,offset,block);offset+=block;count-=block;}
                }
                public void close() throws IOException {Peer.this.close();}
            };
        }
        public void close() throws IOException {long id=handle.getAndSet(0);if(id!=0)nClose(id);super.close();}
        public void setSoTimeout(int millis) throws SocketException {if(millis<0)throw new IllegalArgumentException("Negative timeout");nOption(handle.get(),1,millis);timeout=millis;}
        public int getSoTimeout() {return timeout;}
        public void setTcpNoDelay(boolean value) throws SocketException {nOption(handle.get(),2,value?1:0);noDelay=value;}
        public boolean getTcpNoDelay() {return noDelay;}
        public boolean isClosed() {return handle.get()==0;}
        public boolean isConnected() {return true;}
        public boolean isBound() {return true;}
        public InetAddress getInetAddress() {return remote;}
        public InetAddress getLocalAddress() {return local;}
        public int getPort() {return remotePort;}
        public int getLocalPort() {return localPort;}
        public SocketAddress getRemoteSocketAddress() {return new InetSocketAddress(remote,remotePort);}
        public SocketAddress getLocalSocketAddress() {return new InetSocketAddress(local,localPort);}
        public String[] getSupportedCipherSuites() {return CIPHERS.clone();}
        public String[] getEnabledCipherSuites() {return CIPHERS.clone();}
        public void setEnabledCipherSuites(String[] values) {ciphers(values);}
        public String[] getSupportedProtocols() {return PROTOCOLS.clone();}
        public String[] getEnabledProtocols() {return PROTOCOLS.clone();}
        public void setEnabledProtocols(String[] values) {protocols(values);}
        public void setNeedClientAuth(boolean value) {clientAuth(value);}
        public boolean getNeedClientAuth() {return false;}
        public void setWantClientAuth(boolean value) {clientAuth(value);}
        public boolean getWantClientAuth() {return false;}
        public void setUseClientMode(boolean value) {serverMode(value);}
        public boolean getUseClientMode() {return false;}
        public void setEnableSessionCreation(boolean value) {creation(value);}
        public boolean getEnableSessionCreation() {return true;}
        public void addHandshakeCompletedListener(HandshakeCompletedListener value) {synchronized(handshake) {if(value==null || listeners.size()>=16)throw new IllegalArgumentException();listeners.add(value);}}
        public void removeHandshakeCompletedListener(HandshakeCompletedListener value) {synchronized(handshake) {if(!listeners.remove(value))throw new IllegalArgumentException();}}
        private final long created=System.currentTimeMillis();
        private SSLSession session;
        public SSLSession getSession() {
            synchronized(handshake) {
            if(session!=null)return session;
            session=new SSLSession() {
                private final Map<String,Object> values=new HashMap<>();private boolean valid=true;
                public byte[] getId() {return new byte[0];}
                public SSLSessionContext getSessionContext() {return null;}
                public long getCreationTime() {return created;}
                public long getLastAccessedTime() {return created;}
                public void invalidate() {valid=false;}
                public boolean isValid() {return valid && cipher!=null && !isClosed();}
                public void putValue(String name,Object value) {if(values.size()>=16)throw new IllegalStateException("Session metadata budget");values.put(name,value);}
                public Object getValue(String name) {return values.get(name);}
                public void removeValue(String name) {values.remove(name);}
                public String[] getValueNames() {return values.keySet().toArray(new String[0]);}
                public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {throw new SSLPeerUnverifiedException("No client identity is requested");}
                public javax.security.cert.X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {throw new SSLPeerUnverifiedException("No client identity is requested");}
                public Certificate[] getLocalCertificates() {return new Certificate[]{certificate};}
                public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {throw new SSLPeerUnverifiedException("No client identity is requested");}
                public Principal getLocalPrincipal() {return certificate.getSubjectX500Principal();}
                public String getCipherSuite() {return cipher==null?"SSL_NULL_WITH_NULL_NULL":cipher;}
                public String getProtocol() {return "TLSv1.2";}
                public String getPeerHost() {return remote.getHostAddress();}
                public int getPeerPort() {return remotePort;}
                public int getPacketBufferSize() {return 18437;}
                public int getApplicationBufferSize() {return 16384;}
            };
            return session;
            }
        }
    }
    private static native long nCreate(byte[] key,byte[] certificate) throws IOException;
    private static native int nBind(long id,byte[] address,int port,int backlog) throws IOException;
    private static native long[] nAccept(long id) throws IOException;
    private static native void nClose(long id);
    private static native String nHandshake(long id) throws IOException;
    private static native int nRead(long id,byte[] data,int offset,int count) throws IOException;
    private static native void nWrite(long id,byte[] data,int offset,int count) throws IOException;
    private static native void nOption(long id,int kind,int value) throws SocketException;
    private static native int nLive();
}
