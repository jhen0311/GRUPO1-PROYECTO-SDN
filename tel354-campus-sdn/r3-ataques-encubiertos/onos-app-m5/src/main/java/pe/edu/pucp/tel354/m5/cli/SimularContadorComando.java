package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.m5.M5Pruebas;

/** tel354:r3-simular-contador <incidente> — CounterDelta de la regla 301 (renovación de N2 sin M3). */
@Service
@Command(scope = "tel354", name = "r3-simular-contador", description = "Simula un CounterDelta de la regla 301 (pruebas)")
public class SimularContadorComando extends AbstractShellCommand {

    @Argument(index = 0, name = "incidente", description = "incidente.id en N2", required = true)
    private long incidente;

    @Override
    protected void doExecute() {
        boolean ok = get(M5Pruebas.class).simularContador(incidente);
        print(ok ? "Incidente %d renovado" : "El incidente %d no está abierto en N2 en esta instancia", incidente);
    }
}
