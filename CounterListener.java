package pe.edu.pucp.tel354.api.contador;

/**
 * Contrato de quien quiere recibir los avisos CounterDelta de M3 (por ejemplo, M5).
 */
public interface CounterListener {

    /** Se ejecuta cada vez que una regla vigilada sumó paquetes. */
    void event(CounterEvent evento);
}
