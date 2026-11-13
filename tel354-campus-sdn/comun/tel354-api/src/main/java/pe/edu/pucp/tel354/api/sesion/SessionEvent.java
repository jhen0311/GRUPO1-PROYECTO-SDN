package pe.edu.pucp.tel354.api.sesion;

import org.onlab.packet.Ip4Address;
import org.onosproject.event.AbstractEvent;

/**
 * Eventos de sesión que publica M1 (LLSD R3, Tabla 9).
 *
 * <ul>
 *   <li>SESSION_READY: binding 520 instalado (sesión ACTIVA).</li>
 *   <li>SESSION_CLOSED: cierre por cualquier motivo ({@link #motivo()}).</li>
 *   <li>AUTH_FAILED: Access-Reject o sesión no admitida (sujeto con id = -1).</li>
 *   <li>BINDING_VIOLATION: IPv4 de la regla 505 o ARP con una IP distinta de la concedida
 *       ({@link #violacion()}, {@link #ip()}, {@link #inequivoca()}).</li>
 *   <li>ARP_PROBE: ARP de una sesión ACTIVA hacia una IP que no es su gateway ({@link #ip()}).</li>
 * </ul>
 */
public class SessionEvent extends AbstractEvent<SessionEvent.Type, SesionInfo> {

    public enum Type { SESSION_READY, SESSION_CLOSED, AUTH_FAILED, BINDING_VIOLATION, ARP_PROBE }

    /** Origen de una suplantación. */
    public enum Violacion { IP, ARP }

    private final Motivo motivo;
    private final Violacion violacion;
    private final Ip4Address ip;
    private final boolean inequivoca;

    private SessionEvent(Type tipo, SesionInfo s, Motivo motivo, Violacion violacion, Ip4Address ip,
                         boolean inequivoca) {
        super(tipo, s);
        this.motivo = motivo;
        this.violacion = violacion;
        this.ip = ip;
        this.inequivoca = inequivoca;
    }

    public static SessionEvent lista(SesionInfo s) {
        return new SessionEvent(Type.SESSION_READY, s, null, null, s.ip(), false);
    }

    public static SessionEvent cerrada(SesionInfo s, Motivo motivo) {
        return new SessionEvent(Type.SESSION_CLOSED, s, motivo, null, s.ip(), false);
    }

    public static SessionEvent falloAutenticacion(SesionInfo s) {
        return new SessionEvent(Type.AUTH_FAILED, s, null, null, null, false);
    }

    /** ipUsada: IP de origen (505) o IP anunciada por ARP. */
    public static SessionEvent violacion(SesionInfo s, Violacion v, Ip4Address ipUsada, boolean inequivoca) {
        return new SessionEvent(Type.BINDING_VIOLATION, s, null, v, ipUsada, inequivoca);
    }

    public static SessionEvent sondeoArp(SesionInfo s, Ip4Address ipBuscada) {
        return new SessionEvent(Type.ARP_PROBE, s, null, null, ipBuscada, false);
    }

    /** Motivo de cierre (solo SESSION_CLOSED). */
    public Motivo motivo() {
        return motivo;
    }

    /** IP o ARP (solo BINDING_VIOLATION). */
    public Violacion violacion() {
        return violacion;
    }

    /**
     * BINDING_VIOLATION: IP usada o anunciada; ARP_PROBE: IP buscada; SESSION_READY y
     * SESSION_CLOSED: IP de la sesión. La IP concedida está siempre en subject().ip().
     */
    public Ip4Address ip() {
        return ip;
    }

    /** Suplanta un gateway, un servidor o el plano de control (confirmación automática en M5). */
    public boolean inequivoca() {
        return inequivoca;
    }

    @Override
    public String toString() {
        return type() + " " + subject() + (motivo != null ? " motivo " + motivo : "")
                + (violacion != null ? " " + violacion + " " + ip + (inequivoca ? " inequívoca" : "") : "")
                + (type() == Type.ARP_PROBE ? " busca " + ip : "");
    }
}
