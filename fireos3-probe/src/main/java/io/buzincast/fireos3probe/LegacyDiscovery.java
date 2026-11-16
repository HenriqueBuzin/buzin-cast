package io.buzincast.fireos3probe;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** TXT records come from the same native receiver that owns the advertised port. */
final class LegacyDiscovery {
    private JmDNS dns;
    private String name;

    void start(Inet4Address address, String identity, int port, String[] airplayTxt, String[] raopTxt) throws IOException {
        name = "Buzin Cast API17 "+identity.replace(":", "").substring(8);
        dns = JmDNS.create(address, "buzin-api17-"+identity.replace(":", "").toLowerCase(java.util.Locale.US));
        try {
            Map<String,String> videoProperties = properties(airplayTxt);
            ServiceInfo video = ServiceInfo.create("_airplay._tcp.local.", name, port, 0, 0, videoProperties);
            dns.registerService(video);
            // Do not appear as an AirTunes speaker while audio playback is absent.
            if (advertisesAudio(videoProperties)) {
                ServiceInfo audio = ServiceInfo.create("_raop._tcp.local.", identity.replace(":", "")+"@"+name,
                        port, 0, 0, properties(raopTxt));
                dns.registerService(audio);
            }
            name = video.getName();
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }

    String name() { return name; }

    static boolean advertisesAudio(Map<String,String> records) {
        String features = records.get("features");
        if (features == null) return false;
        String low = features.split(",")[0];
        if (!low.matches("0x[0-9a-fA-F]{1,8}")) throw new IllegalArgumentException("Invalid DNS-SD capabilities");
        return (Long.parseLong(low.substring(2),16) & (1L<<9)) != 0;
    }

    static Map<String, String> properties(String[] records) {
        if (records == null || records.length%2 != 0) throw new IllegalArgumentException("Invalid native DNS-SD record pairs");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i=0; i<records.length; i+=2) {
            if (records[i] == null || records[i+1] == null || result.put(records[i], records[i+1]) != null) {
                throw new IllegalArgumentException("Invalid or duplicate DNS-SD property");
            }
        }
        return result;
    }

    void close() throws IOException {
        JmDNS previous = dns; dns = null;
        if (previous != null) previous.close();
    }
}
