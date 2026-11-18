package io.github.jqssun.airplay.discovery;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable discovery value matching the shared transport's modern platform binding. */
public final class MdnsService {
    final String name,type;
    final int port;
    final Map<String,String> txt;
    public MdnsService(String name,String type,int port,Map<String,String> txt) {
        this.name=name;this.type=type;this.port=port;this.txt=Collections.unmodifiableMap(new HashMap<>(txt));
    }
}
