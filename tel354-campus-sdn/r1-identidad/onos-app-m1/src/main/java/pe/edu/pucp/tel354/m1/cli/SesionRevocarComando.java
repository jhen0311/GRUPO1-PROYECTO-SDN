package pe.edu.pucp.tel354.m1.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.sesion.Motivo;
import pe.edu.pucp.tel354.api.sesion.SessionService;

/** tel354:sesion-revocar <id> [--motivo REVOCACION] — cierra la sesión desde cualquier instancia. */
@Service
@Command(scope = "tel354", name = "sesion-revocar", description = "Revoca una sesión (cierre, retiro de reglas y rebote)")
public class SesionRevocarComando extends AbstractShellCommand {

    @Argument(index = 0, name = "id", description = "sesion.id", required = true)
    private long id;

    @Option(name = "--motivo", aliases = "-m", description = "Motivo de cierre (por defecto REVOCACION)")
    private String motivo = "REVOCACION";

    @Override
    protected void doExecute() {
        boolean ok = get(SessionService.class).revocar(id, Motivo.valueOf(motivo.toUpperCase()), "consola");
        print(ok ? "Sesión %d revocada (%s)" : "La sesión %d no está abierta", id, motivo.toUpperCase());
    }
}
