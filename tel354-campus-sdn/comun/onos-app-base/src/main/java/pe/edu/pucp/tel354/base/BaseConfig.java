package pe.edu.pucp.tel354.base;

import com.fasterxml.jackson.databind.JsonNode;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Network config de tel354.base (clave "base"). La genera gen_base_netcfg.py desde el perfil de
 * despliegue. Lo que cambia con cada pabellón (dpid → pabellón) no está aquí: sale de la tabla
 * switch_acceso, así que agregar un switch no exige tocar esta configuración.
 *
 * <pre>
 * {"apps": {"tel354.base": {"base": {
 *    "core": "of:00000000000000c1",
 *    "destinos": [{"prefijo": "10.200.0.0/16", "mac": "fa:16:3e:..", "puerto": 0}],
 *    "ctlPps": 10, "ctlRafaga": 10, "conciliarSeg": 30,
 *    "jdbcUrl": "...", "jdbcUsuario": "onos_m7", "jdbcClave": "m7-demo"}}}}
 * </pre>
 * puerto 0 = se descubre (el único puerto del core sin enlace).
 */
public class BaseConfig extends Config<ApplicationId> {

    public static final String CLAVE = "base";

    /** Destino fijo del core tal como viene en la configuración (puerto 0 = descubrir). */
    public static final class DestinoCfg {
        public final String prefijo;
        public final String mac;
        public final int puerto;

        public DestinoCfg(String prefijo, String mac, int puerto) {
            this.prefijo = prefijo;
            this.mac = mac;
            this.puerto = puerto;
        }
    }

    public static final class Valores {
        public String core = "of:00000000000000c1";
        public List<DestinoCfg> destinos = new ArrayList<>();
        /** Meter ctl_P por puerto de host (pps y ráfaga); 0 = sin meters. */
        public int ctlPps = 10;
        public int ctlRafaga = 10;
        /** Periodo de la conciliación completa (relee switch_acceso). */
        public int conciliarSeg = 30;
        public String jdbcUrl = "jdbc:postgresql://10.200.3.12:5432/campus"
                + "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem";
        public String jdbcUsuario = "onos_m7";
        public String jdbcClave = "m7-demo";
    }

    public Valores valores() {
        Valores d = new Valores();
        Valores v = new Valores();
        v.core = get("core", d.core);
        v.ctlPps = get("ctlPps", d.ctlPps);
        v.ctlRafaga = get("ctlRafaga", d.ctlRafaga);
        v.conciliarSeg = Math.max(5, get("conciliarSeg", d.conciliarSeg));
        v.jdbcUrl = get("jdbcUrl", d.jdbcUrl);
        v.jdbcUsuario = get("jdbcUsuario", d.jdbcUsuario);
        v.jdbcClave = get("jdbcClave", d.jdbcClave);
        JsonNode lista = object.path("destinos");
        if (lista.isArray()) {
            for (JsonNode x : lista) {
                v.destinos.add(new DestinoCfg(x.path("prefijo").asText(), x.path("mac").asText(""),
                                              x.path("puerto").asInt(0)));
            }
        }
        return v;
    }
}
