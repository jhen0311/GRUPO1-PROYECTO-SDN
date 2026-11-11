package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.r3.Filtro;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Incidente;

import java.time.Instant;
import java.util.List;

/** tel354:incidentes [--abiertos] [--usuario U] [--limite N] (LLSD R3, Tabla 18). */
@Service
@Command(scope = "tel354", name = "incidentes", description = "Lista los incidentes de R3")
public class IncidentesComando extends AbstractShellCommand {

    @Option(name = "--abiertos", aliases = "-a", description = "Solo EN_INVESTIGACION")
    private boolean abiertos = false;

    @Option(name = "--usuario", aliases = "-u", description = "uid del usuario")
    private String usuario = null;

    @Option(name = "--limite", aliases = "-n", description = "Máximo de filas (100)")
    private int limite = 100;

    @Override
    protected void doExecute() {
        IncidentAdminService ti = get(IncidentAdminService.class);
        List<Incidente> l = ti.listar(new Filtro(abiertos, null, usuario, limite));
        print("Modo de M5: %s", ti.modo());
        print("%-5s %-14s %-18s %-10s %-8s %-4s %-4s %-17s %-20s %s", "id", "usuario", "tipo", "ataque",
              "crit.", "niv", "calc", "estado", "expira", "rev.");
        for (Incidente i : l) {
            print("%-5d %-14s %-18s %-10s %-8s %-4s %-4s %-17s %-20s %s", i.id(), i.usuario(), i.tipo(),
                  i.nivelAtaque(), i.criticidad(), i.nivel(), i.nivelCalculado(), i.estado(),
                  i.expiraMs() > 0 ? Instant.ofEpochMilli(i.expiraMs()).toString().substring(0, 19) : "-",
                  i.revisionPrioritaria() ? "PRIORITARIA" : "");
        }
        print("%d incidentes", l.size());
    }
}
