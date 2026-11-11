package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Incidente;

/** tel354:incidente-descartar <id> --motivo "…" — retira la contención. */
@Service
@Command(scope = "tel354", name = "incidente-descartar", description = "Descarta un incidente y retira la contención")
public class DescartarComando extends AbstractShellCommand {

    @Argument(index = 0, name = "id", description = "incidente.id", required = true)
    private long id;

    @Option(name = "--motivo", aliases = "-m", description = "Motivo", required = true)
    private String motivo;

    @Override
    protected void doExecute() {
        Incidente i = get(IncidentAdminService.class).descartar(id, "consola:" + System.getProperty("user.name"),
                                                                motivo, "consola");
        print("Incidente %d %s", id, i.estado());
    }
}
