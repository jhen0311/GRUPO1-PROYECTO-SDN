package pe.edu.pucp.tel354.m1.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.m1.onos.M1Consola;

import java.util.List;

/** tel354:sesiones [--locales] — sesiones abiertas del campus o solo las de esta instancia. */
@Service
@Command(scope = "tel354", name = "sesiones",
        description = "Lista las sesiones abiertas (PostgreSQL) o las de esta instancia (--locales)")
public class SesionesComando extends AbstractShellCommand {

    @Option(name = "--locales", aliases = "-l", description = "Solo las sesiones en memoria de esta instancia")
    private boolean locales = false;

    @Override
    protected void doExecute() {
        M1Consola m1 = get(M1Consola.class);
        List<SesionInfo> l = locales ? m1.sesionesLocales() : m1.sesionesAbiertas();
        print("NAS/relay de esta instancia: %s", m1.nasIp());
        print("%-6s %-14s %-11s %-19s %-24s %-15s %-5s %s", "id", "usuario", "rol", "mac", "switch/puerto",
              "ip", "vlan", "estado");
        for (SesionInfo s : l) {
            print("%-6d %-14s %-11s %-19s %-24s %-15s %-5d %s", s.id(), s.usuario(), s.rol(), s.mac(),
                  s.dispositivo() + "/" + s.puerto(), s.ip() != null ? s.ip() : "-", s.vlan(), s.estado());
        }
        print("%d sesiones", l.size());
    }
}
