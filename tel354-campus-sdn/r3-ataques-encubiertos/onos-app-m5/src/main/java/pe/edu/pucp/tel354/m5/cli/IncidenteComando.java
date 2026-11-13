package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.core.CoreService;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.m5.M5Manager;

/** tel354:incidente <id> — detalle con evidencia, sesiones y reglas instaladas. */
@Service
@Command(scope = "tel354", name = "incidente", description = "Detalle de un incidente de R3")
public class IncidenteComando extends AbstractShellCommand {

    @Argument(index = 0, name = "id", description = "incidente.id", required = true)
    private long id;

    @Override
    protected void doExecute() {
        Incidente i = get(IncidentAdminService.class).detalle(id);
        if (i == null) {
            print("No existe el incidente %d", id);
            return;
        }
        print("%s", i);
        print("  sesión de origen   %d", i.sesionId());
        print("  nivel de ataque    %s · criticidad %s · revisión prioritaria %s", i.nivelAtaque(), i.criticidad(),
              i.revisionPrioritaria() ? "sí" : "no");
        print("  contención         %s (calculado %s) · estado %s", i.nivel(), i.nivelCalculado(), i.estado());
        print("  sesiones contenidas %s", i.sesionesContenidas());
        print("  evidencia          %s", i.evidencia());
        print("  reglas instaladas:");
        short app = get(CoreService.class).getAppId(M5Manager.APP).id();
        for (FlowEntry e : get(FlowRuleService.class).getFlowEntriesById(get(CoreService.class).getAppId(M5Manager.APP))) {
            long c = e.id().value();
            if (Cookies.appId(c) == app && Cookies.tipo(c) == Cookies.CONTENCION && Cookies.id(c) == id) {
                print("    %s prio %d tabla %d hard %d s, vida %d s, %d paquetes, %s", e.deviceId(), e.priority(),
                      e.tableId(), e.hardTimeout(), e.life(), e.packets(), e.selector());
            }
        }
    }
}
