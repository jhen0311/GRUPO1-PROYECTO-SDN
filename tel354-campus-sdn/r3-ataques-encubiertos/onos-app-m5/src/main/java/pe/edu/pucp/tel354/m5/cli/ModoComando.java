package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Modo;

/** tel354:r3-modo [ACTIVO|OBSERVACION] — muestra o cambia el modo de M5 (en todo el clúster). */
@Service
@Command(scope = "tel354", name = "r3-modo", description = "Muestra o cambia el modo de M5")
public class ModoComando extends AbstractShellCommand {

    @Argument(index = 0, name = "modo", description = "ACTIVO u OBSERVACION", required = false)
    private String modo;

    @Override
    protected void doExecute() {
        IncidentAdminService ti = get(IncidentAdminService.class);
        if (modo != null) {
            ti.modo(Modo.valueOf(modo.toUpperCase()), "consola:" + System.getProperty("user.name"), "consola");
        }
        print("Modo de M5: %s", ti.modo());
    }
}
