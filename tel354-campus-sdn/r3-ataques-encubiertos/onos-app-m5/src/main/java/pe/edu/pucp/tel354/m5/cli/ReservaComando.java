package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.DeviceId;
import pe.edu.pucp.tel354.api.r3.MitigationReserveService;
import pe.edu.pucp.tel354.api.r3.Requisito;
import pe.edu.pucp.tel354.m5.M5Manager;

import java.util.Map;

/** tel354:reserva — entradas de la reserva usadas por switch y por requisito. */
@Service
@Command(scope = "tel354", name = "reserva", description = "Reserva de mitigación por switch (R3 y R4)")
public class ReservaComando extends AbstractShellCommand {

    @Override
    protected void doExecute() {
        MitigationReserveService r = get(MitigationReserveService.class);
        M5Manager m5 = (M5Manager) r;
        print("%-22s %6s %6s %6s %6s", "switch", "M", "R3", "R4", "libres");
        for (DeviceId d : m5.switchesConReserva()) {
            Map<Requisito, Integer> u = r.usadas(d);
            print("%-22s %6d %6d %6d %6d", d, r.capacidad(d), u.get(Requisito.R3), u.get(Requisito.R4), r.libres(d));
        }
    }
}
