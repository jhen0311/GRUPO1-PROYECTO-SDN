package pe.edu.pucp.tel354.api.r3;

import org.onlab.packet.Ip4Address;
import org.onosproject.event.AbstractEvent;
import org.onosproject.net.DeviceId;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;

/**
 * AUTHORIZATION_DENIED: M2 instaló un DROP 150 para la cuádrupla (LLSD R3, Tabla 9).
 * Campos: sesión, ipDst, proto (6, 17, 1, ...), puertoDst (-1 si no es TCP/UDP), dpid.
 */
public class AuthorizationEvent extends AbstractEvent<AuthorizationEvent.Type, SesionInfo> {

    public enum Type { AUTHORIZATION_DENIED }

    private final Ip4Address ipDst;
    private final int proto;
    private final int puertoDst;
    private final DeviceId dispositivo;

    public AuthorizationEvent(SesionInfo s, Ip4Address ipDst, int proto, int puertoDst, DeviceId dispositivo) {
        super(Type.AUTHORIZATION_DENIED, s);
        this.ipDst = ipDst;
        this.proto = proto;
        this.puertoDst = puertoDst;
        this.dispositivo = dispositivo;
    }

    public Ip4Address ipDst() {
        return ipDst;
    }

    public int proto() {
        return proto;
    }

    public int puertoDst() {
        return puertoDst;
    }

    public DeviceId dispositivo() {
        return dispositivo;
    }

    @Override
    public String toString() {
        return type() + " sesion " + subject().id() + " -> " + ipDst + " proto " + proto
                + (puertoDst >= 0 ? " puerto " + puertoDst : "") + " en " + dispositivo;
    }
}
