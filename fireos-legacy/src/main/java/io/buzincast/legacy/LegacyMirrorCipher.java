package io.buzincast.legacy;

import io.buzincast.media.MirrorCipher;
import io.buzincast.fireos3probe.LegacyRelayCrypto;
import javax.crypto.spec.SecretKeySpec;

/** Uses the shared channel with API17's explicitly selected crypto binding. */
public final class LegacyMirrorCipher implements MirrorCipher {
    public static final LegacyMirrorCipher INSTANCE=new LegacyMirrorCipher();
    private LegacyMirrorCipher() {}
    @Override public byte[] crypt(boolean encrypt,SecretKeySpec key,byte[] nonce,byte[] aad,byte[] data) {
        byte[] owned=key.getEncoded();
        try {return LegacyRelayCrypto.crypt(encrypt,owned,nonce,aad,data);}
        finally {java.util.Arrays.fill(owned,(byte)0);}
    }
}
