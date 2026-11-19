package io.buzincast.fireos3probe;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded HTTP(S) media request shared by the receiver and protocol adapters. */
public final class LegacyUrlRequest {
    public static final long MAX_POSITION_MS=31_536_000_000L;
    public final String location;
    public final long startMs;
    public final Map<String,String> headers;
    public final String mime;
    public final boolean localRecording;
    /** Trusted frontend-only entry point; network constructors remain HTTP(S)-only. */
    public static LegacyUrlRequest recording(java.io.File file) throws java.io.IOException {
        return local(file,"video/x-matroska");
    }
    public static LegacyUrlRequest downloaded(java.io.File file) throws java.io.IOException {
        return local(file,"video/mp4");
    }
    private static LegacyUrlRequest local(java.io.File file,String mime) throws java.io.IOException {
        java.io.File canonical=file.getCanonicalFile();
        if(!canonical.isFile())throw new java.io.IOException("Recording is unavailable");
        return new LegacyUrlRequest(canonical.toURI().toASCIIString(),0,Collections.emptyMap(),mime,true);
    }
    private LegacyUrlRequest(String location,long start,Map<String,String> headers,String mime,boolean local) {
        this.location=location;this.startMs=start;this.headers=headers;this.mime=mime;this.localRecording=local;
    }
    public LegacyUrlRequest withPosition(long start) {
        if(start<0 || start>MAX_POSITION_MS)throw new IllegalArgumentException("Invalid media position");
        return new LegacyUrlRequest(location,start,headers,mime,localRecording);
    }
    public LegacyUrlRequest(String location,long startMs,Map<String,String> headers) {
        this(location,startMs,headers,null);
    }
    public LegacyUrlRequest(String location,long startMs,Map<String,String> headers,String mime) {
        this.localRecording=false;
        if(location==null || location.length()>8192 || startMs<0 || startMs>MAX_POSITION_MS)
            throw new IllegalArgumentException("Invalid media location or position");
        if(mime!=null) {
            if(mime.length()>120)throw new IllegalArgumentException("Invalid media type");
            for(int i=0;i<mime.length();i++)if(Character.isISOControl(mime.charAt(i)))throw new IllegalArgumentException("Invalid media type");
            mime=mime.split(";",2)[0].trim();
            if(!mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))throw new IllegalArgumentException("Invalid media type");
        }
        this.mime=mime;
        try {
            URI uri=new URI(location);
            if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null ||
                uri.getRawUserInfo()!=null || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException("Expected HTTP(S) media location without embedded credentials");
            this.location=uri.toASCIIString();
        } catch(URISyntaxException invalid) {throw new IllegalArgumentException("Invalid media location",invalid);}
        this.startMs=startMs;
        Map<String,String> copy=new LinkedHashMap<>();
        if(headers!=null) {
            if(headers.size()>24)throw new IllegalArgumentException("Too many media headers");
            for(Map.Entry<String,String> entry:headers.entrySet()) {
                String key=entry.getKey(),value=entry.getValue();
                if(key==null || !key.matches("[A-Za-z0-9-]{1,80}") || value==null || value.length()>4096)
                    throw new IllegalArgumentException("Invalid media header");
                String normalized=key.toLowerCase(Locale.US);
                if(normalized.equals("host") || normalized.equals("connection") || normalized.equals("content-length") || normalized.equals("transfer-encoding"))
                    throw new IllegalArgumentException("Media headers cannot override HTTP framing");
                for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new IllegalArgumentException("Invalid media header value");
                copy.put(key,value);
            }
        }
        this.headers=Collections.unmodifiableMap(copy);
    }
}
