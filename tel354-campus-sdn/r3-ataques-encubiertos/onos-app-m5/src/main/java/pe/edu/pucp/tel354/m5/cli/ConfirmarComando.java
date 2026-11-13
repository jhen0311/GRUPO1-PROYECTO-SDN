package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Incidente;

import java.time.Duration;

/** tel354:incidente-confirmar <id> [--horas H] --motivo "…" — cuarentena y paso forzado. */
@Service
@Command(scope = "tel354", name = "incidente-confirmar", description = "Confirma un incidente: cuarentena (N3)")
public class ConfirmarComando extends AbstractShellCommand {

    @Argument(index = 0, name = "id", description = "incidente.id", required = true)
    private long id;

    @Option(name = "--horas", aliases = "-h", description = "Duración de la sanción (24)")
    private int horas = 24;

    @Option(name = "--minutos", description = "Duración en minutos (pruebas); reemplaza a --horas")
    private int minutos = 0;

    @Option(name = "--motivo", aliases = "-m", description = "Motivo", required = true)
    private String motivo;

    @Override
    protected void doExecute() {
        Duration d = minutos > 0 ? Duration.ofMinutes(minutos) : Duration.ofHours(horas);
        Incidente i = get(IncidentAdminService.class).confirmar(id, d, "consola:" + System.getProperty("user.name"),
                                                                motivo, "consola");
        print("Incidente %d %s: sanción de %s a %s", id, i.estado(), d, i.usuario());
    }
}
