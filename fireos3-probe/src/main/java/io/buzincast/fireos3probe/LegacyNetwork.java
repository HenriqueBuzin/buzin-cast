package io.buzincast.fireos3probe;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** A single local IPv4 interface; no guessed addresses or host identifiers. */
final class LegacyNetwork {
    final Inet4Address address;
    final int prefix;
    LegacyNetwork(Inet4Address address, int prefix) { this.address = address; this.prefix = prefix; }

    static LegacyNetwork select() throws IOException {
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) for (NetworkInterface network : Collections.list(interfaces)) {
            if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
            for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
                if (candidate.getAddress() instanceof Inet4Address && candidate.getAddress().isSiteLocalAddress()
                        && candidate.getNetworkPrefixLength() > 0 && candidate.getNetworkPrefixLength() <= 32) {
                    return new LegacyNetwork((Inet4Address)candidate.getAddress(), candidate.getNetworkPrefixLength());
                }
            }
        }
        throw new SocketException("No multicast-capable local IPv4 interface is available");
    }
}
