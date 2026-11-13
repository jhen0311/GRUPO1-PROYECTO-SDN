package pe.edu.pucp.tel354.api.r3;

/**
 * Contrato de quien quiere recibir los avisos CounterDelta de M3 (por ejemplo, M5).
 */
public interface CounterListener {

    /**
     * Se ejecuta cada vez que una regla vigilada sumó paquetes.
     *
     * @param evento aviso con el switch, la regla y lo que sumó
     */
    void event(CounterEvent evento);
}
