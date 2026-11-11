package pe.edu.pucp.tel354.api.r3;

import org.onosproject.net.DeviceId;

/**
 * Servicio de M3: vigila los contadores de reglas concretas (switch + cookie)
 * y avisa cuando aumentan. Lo usa M5 para renovar la contención N2 mientras
 * el ataque continúa.
 */
public interface CounterService {

    /**
     * Empieza a vigilar la regla con esa cookie en ese switch.
     *
     * @param dispositivo switch donde está la regla
     * @param cookie      cookie que identifica la regla
     */
    void vigilar(DeviceId dispositivo, long cookie);

    /**
     * Deja de vigilarla (por ejemplo, al recibir FlowExpired o al cerrar el incidente).
     *
     * @param dispositivo switch donde está la regla
     * @param cookie      cookie que identifica la regla
     */
    void dejarDeVigilar(DeviceId dispositivo, long cookie);

    /**
     * Registra a un módulo para que reciba los avisos de M3.
     *
     * @param oyente módulo que recibirá los avisos
     */
    void addListener(CounterListener oyente);

    /**
     * Deja de enviar avisos a ese módulo.
     *
     * @param oyente módulo que ya no recibirá avisos
     */
    void removeListener(CounterListener oyente);
}
