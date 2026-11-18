package io.buzincast.fireos3probe;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded HTTP(S) media request shared by the future protocol adapters. */
public final class LegacyUrlRequest {
    public final String location;
    public final long startMs;
    public final Map<String,String> headers;
    public LegacyUrlRequest(String location,long startMs,Map<String,String> headers) {
        if(location==null || location.length()>8192 || startMs<0 || startMs>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid media location or position");
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
