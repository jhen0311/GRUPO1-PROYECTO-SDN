package pe.edu.pucp.tel354.m1.onos;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import pe.edu.pucp.tel354.m1.nucleo.Parametros;

/**
 * Network config de M1 (clave "m1" de la app tel354.m1). Ejemplo en m1-netcfg.json:
 * <pre>
 * {"apps": {"tel354.m1": {"m1": {"radiusIp": "10.200.0.10", "radiusSecreto": "tel354-secreto"}}}}
 * </pre>
 * Toda clave ausente toma el valor del diseño (clase Parametros).
 */
public class M1Config extends Config<ApplicationId> {

    public static final String CLAVE = "m1";

    public Parametros parametros() {
        Parametros d = new Parametros();
        Parametros p = new Parametros();
        p.nasIp = get("nasIp", d.nasIp);
        p.radiusIp = get("radiusIp", d.radiusIp);
        p.radiusPuerto = get("radiusPuerto", d.radiusPuerto);
        p.radiusSecreto = get("radiusSecreto", d.radiusSecreto);
        p.radiusEsperaSeg = get("radiusEsperaSeg", d.radiusEsperaSeg);
        p.radiusReintentos = get("radiusReintentos", d.radiusReintentos);
        p.framedMtu = get("framedMtu", d.framedMtu);
        p.relayIp = get("relayIp", d.relayIp);
        p.keaIp = get("keaIp", d.keaIp);
        p.jdbcUrl = get("jdbcUrl", d.jdbcUrl);
        p.jdbcUsuario = get("jdbcUsuario", d.jdbcUsuario);
        p.jdbcClave = get("jdbcClave", d.jdbcClave);
        p.macAutenticador = get("macAutenticador", d.macAutenticador);
        p.rebotarPuertoAlExpirar = get("rebotarPuertoAlExpirar", d.rebotarPuertoAlExpirar);
        return p;
    }
}
