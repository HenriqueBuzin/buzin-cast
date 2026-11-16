package io.buzincast.fireos3probe;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** A single local IPv4 interface; no guessed addresses or host identifiers. */
public final class LegacyNetwork {
    public final Inet4Address address;
    public final int prefix;
    public final String interfaceName;
    LegacyNetwork(Inet4Address address,int prefix,String interfaceName) {this.address=address;this.prefix=prefix;this.interfaceName=interfaceName;}

    public static LegacyNetwork select() throws IOException {return select(null);}
    public static LegacyNetwork select(LegacyNetwork preferred) throws IOException {
        java.util.ArrayList<LegacyNetwork> candidates=new java.util.ArrayList<>();
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) for (NetworkInterface network : Collections.list(interfaces)) {
            if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
            for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
                if (candidate.getAddress() instanceof Inet4Address && candidate.getAddress().isSiteLocalAddress()
                        && candidate.getNetworkPrefixLength() > 0 && candidate.getNetworkPrefixLength() <= 32) {
                    LegacyNetwork value=new LegacyNetwork((Inet4Address)candidate.getAddress(),candidate.getNetworkPrefixLength(),network.getName());
                    if(value.equals(preferred))return value;
                    candidates.add(value);
                }
            }
        }
        if(candidates.isEmpty())throw new SocketException("No multicast-capable local IPv4 interface is available");
        Collections.sort(candidates,(a,b)->a.fingerprint().compareTo(b.fingerprint()));
        return candidates.get(0);
    }
    public String fingerprint() {return interfaceName+":"+address.getHostAddress()+"/"+prefix;}
    @Override public boolean equals(Object other) {return other instanceof LegacyNetwork && fingerprint().equals(((LegacyNetwork)other).fingerprint());}
    @Override public int hashCode() {return fingerprint().hashCode();}
}
