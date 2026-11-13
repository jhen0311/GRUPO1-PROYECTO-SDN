package pe.edu.pucp.tel354.m5;

import com.fasterxml.jackson.databind.JsonNode;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.Modo;

/**
 * Network config de M5 (clave "r3" de la app tel354.m5; LLSD R3, sección 7). Cambiar un valor no
 * requiere recompilar ni tocar los switches. Ejemplo en r3-netcfg.json.
 */
public class M5Config extends Config<ApplicationId> {

    public static final String CLAVE = "r3";

    /** Valores del diseño (LLSD R3, sección 7; LAB6 para las direcciones). */
    public static final class Valores {
        public Modo modo = Modo.ACTIVO;
        /** Entradas de mitigación por switch de acceso (M; AVZ08, sección 3.6). */
        public int reservaPorSwitch = 96;
        public int n1Seg = 900;
        public int n2Seg = 3600;
        public int sancionHorasPorDefecto = 24;
        /** Meter contención de N2 (kbps), compartido por las sesiones N2 de un switch. */
        public int contencionKbps = 1000;
        public Criticidades criticidades = porDefecto();
        public String jdbcUrl = "jdbc:postgresql://10.200.3.12:5432/campus"
                + "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem";
        public String jdbcUsuario = "onos_m5";
        public String jdbcClave = "m5-demo";

        static Criticidades porDefecto() {
            return new Criticidades(Criticidad.MAYOR)
                    .agregar("10.200.0.0/24", Criticidad.CRITICO)      // servicios de red
                    .agregar("10.200.3.0/24", Criticidad.CRITICO)      // identidad (RADIUS, LDAP, PostgreSQL)
                    .agregar("172.16.0.0/16", Criticidad.CRITICO)      // plano de control
                    .agregar("10.200.1.10/32", Criticidad.CRITICO)     // Campus Virtual
                    .agregar("10.200.1.20/32", Criticidad.MENOR)       // Biblioteca
                    .agregar("10.200.1.30/32", Criticidad.MAYOR)       // BD académica
                    .agregar("10.200.1.40/32", Criticidad.MAYOR)       // BD administrativa
                    .agregar("10.200.2.0/24", Criticidad.MENOR);       // servidores de cursos
        }
    }

    public Valores valores() {
        Valores d = new Valores();
        Valores v = new Valores();
        v.modo = Modo.valueOf(get("modo", d.modo.name()).toUpperCase());
        v.reservaPorSwitch = get("reservaPorSwitch", d.reservaPorSwitch);
        v.n1Seg = get("n1Seg", d.n1Seg);
        v.n2Seg = get("n2Seg", d.n2Seg);
        v.sancionHorasPorDefecto = get("sancionHorasPorDefecto", d.sancionHorasPorDefecto);
        v.contencionKbps = get("contencionKbps", d.contencionKbps);
        v.jdbcUrl = get("jdbcUrl", d.jdbcUrl);
        v.jdbcUsuario = get("jdbcUsuario", d.jdbcUsuario);
        v.jdbcClave = get("jdbcClave", d.jdbcClave);
        JsonNode lista = object.path("criticidad");
        if (lista.isArray() && lista.size() > 0) {
            Criticidad def = Criticidad.valueOf(get("criticidadPorDefecto", Criticidad.MAYOR.name()).toUpperCase());
            v.criticidades = new Criticidades(def);
            for (JsonNode par : lista) {
                v.criticidades.agregar(par.get(0).asText(), Criticidad.valueOf(par.get(1).asText().toUpperCase()));
            }
        }
        return v;
    }
}
