package io.buzincast.fireos3probe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/** API17's private software identity; AndroidKeyStore is unavailable before API18. */
public final class LegacyTlsIdentity {
    private static final int MAGIC=0x42435c01,LIMIT=16384;
    private final PrivateKey key;
    private final X509Certificate certificate;
    private LegacyTlsIdentity(byte[][] encoded) throws Exception {
        try {
            key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded[0]));
            certificate=(X509Certificate)CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(encoded[1]));
            certificate.checkValidity();certificate.verify(certificate.getPublicKey());
            if(!(certificate.getPublicKey() instanceof RSAPublicKey))throw new IOException("Cast TLS certificate must use RSA");
            if(((RSAPublicKey)certificate.getPublicKey()).getModulus().bitLength()<2048)throw new IOException("Cast TLS RSA key is too small");
            // API17's X509 provider misreads the empty BasicConstraints sequence
            // as a CA. Require our canonical explicit non-CA extension instead:
            // OCTET STRING { SEQUENCE {} }, where cA defaults to FALSE.
            byte[] constraints=certificate.getExtensionValue("2.5.29.19");
            if(!Arrays.equals(constraints,new byte[]{4,2,48,0}))
                throw new IOException("Cast TLS certificate must have the owned non-CA constraint: "+Arrays.toString(constraints));
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update(challenge);byte[] signed=signature.sign();
            signature.initVerify(certificate.getPublicKey());signature.update(challenge);
            if(!signature.verify(signed))throw new IOException("Cast TLS key does not match its certificate");
        } finally {Arrays.fill(encoded[0],(byte)0);}
    }
    private static byte[] read(DataInputStream input) throws IOException {
        int count=input.readInt();if(count<1 || count>LIMIT)throw new IOException("Invalid TLS identity size");
        byte[] value=new byte[count];input.readFully(value);return value;
    }
    /** Caller runs on a protocol worker; key generation must never block the UI. */
    private static synchronized LegacyTlsIdentity identity(Context context) throws Exception {
        AtomicFile stored=new AtomicFile(new File(context.getFilesDir(),"cast-v2-identity.bin"));
        LegacyTlsIdentity identity=null;
        try(DataInputStream input=new DataInputStream(stored.openRead())) {
            if(input.readInt()!=MAGIC)throw new IOException("Invalid TLS identity version");
            byte[][] encoded=new byte[][]{read(input),read(input)};
            if(input.read()!=-1) {Arrays.fill(encoded[0],(byte)0);throw new IOException("Trailing TLS identity data");}
            identity=new LegacyTlsIdentity(encoded);
        } catch(Exception invalid) {
            System.loadLibrary("fireos3_probe");
            byte[][] encoded=NativeReceiver.createTlsIdentity();
            if(encoded==null || encoded.length!=2 || encoded[0]==null || encoded[1]==null)
                throw new IOException("Could not generate Cast TLS identity");
            FileOutputStream file=null;
            try {
                if(encoded[0].length<1 || encoded[0].length>LIMIT || encoded[1].length<1 || encoded[1].length>LIMIT)
                    throw new IOException("Generated TLS identity exceeded budget");
                // Validate before replacing a previous identity. Constructor clears its input key.
                identity=new LegacyTlsIdentity(new byte[][]{encoded[0].clone(),encoded[1]});
                file=stored.startWrite();DataOutputStream output=new DataOutputStream(file);
                output.writeInt(MAGIC);
                for(byte[] value:encoded) {output.writeInt(value.length);output.write(value);}
                output.flush();stored.finishWrite(file);file=null;
            } catch(Exception failure) {if(file!=null)stored.failWrite(file);throw failure;}
            finally {Arrays.fill(encoded[0],(byte)0);}
        }
        return identity;
    }
    public static LegacyNativeTls nativeContext(Context context) throws Exception {
        LegacyTlsIdentity selected=identity(context);return new LegacyNativeTls(selected.key,selected.certificate);
    }
}
