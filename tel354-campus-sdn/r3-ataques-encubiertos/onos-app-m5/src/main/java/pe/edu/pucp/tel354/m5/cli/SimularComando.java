package pe.edu.pucp.tel354.m5.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onlab.packet.Ip4Address;
import org.onosproject.cli.AbstractShellCommand;
import pe.edu.pucp.tel354.api.r3.Destino;
import pe.edu.pucp.tel354.api.r3.Metrica;
import pe.edu.pucp.tel354.api.r3.Sospecha;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionService;
import pe.edu.pucp.tel354.m5.M5Pruebas;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * tel354:r3-simular <sesion> <P|D|N|F|SUPLANTACION> --destino ip[:proto[:p1,p2,...]] ... — inyecta en
 * M5 el ThreatSuspected que publicaría M4a, para probar M5 antes de que M4a esté instalado.
 * Ejemplo: tel354:r3-simular 12 P --destino 10.200.1.30:6:21,22,23,25,80,110,135,139,443,445
 */
@Service
@Command(scope = "tel354", name = "r3-simular", description = "Inyecta un ThreatSuspected en M5 (pruebas)")
public class SimularComando extends AbstractShellCommand {

    @Argument(index = 0, name = "sesion", description = "sesion.id del origen", required = true)
    private long sesion;

    @Argument(index = 1, name = "metrica", description = "P, D, N, F o SUPLANTACION", required = true)
    private String metrica;

    @Option(name = "--destino", aliases = "-d", description = "ip[:proto[:puertos]]", multiValued = true)
    private List<String> destinos = new ArrayList<>();

    @Option(name = "--valor", aliases = "-v", description = "Valor de la métrica (por defecto el umbral)")
    private int valor = 0;

    @Option(name = "--inequivoca", aliases = "-i", description = "Suplantación de gateway, servidor o control")
    private boolean inequivoca = false;

    @Override
    protected void doExecute() {
        SesionInfo s = get(SessionService.class).getSession(sesion);
        if (s == null) {
            print("No existe la sesión %d", sesion);
            return;
        }
        Metrica m = Metrica.valueOf(metrica.toUpperCase());
        int umbral = m == Metrica.P ? 10 : m == Metrica.D ? 8 : m == Metrica.N ? 30 : m == Metrica.F ? 3 : 1;
        Sospecha.Builder b = Sospecha.builder(s, m).valor(valor > 0 ? valor : umbral).umbral(umbral)
                .ventanaSeg(m == Metrica.P || m == Metrica.D ? 10 : m == Metrica.SUPLANTACION ? 0 : 600)
                .inequivoca(inequivoca).detalle("simulado desde la consola").tsPrimeraMs(System.currentTimeMillis());
        for (String d : destinos) {
            String[] p = d.split(":");
            int proto = p.length > 1 ? ("tcp".equalsIgnoreCase(p[1]) ? 6 : "udp".equalsIgnoreCase(p[1]) ? 17
                    : Integer.parseInt(p[1])) : 0;
            Set<Integer> puertos = new TreeSet<>();
            if (p.length > 2) {
                for (String x : p[2].split(",")) {
                    puertos.add(Integer.parseInt(x.trim()));
                }
            }
            b.destino(new Destino(Ip4Address.valueOf(p[0]), proto, puertos));
        }
        get(M5Pruebas.class).simular(b.build());
        print("Sospecha %s de la sesión %d (%s) entregada a M5; ver tel354:incidentes --abiertos", m, sesion, s.usuario());
    }
}
