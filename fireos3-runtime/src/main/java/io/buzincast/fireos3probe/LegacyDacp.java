package io.buzincast.fireos3probe;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Session-owned sender controls. Discovery and HTTP never run on the UI thread. */
public final class LegacyDacp implements java.io.Closeable {
    public enum Command {
        PLAY("play"), PAUSE("pause"), NEXT("nextitem"), PREVIOUS("previtem"),
        VOLUME_UP("volumeup"), VOLUME_DOWN("volumedown"),
        FAST_FORWARD("beginff"), REWIND("beginrew"), RESUME("playresume");
        final String path;
        Command(String value) {path="/ctrl-int/1/"+value;}
    }
    interface Resolver {Endpoint resolve(String name) throws IOException;}
    public interface Completion {void complete(Exception failure);}
    static final class Endpoint {
        final Inet4Address address;final int port;
        Endpoint(Inet4Address address,int port) {
            if(address==null || port<1 || port>65535)throw new IllegalArgumentException("Invalid control endpoint");
            this.address=address;this.port=port;
        }
    }
    public static final class State {
        public final boolean ready,playing,identified,resolving;
        public final Exception failure;
        State(boolean ready,boolean playing,boolean identified,boolean resolving,Exception failure) {
            this.ready=ready;this.playing=playing;this.identified=identified;this.resolving=resolving;this.failure=failure;
        }
    }
    public static State inactive() {return new State(false,false,false,false,null);}
    private final Resolver resolver;
    private final Runnable changed;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16),task->{Thread thread=new Thread(task,"buzin-legacy-control");thread.setDaemon(true);return thread;});
    private String id="",token="";
    private Endpoint endpoint;
    private long generation,playbackRevision;
    private boolean closed,resolving,playing=true;
    private Exception failure;

    LegacyDacp(Resolver resolver,Runnable changed) {this.resolver=resolver;this.changed=changed;}
    public synchronized State state() {return new State(!closed && endpoint!=null,playing,!id.isEmpty(),resolving,failure);}
    void update(String id,String token) {
        final long owner;
        synchronized(this) {
            if(closed)return;
            if(this.id.equals(id) && this.token.equals(token) && (endpoint!=null || resolving))return;
            generation++;owner=generation;endpoint=null;resolving=false;failure=null;this.id="";this.token="";playing=true;
            if(id==null || !id.matches("[A-Za-z0-9_-]{1,48}") || !validToken(token)) {changed.run();return;}
            this.id=id;this.token=token;resolving=true;
        }
        changed.run();
        try {worker.execute(()-> {
            synchronized(this) {if(closed || owner!=generation)return;}
            Endpoint resolved;
            try {resolved=resolver.resolve("iTunes_Ctrl_"+id);}
            catch(IOException | RuntimeException unavailable) {
                synchronized(this) {if(owner!=generation || closed)return;resolving=false;failure=unavailable;}
                changed.run();return;
            }
            synchronized(this) {if(closed || owner!=generation)return;resolving=false;endpoint=resolved;}
            changed.run();
        });} catch(RejectedExecutionException full) {synchronized(this) {if(owner==generation)resolving=false;}}
    }
    private static boolean validToken(String value) {
        if(value==null || value.isEmpty() || value.length()>256)return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<33 || value.charAt(i)>126)return false;
        return true;
    }
    void senderPlaying(boolean value) {synchronized(this) {if(!value)playbackRevision++;playing=value;}changed.run();}
    void senderStopped() {senderPlaying(false);}
    public void command(Command command,Completion completion) {
        final Endpoint target;final String secret;final long owner,playbackOwner;
        synchronized(this) {target=endpoint;secret=token;owner=generation;playbackOwner=playbackRevision;}
        FutureTask<Void> task=new FutureTask<Void>(()-> {
            synchronized(this) {if(closed || target==null || owner!=generation)throw new IOException("Sender controls are not available");}
            send(target,secret,command);
            synchronized(this) {
                if(closed || owner!=generation)throw new IOException("Sender session changed during the command");
                if(playbackOwner==playbackRevision) {
                    if(command==Command.PLAY || command==Command.RESUME)playing=true;
                    else if(command==Command.PAUSE)playing=false;
                }
            }
            return null;
        }) {
            @Override protected void done() {
                Exception failure=null;
                try {get();}
                catch(ExecutionException error) {failure=error.getCause() instanceof Exception?(Exception)error.getCause():new IOException("Control command failed",error.getCause());}
                catch(CancellationException error) {failure=new IOException("Control command cancelled",error);}
                catch(InterruptedException error) {Thread.currentThread().interrupt();failure=error;}
                changed.run();if(completion!=null)completion.complete(failure);
            }
        };
        try {worker.execute(task);}catch(RejectedExecutionException unavailable) {task.cancel(false);}
    }
    private static void send(Endpoint target,String token,Command command) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL("http",target.address.getHostAddress(),target.port,command.path).openConnection();
        try {
            connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setRequestProperty("Active-Remote",token);
            int code=connection.getResponseCode();
            if(code<200 || code>=300)throw new IOException("Control HTTP status "+code);
            // Only the status acknowledges a command; never download a sender's body.
        } finally {connection.disconnect();}
    }
    @Override public void close() {
        synchronized(this) {if(closed)return;closed=true;generation++;endpoint=null;resolving=false;id=token="";}
        for(Runnable task:worker.shutdownNow())if(task instanceof FutureTask<?>)((FutureTask<?>)task).cancel(false);
    }
}
