// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onosproject.net.DeviceId;

public interface FlowRule {
    FlowId id();
    short appId();
    int priority();
    int tableId();
    DeviceId deviceId();
    TrafficSelector selector();
    TrafficTreatment treatment();

    interface Builder {
        Builder forDevice(DeviceId d);
        Builder forTable(int t);
        Builder withPriority(int p);
        Builder withSelector(TrafficSelector s);
        Builder withTreatment(TrafficTreatment t);
        Builder withCookie(long c);
        Builder makePermanent();
        FlowRule build();
    }
}
