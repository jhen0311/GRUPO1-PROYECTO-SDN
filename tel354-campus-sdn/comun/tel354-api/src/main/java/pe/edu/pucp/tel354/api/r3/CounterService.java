package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.ListenerService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowId;

/** Lectura de contadores cada 5 s (implementa M3). M3 lee del almacén de ONOS, sin mensajes extra. */
public interface CounterService extends ListenerService<CounterEvent, CounterListener> {

    void vigilar(FlowId regla, DeviceId dispositivo);

    void dejarDeVigilar(FlowId regla);
}
