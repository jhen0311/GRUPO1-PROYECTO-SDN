package pe.edu.pucp.tel354.m5;

import pe.edu.pucp.tel354.api.r3.Criticidad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Criticidad del destino por el prefijo más específico (AVZ07, Tabla 25; LLSD R3, sección 5.6,
 * paso 2). Con varios destinos se toma la mayor; sin destino identificable, la de por defecto.
 * Trabaja con direcciones en texto para poder probarse sin ONOS.
 */
public final class Criticidades {

    private static final class Prefijo {
        final int red;
        final int largo;
        final Criticidad criticidad;

        Prefijo(int red, int largo, Criticidad criticidad) {
            this.red = red;
            this.largo = largo;
            this.criticidad = criticidad;
        }

        boolean contiene(int ip) {
            int mascara = largo == 0 ? 0 : -1 << (32 - largo);
            return (ip & mascara) == (red & mascara);
        }
    }

    private final List<Prefijo> prefijos = new ArrayList<>();
    private final Criticidad porDefecto;

    public Criticidades(Criticidad porDefecto) {
        this.porDefecto = porDefecto;
    }

    /** "10.200.0.0/24" -> CRITICO. */
    public Criticidades agregar(String prefijo, Criticidad c) {
        String[] p = prefijo.split("/");
        prefijos.add(new Prefijo(ip(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 32, c));
        return this;
    }

    public Criticidad porDefecto() {
        return porDefecto;
    }

    public static int ip(String s) {
        String[] o = s.trim().split("\\.");
        return (Integer.parseInt(o[0]) << 24) | (Integer.parseInt(o[1]) << 16) | (Integer.parseInt(o[2]) << 8)
                | Integer.parseInt(o[3]);
    }

    /** Criticidad de un destino: el prefijo más largo que lo contiene, o la de por defecto. */
    public Criticidad de(String ip) {
        int x = ip(ip);
        Prefijo mejor = null;
        for (Prefijo p : prefijos) {
            if (p.contiene(x) && (mejor == null || p.largo > mejor.largo)) {
                mejor = p;
            }
        }
        return mejor != null ? mejor.criticidad : porDefecto;
    }

    /** La mayor entre los destinos; sin destinos, la de por defecto. */
    public Criticidad maxima(Collection<String> ips) {
        Criticidad c = null;
        for (String ip : ips) {
            Criticidad k = de(ip);
            if (c == null || k.ordinal() > c.ordinal()) {
                c = k;
            }
        }
        return c != null ? c : porDefecto;
    }
}
