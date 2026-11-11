package pe.edu.pucp.tel354.api.r3;

import org.onosproject.net.DeviceId;

import java.util.Map;

/**
 * Reserva de entradas de flujo para mitigación en cada switch de acceso (LLSD R3, sección 4.4),
 * compartida por R3 y R4. Capacidad por switch configurable (por defecto M = 96); el contador es
 * del clúster (ConsistentMap), de modo que cualquier instancia reserva sobre el mismo valor.
 */
public interface MitigationReserveService {

    /** Reserva {@code entradas} si caben; false si no (la reserva no cambia). */
    boolean reservar(DeviceId dispositivo, int entradas, Requisito requisito);

    /** Devuelve entradas a la reserva (nunca por debajo de 0). */
    void liberar(DeviceId dispositivo, int entradas, Requisito requisito);

    /** Entradas libres en el switch. */
    int libres(DeviceId dispositivo);

    /** Capacidad M del switch. */
    int capacidad(DeviceId dispositivo);

    /** Entradas usadas por requisito en el switch. */
    Map<Requisito, Integer> usadas(DeviceId dispositivo);
}
