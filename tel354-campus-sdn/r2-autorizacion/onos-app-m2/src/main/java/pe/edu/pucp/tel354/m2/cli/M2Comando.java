package pe.edu.pucp.tel354.m2.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.m2.M2Manager;

/** tel354:m2 — matriz, LISTEN y Tabla 1 por switch de acceso (y, con -r, las reglas deseadas). */
@Service
@Command(scope = "tel354", name = "m2", description = "M2: matriz de autorización y Tabla 1 por switch de acceso")
public class M2Comando extends AbstractShellCommand {

    @Option(name = "-r", aliases = "--reglas", description = "Lista también la Tabla 1 deseada")
    private boolean reglas = false;

    @Override
    protected void doExecute() {
        for (String l : get(M2Manager.class).resumen(reglas)) {
            print("%s", l);
        }
    }
}
