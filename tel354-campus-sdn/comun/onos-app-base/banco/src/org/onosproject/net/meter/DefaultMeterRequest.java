// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;

import java.util.Collection;

/** Como en ONOS, add() y remove() exigen dispositivo, app y al menos una banda. */
public final class DefaultMeterRequest implements MeterRequest {
    private DeviceId d;
    private ApplicationId app;
    private Meter.Unit unidad;
    private Collection<Band> bandas;

    public static MeterRequest.Builder builder() {
        DefaultMeterRequest r = new DefaultMeterRequest();
        return new MeterRequest.Builder() {
            public Builder forDevice(DeviceId x) { r.d = x; return this; }
            public Builder fromApp(ApplicationId a) { r.app = a; return this; }
            public Builder withUnit(Meter.Unit u) { r.unidad = u; return this; }
            public Builder burst() { return this; }
            public Builder withBands(Collection<Band> b) { r.bandas = b; return this; }
            public MeterRequest add() { return validar(); }
            public MeterRequest remove() { return validar(); }
            private MeterRequest validar() {
                if (r.d == null || r.app == null || r.bandas == null || r.bandas.isEmpty()) {
                    throw new IllegalArgumentException("MeterRequest incompleto");
                }
                return r;
            }
        };
    }

    public DeviceId deviceId() { return d; }
    public ApplicationId appId() { return app; }
    public Meter.Unit unit() { return unidad; }
    public Collection<Band> bands() { return bandas; }
}
