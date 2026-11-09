package pe.edu.pucp.tel354.api.contador;

import org.onosproject.net.DeviceId;

/**
 * Servicio de M3: vigila los contadores de reglas concretas (switch + cookie)
 * y avisa cuando aumentan. Lo usa M5 para renovar la contención N2 mientras
 * el ataque continúa.
 */
public interface CounterService {

    /** Empieza a vigilar la regla con esa cookie en ese switch. */
    void vigilar(DeviceId dispositivo, long cookie);

    /** Deja de vigilarla (por ejemplo, al recibir FlowExpired o al cerrar el incidente). */
    void dejarDeVigilar(DeviceId dispositivo, long cookie);

    void addListener(CounterListener oyente);

    void removeListener(CounterListener oyente);
}
