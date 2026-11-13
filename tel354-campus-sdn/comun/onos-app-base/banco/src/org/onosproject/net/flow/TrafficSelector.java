// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onlab.packet.IpPrefix;
import org.onlab.packet.VlanId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.criteria.Criterion;

public interface TrafficSelector {
    Criterion getCriterion(Criterion.Type t);

    interface Builder {
        Builder matchInPort(PortNumber p);
        Builder matchVlanId(VlanId v);
        Builder matchEthType(short t);
        Builder matchIPDst(IpPrefix p);
        TrafficSelector build();
    }
}
