package pe.edu.pucp.tel354.base.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.base.BaseManager;

/** tel354:base — estado del régimen base por switch (rol, puertos, reglas, meters, convergencia). */
@Service
@Command(scope = "tel354", name = "base", description = "Régimen base (M7) y caminos fijos (M8) por switch")
public class BaseComando extends AbstractShellCommand {

    @Option(name = "-r", aliases = "--reglas", description = "Lista también las reglas deseadas de cada switch")
    private boolean reglas = false;

    @Override
    protected void doExecute() {
        for (String l : get(BaseManager.class).resumen(reglas)) {
            print("%s", l);
        }
    }
}
