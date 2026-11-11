package pe.edu.pucp.tel354.api.sesion;

import org.onlab.packet.Ip4Address;
import org.onosproject.event.ListenerService;

import java.util.List;

/**
 * Sesiones de R1 (implementa M1; LLSD R3, Tabla 8). En el clúster, cada instancia mantiene en
 * memoria las sesiones de los switches de los que es master; las consultas que no encuentran la
 * sesión en memoria la buscan en PostgreSQL, y las operaciones se reenvían a la instancia master.
 */
public interface SessionService extends ListenerService<SessionEvent, SessionListener> {

    /** Sesión abierta con esa IP, o null. */
    SesionInfo getSessionByIp(Ip4Address ip);

    /** Sesiones abiertas (AUTENTICADA o ACTIVA) del usuario en todo el campus. */
    List<SesionInfo> getSessionsByUser(int usuarioId);

    /** Sesión por id (también cerradas, para consultas de TI), o null. */
    SesionInfo getSession(long sesionId);

    /**
     * Cierra la sesión, retira sus reglas por cookie, publica SESSION_CLOSED y, si el motivo
     * lo indica, rebota el puerto.
     *
     * @return false si la sesión no existe o ya estaba cerrada
     */
    boolean revocar(long sesionId, Motivo motivo, String solicitante);

    /**
     * Paso forzado a cuarentena (N3): cierra cada sesión abierta del usuario con CUARENTENA,
     * borra su lease en Kea y rebota el puerto con EAP-Request/Identity al rehabilitarlo.
     * La fila de cuarentena debe existir antes de llamar.
     *
     * @return número de sesiones cerradas
     */
    int aplicarCuarentena(int usuarioId, long incidenteId);
}
