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

    void start(Inet4Address address, String identity, int port, String[] airplayTxt, String[] raopTxt, String serviceName) throws IOException {
        name = serviceName == null ? "Buzin Cast API17 "+identity.replace(":", "").substring(8) : serviceName;
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

    LegacyDacp.Endpoint resolveControl(String name,LegacyNetwork network) throws IOException {
        JmDNS current=dns;if(current==null)throw new IOException("Discovery is closed");
        ServiceInfo info=null;
        // A sender can deliver RTSP headers before its DNS probing/announcement
        // finishes. Re-query rather than permanently lose controls at that race.
        for(int attempt=0;attempt<3 && info==null;attempt++) {
            if(Thread.currentThread().isInterrupted() || dns!=current)throw new IOException("Sender discovery cancelled");
            info=current.getServiceInfo("_dacp._tcp.local.",name,4000);
        }
        if(info==null)throw new IOException("Sender control service was not found");
        for(Inet4Address address:info.getInet4Addresses()) {
            byte[] candidate=address.getAddress(),local=network.address.getAddress();boolean same=true;
            for(int bit=0;bit<network.prefix;bit++)
                if(((candidate[bit/8]^local[bit/8]) & (1<<(7-bit%8)))!=0) {same=false;break;}
            if(same)return new LegacyDacp.Endpoint(address,info.getPort());
        }
        throw new IOException("Sender control service is outside the local network");
    }

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
