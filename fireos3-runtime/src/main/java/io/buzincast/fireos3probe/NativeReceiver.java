package io.buzincast.fireos3probe;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/** JNI entry points shared by the app and its separate debug-only test frontend. */
final class NativeReceiver {
    static native boolean checkNativeParser();
    static native long startReceiver(String keyPath, VideoFrameQueue callback);
    static long startNetworkReceiver(String keyPath, VideoFrameQueue callback, String identity,
                                    String address, int prefix, String name, int port) {
        return createNetworkReceiver(keyPath,callback,identity,address,prefix,
                name == null ? null : name.getBytes(Charset.forName("UTF-8")),port);
    }
    private static native long createNetworkReceiver(String keyPath, VideoFrameQueue callback, String identity,
                                                     String address, int prefix, byte[] utf8Name, int port);
    static native String[] receiverTxt(long handle, boolean airplay);
    static native int receiverPort(long handle);
    static native void stopReceiver(long handle);
    static native boolean replayVideoFrame(Object callback, ByteBuffer input, int length, long timeNs);
    static native boolean replayReceiverVideo(long handle, ByteBuffer input, int length, long timeNs);
    static native boolean replayReceiverAudio(long handle, ByteBuffer input, int length, int codec, int spf,
                                              long timeNs, float volume, boolean flush);
}
