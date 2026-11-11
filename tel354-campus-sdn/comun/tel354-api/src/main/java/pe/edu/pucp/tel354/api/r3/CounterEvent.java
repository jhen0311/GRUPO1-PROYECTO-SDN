package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.AbstractEvent;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowId;

/** COUNTER_DELTA: aumentó el contador de paquetes de una regla vigilada (M3). */
public class CounterEvent extends AbstractEvent<CounterEvent.Type, FlowId> {

    public enum Type { COUNTER_DELTA }

    private final DeviceId dispositivo;
    private final long deltaPaquetes;
    private final long intervaloMs;

    public CounterEvent(FlowId regla, DeviceId dispositivo, long deltaPaquetes, long intervaloMs) {
        super(Type.COUNTER_DELTA, regla);
        this.dispositivo = dispositivo;
        this.deltaPaquetes = deltaPaquetes;
        this.intervaloMs = intervaloMs;
    }

    public DeviceId dispositivo() {
        return dispositivo;
    }

    public long deltaPaquetes() {
        return deltaPaquetes;
    }

    public long intervaloMs() {
        return intervaloMs;
    }

    @Override
    public String toString() {
        return type() + " " + subject() + " en " + dispositivo + " +" + deltaPaquetes + " paquetes en " + intervaloMs + " ms";
    }
}
