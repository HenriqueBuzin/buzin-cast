package io.buzincast.fireos3probe;

/** AES-256-GCM binding for the shared BZCAST02 channel on API17. */
public final class LegacyRelayCrypto {
    static {System.loadLibrary("fireos3_probe");}
    private LegacyRelayCrypto() {}
    public static native byte[] crypt(boolean encrypt,byte[] key,byte[] nonce,byte[] aad,byte[] data);
}
