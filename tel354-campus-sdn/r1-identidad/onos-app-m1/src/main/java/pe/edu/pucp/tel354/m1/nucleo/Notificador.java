package pe.edu.pucp.tel354.m1.nucleo;

/**
 * Avisos del núcleo hacia los demás módulos (P5). En ONOS los convierte M1Manager en
 * SessionEvent de tel354-api; en el banco de pruebas solo se registran en el log.
 */
public interface Notificador {

    /** SESSION_READY: reglas 520, 110, 200 y 190 instaladas. */
    default void sesionLista(Sesion s) {
    }

    /** SESSION_CLOSED. */
    default void sesionCerrada(Sesion s, String motivo) {
    }

    /** AUTH_FAILED: Access-Reject o sesión no admitida. */
    default void autenticacionFallida(String dpid, int puerto, String mac, String usuario) {
    }

    /** BINDING_VIOLATION (tipo "IP" o "ARP"). */
    default void violacion(Sesion s, String tipo, String ipUsada, boolean inequivoca) {
    }

    /** ARP_PROBE. */
    default void sondeoArp(Sesion s, String ipBuscada) {
    }

    Notificador NINGUNO = new Notificador() {
    };
}
