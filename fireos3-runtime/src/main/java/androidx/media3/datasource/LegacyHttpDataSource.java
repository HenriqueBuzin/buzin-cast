package androidx.media3.datasource;

import androidx.media3.common.util.UnstableApi;
import io.buzincast.fireos3probe.LegacyHttps;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** Media3 1.2.1 platform binding; inherits HTTP, range, redirects and error handling unchanged.
 * The package-local connection hook intentionally requires this package and @Override.
 * A Media3 upgrade that removes it must fail compilation instead of silently losing TLS policy.
 */
@androidx.annotation.OptIn(markerClass=UnstableApi.class)
public final class LegacyHttpDataSource extends DefaultHttpDataSource {
    @SuppressWarnings("deprecation")
    private LegacyHttpDataSource(Map<String,String> headers) {
        super("BuzinCast",10000,15000,false,new RequestProperties());
        for(Map.Entry<String,String> header:headers.entrySet())setRequestProperty(header.getKey(),header.getValue());
    }
    public static DataSource.Factory factory(Map<String,String> headers) {
        Map<String,String> owned=new java.util.HashMap<>(headers);
        return ()->new LegacyHttpDataSource(owned);
    }
    @Override HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection=super.openConnection(url);
        try {LegacyHttps.configure(connection);return connection;}
        catch(RuntimeException failure) {connection.disconnect();throw failure;}
    }
}
