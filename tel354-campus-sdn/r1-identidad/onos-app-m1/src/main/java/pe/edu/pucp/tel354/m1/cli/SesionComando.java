package pe.edu.pucp.tel354.m1.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.mastership.MastershipService;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionService;

/** tel354:sesion <id> — detalle de una sesión y la instancia master de su switch. */
@Service
@Command(scope = "tel354", name = "sesion", description = "Detalle de una sesión")
public class SesionComando extends AbstractShellCommand {

    @Argument(index = 0, name = "id", description = "sesion.id", required = true)
    private long id;

    @Override
    protected void doExecute() {
        SesionInfo s = get(SessionService.class).getSession(id);
        if (s == null) {
            print("No existe la sesión %d", id);
            return;
        }
        print("%s", s);
        print("  usuario      %s (id %d)", s.usuario(), s.usuarioId());
        print("  rol          %s (k = %d, b = %d)", s.rol(), s.codigoRol(), s.octeto());
        print("  pabellón     %d · VLAN %d", s.pabellon(), s.vlan());
        print("  switch       %s puerto %s · master %s", s.dispositivo(), s.puerto(),
              get(MastershipService.class).getMasterFor(s.dispositivo()));
        print("  ip           %s", s.ip() != null ? s.ip() : "-");
        print("  estado       %s desde %tF %<tT", s.estado(), s.inicioMs());
    }
}
