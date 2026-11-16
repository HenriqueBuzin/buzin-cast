package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LegacyDacpTest {
    @Test public void progressBeforeThePauseAcknowledgementDoesNotCancelThePause() throws Exception {
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->peer.endpoint(),()->{})) {
            control.update("ID","token");ready(control);peer.hold=true;
            CountDownLatch completed=new CountDownLatch(1);AtomicReference<Exception> failure=new AtomicReference<>();
            control.command(LegacyDacp.Command.PAUSE,error->{failure.set(error);completed.countDown();});
            assertTrue(peer.received.await(2,TimeUnit.SECONDS));control.senderPlaying(true);peer.release.countDown();
            assertTrue(completed.await(2,TimeUnit.SECONDS));assertNull(failure.get());assertFalse(control.state().playing);
        }
    }
    @Test public void aLateAcknowledgementCannotUndoNativeSenderTeardown() throws Exception {
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->peer.endpoint(),()->{})) {
            control.update("ID","token");ready(control);peer.hold=true;
            CountDownLatch complete=new CountDownLatch(1);AtomicReference<Exception> failure=new AtomicReference<>();
            control.command(LegacyDacp.Command.PLAY,error->{failure.set(error);complete.countDown();});
            assertTrue(peer.received.await(2,TimeUnit.SECONDS));control.senderStopped();peer.release.countDown();
            assertTrue(complete.await(2,TimeUnit.SECONDS));assertNull(failure.get());assertFalse(control.state().playing);
        }
    }
    @Test public void duplicateNativeIdentityCallbacksShareAnInFlightResolution() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->{
            calls.incrementAndGet();entered.countDown();try {release.await(2,TimeUnit.SECONDS);}
            catch(InterruptedException error) {throw new IOException(error);}return peer.endpoint();
        },()->{})) {
            control.update("ID","token");assertTrue(entered.await(2,TimeUnit.SECONDS));
            control.update("ID","token");release.countDown();ready(control);
            assertEquals(1,calls.get());assertNull(command(control,LegacyDacp.Command.PAUSE));
        } finally {release.countDown();}
    }
    private static final class Peer implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        final Thread worker;
        volatile int code=204;
        volatile boolean hold;
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        Peer() throws Exception {
            worker=new Thread(()-> {
                while(!server.isClosed())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(2000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();String headers;
                    do {
                        int value=socket.getInputStream().read();if(value<0)throw new IOException("Truncated request");
                        bytes.write(value);headers=new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
                    } while(!headers.endsWith("\r\n\r\n") && bytes.size()<8192);
                    requests.add(headers);received.countDown();if(hold)release.await(3,TimeUnit.SECONDS);
                    socket.getOutputStream().write(("HTTP/1.1 "+code+" Result\r\nContent-Length: 0\r\nConnection: close\r\n"+
                        (code==302?"Location: http://127.0.0.1:1/forbidden\r\n":"")+"\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch(Exception error) {if(!server.isClosed())throw new RuntimeException(error);}
            },"test-dacp-peer");worker.setDaemon(true);worker.start();
        }
        LegacyDacp.Endpoint endpoint() {return new LegacyDacp.Endpoint((Inet4Address)server.getInetAddress(),server.getLocalPort());}
        @Override public void close() throws Exception {release.countDown();server.close();worker.join(4000);assertFalse(worker.isAlive());}
    }
    private static void ready(LegacyDacp control) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!control.state().ready && System.nanoTime()<until)Thread.sleep(5);
        assertTrue("Discovery did not resolve",control.state().ready);
    }
    private static Exception command(LegacyDacp control,LegacyDacp.Command command) throws Exception {
        AtomicReference<Exception> failure=new AtomicReference<>();CountDownLatch complete=new CountDownLatch(1);
        control.command(command,error->{failure.set(error);complete.countDown();});
        assertTrue("Command callback missing",complete.await(4,TimeUnit.SECONDS));return failure.get();
    }
    @Test public void allCommandsReachTheResolvedPeerWithTheSessionToken() throws Exception {
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->{assertEquals("iTunes_Ctrl_AABB",name);return peer.endpoint();},()->{})) {
            control.update("AABB","123456789");ready(control);
            for(LegacyDacp.Command value:LegacyDacp.Command.values())assertNull(command(control,value));
            assertEquals(9,peer.requests.size());
            for(int i=0;i<9;i++) {
                String request=peer.requests.get(i);assertTrue(request.startsWith("GET "+LegacyDacp.Command.values()[i].path+" HTTP/1.1\r\n"));
                assertTrue(request.contains("\r\nActive-Remote: 123456789\r\n"));
            }
            assertTrue(control.state().playing);
        }
    }
    @Test public void httpErrorsAndRedirectsDoNotChangePlayingState() throws Exception {
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->peer.endpoint(),()->{})) {
            control.update("ID","token");ready(control);
            assertNull(command(control,LegacyDacp.Command.PAUSE));assertFalse(control.state().playing);
            peer.code=500;assertNotNull(command(control,LegacyDacp.Command.PLAY));assertFalse(control.state().playing);
            peer.code=302;Exception error=command(control,LegacyDacp.Command.PLAY);
            assertNotNull(error);assertTrue(error.getMessage().contains("302"));assertFalse(control.state().playing);
            assertEquals(3,peer.requests.size());
        }
    }
    @Test public void staleDiscoveryAndInjectedHeadersCannotAuthorizeCommands() throws Exception {
        CountDownLatch resolving=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->{
            if(name.endsWith("OLD"))try {resolving.countDown();release.await(3,TimeUnit.SECONDS);}
                catch(InterruptedException error) {throw new IOException(error);}
            return peer.endpoint();
        },()->{})) {
            control.update("OLD","first");assertTrue(resolving.await(2,TimeUnit.SECONDS));
            control.update("NEW","second");assertFalse(control.state().ready);release.countDown();ready(control);
            assertNull(command(control,LegacyDacp.Command.NEXT));assertTrue(peer.requests.get(0).contains("Active-Remote: second\r\n"));
            control.update("NEW","token\r\nInjected: yes");assertFalse(control.state().ready);
            assertNotNull(command(control,LegacyDacp.Command.PLAY));assertEquals(1,peer.requests.size());
        } finally {release.countDown();}
    }
    @Test public void replyFromRetiredSenderCannotOverwriteTheNewSession() throws Exception {
        try(Peer peer=new Peer();LegacyDacp control=new LegacyDacp(name->peer.endpoint(),()->{})) {
            control.update("OLD","first");ready(control);peer.hold=true;
            CountDownLatch completed=new CountDownLatch(1);AtomicReference<Exception> failure=new AtomicReference<>();
            control.command(LegacyDacp.Command.PAUSE,error->{failure.set(error);completed.countDown();});
            assertTrue(peer.received.await(2,TimeUnit.SECONDS));control.update("NEW","second");
            peer.release.countDown();assertTrue(completed.await(3,TimeUnit.SECONDS));assertNotNull(failure.get());
            ready(control);assertTrue(control.state().playing);
            assertNull(command(control,LegacyDacp.Command.NEXT));assertTrue(peer.requests.get(1).contains("Active-Remote: second\r\n"));
        }
    }
    @Test public void closingCancelsQueuedCommandsAndBoundsTheQueue() throws Exception {
        CountDownLatch resolving=new CountDownLatch(1),completed=new CountDownLatch(24);
        LegacyDacp control=new LegacyDacp(name->{
            resolving.countDown();try {Thread.sleep(10000);}catch(InterruptedException error) {throw new IOException(error);}
            throw new IOException("No endpoint");
        },()->{});
        try {
            control.update("ID","token");assertTrue(resolving.await(2,TimeUnit.SECONDS));
            for(int i=0;i<24;i++)control.command(LegacyDacp.Command.PLAY,error->{assertNotNull(error);completed.countDown();});
            assertEquals(16,completed.getCount());control.close();assertTrue(completed.await(2,TimeUnit.SECONDS));
            assertFalse(control.state().ready);assertNotNull(command(control,LegacyDacp.Command.PLAY));
        } finally {control.close();}
    }
}
