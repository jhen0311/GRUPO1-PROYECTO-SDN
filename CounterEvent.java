package pe.edu.pucp.tel354.api.contador;

import org.onosproject.net.DeviceId;

/**
 * Aviso CounterDelta: una regla vigilada sumó paquetes desde la lectura anterior.
 * M3 lo crea y M5 lo usa para saber si el ataque continúa.
 */
public final class CounterEvent {

    private final DeviceId dispositivo;
    private final long cookie;
    private final long deltaPaquetes;
    private final long deltaBytes;
    private final long intervaloMs;

    /** Crea el aviso con el switch, la regla y lo que sumó en el intervalo. */
    public CounterEvent(DeviceId dispositivo, long cookie,
                        long deltaPaquetes, long deltaBytes, long intervaloMs) {
        this.dispositivo = dispositivo;
        this.cookie = cookie;
        this.deltaPaquetes = deltaPaquetes;
        this.deltaBytes = deltaBytes;
        this.intervaloMs = intervaloMs;
    }

    /** Switch donde está la regla. */
    public DeviceId dispositivo()  { return dispositivo; }

    /** Cookie que identifica la regla. */
    public long cookie()           { return cookie; }

    /** Paquetes que la regla sumó desde la lectura anterior. */
    public long deltaPaquetes()    { return deltaPaquetes; }

    /** Bytes que la regla sumó desde la lectura anterior. */
    public long deltaBytes()       { return deltaBytes; }

    /** Tiempo entre las dos lecturas, en milisegundos. */
    public long intervaloMs()      { return intervaloMs; }

    /** Tasa media en paquetes por segundo durante el intervalo. */
    public double pps() {
        return intervaloMs > 0 ? deltaPaquetes * 1000.0 / intervaloMs : 0;
    }

    /** Texto legible del aviso, para los logs. */
    @Override
    public String toString() {
        return String.format("CounterDelta{%s, cookie=0x%x, +%d paq, +%d B, %d ms}",
                dispositivo, cookie, deltaPaquetes, deltaBytes, intervaloMs);
    }
}
