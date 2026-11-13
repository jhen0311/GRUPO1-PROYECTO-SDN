package pe.edu.pucp.tel354.api.r3;

import org.onlab.packet.Ip4Address;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Destino implicado en una sospecha: IP, protocolo (0 si no aplica) y puertos intentados. */
public final class Destino {

    private final Ip4Address ip;
    private final int proto;
    private final Set<Integer> puertos;

    public Destino(Ip4Address ip, int proto, Set<Integer> puertos) {
        this.ip = ip;
        this.proto = proto;
        this.puertos = Collections.unmodifiableSet(new TreeSet<>(puertos));
    }

    public static Destino soloIp(Ip4Address ip) {
        return new Destino(ip, 0, Collections.<Integer>emptySet());
    }

    public Ip4Address ip() {
        return ip;
    }

    public int proto() {
        return proto;
    }

    public Set<Integer> puertos() {
        return puertos;
    }

    @Override
    public String toString() {
        return ip + (proto != 0 ? "/" + proto : "") + (puertos.isEmpty() ? "" : puertos.toString());
    }
}
