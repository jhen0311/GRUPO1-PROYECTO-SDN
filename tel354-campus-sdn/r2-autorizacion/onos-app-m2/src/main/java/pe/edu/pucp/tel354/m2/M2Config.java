package pe.edu.pucp.tel354.m2;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

/**
 * Network config de tel354.m2 (clave "m2"). La genera gen_m2_netcfg.py desde el perfil.
 *
 * <pre>
 * {"apps": {"tel354.m2": {"m2": {
 *    "tabla1": true, "dns": "10.200.0.14",
 *    "capturaPps": 50, "capturaRafaga": 20, "cuarentenaKbps": 1000, "cuarentenaRafaga": 1000,
 *    "conciliarSeg": 30,
 *    "jdbcUrl": "...", "jdbcUsuario": "onos_m2", "jdbcClave": "m2-demo"}}}}
 * </pre>
 * tabla1 = false deja la Tabla 1 al puente base_auto.py --solo-tabla1 y M2 solo atiende el slow-path.
 */
public class M2Config extends Config<ApplicationId> {

    public static final String CLAVE = "m2";

    public static final class Valores {
        public boolean tabla1 = true;
        public String dns = "10.200.0.14";
        /** Meter captura de la regla 55 (LLSD R3, Tabla 12): 50 pps, ráfaga 20, por switch. */
        public int capturaPps = 50;
        public int capturaRafaga = 20;
        /** Meter cuarentena de la regla 57: 1 Mbps hacia Internet. */
        public int cuarentenaKbps = 1000;
        public int cuarentenaRafaga = 1000;
        /** Revisión completa: relee la matriz y concilia la Tabla 1 de todos los switches. */
        public int conciliarSeg = 30;
        public String jdbcUrl = "jdbc:postgresql://10.200.3.12:5432/campus"
                + "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem";
        public String jdbcUsuario = "onos_m2";
        public String jdbcClave = "m2-demo";
    }

    public Valores valores() {
        Valores d = new Valores();
        Valores v = new Valores();
        v.tabla1 = get("tabla1", d.tabla1);
        v.dns = get("dns", d.dns);
        v.capturaPps = Math.max(1, get("capturaPps", d.capturaPps));
        v.capturaRafaga = Math.max(1, get("capturaRafaga", d.capturaRafaga));
        v.cuarentenaKbps = Math.max(1, get("cuarentenaKbps", d.cuarentenaKbps));
        v.cuarentenaRafaga = Math.max(1, get("cuarentenaRafaga", d.cuarentenaRafaga));
        v.conciliarSeg = Math.max(5, get("conciliarSeg", d.conciliarSeg));
        v.jdbcUrl = get("jdbcUrl", d.jdbcUrl);
        v.jdbcUsuario = get("jdbcUsuario", d.jdbcUsuario);
        v.jdbcClave = get("jdbcClave", d.jdbcClave);
        return v;
    }
}
