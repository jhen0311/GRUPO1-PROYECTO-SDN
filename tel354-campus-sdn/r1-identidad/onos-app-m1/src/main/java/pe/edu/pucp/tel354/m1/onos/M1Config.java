package pe.edu.pucp.tel354.m1.onos;

import com.fasterxml.jackson.databind.JsonNode;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import pe.edu.pucp.tel354.m1.nucleo.Parametros;

/**
 * Network config de M1 (clave "m1" de la app tel354.m1). Ejemplo en m1-netcfg.json:
 * <pre>
 * {"apps": {"tel354.m1": {"m1": {
 *     "nasIpPorNodo": {"onos-1": "172.16.0.11", "onos-2": "172.16.0.12", "onos-3": "172.16.0.13"},
 *     "radiusIp": "10.200.3.10", "radiusSecreto": "tel354-secreto"}}}}
 * </pre>
 * Toda clave ausente toma el valor del diseño (clase Parametros). La network config es del
 * clúster: cada instancia toma de nasIpPorNodo la dirección in-band que le corresponde.
 */
public class M1Config extends Config<ApplicationId> {

    public static final String CLAVE = "m1";

    /** @param nodo identificador de esta instancia (cluster.json: node.id) */
    public Parametros parametros(String nodo) {
        Parametros d = new Parametros();
        Parametros p = new Parametros();
        p.nasIp = get("nasIp", d.nasIp);
        JsonNode mapa = object.path("nasIpPorNodo");
        if (mapa.has(nodo)) {
            p.nasIp = mapa.get(nodo).asText();
        }
        p.relayIp = p.nasIp;
        p.radiusIp = get("radiusIp", d.radiusIp);
        p.radiusPuerto = get("radiusPuerto", d.radiusPuerto);
        p.radiusSecreto = get("radiusSecreto", d.radiusSecreto);
        p.radiusEsperaSeg = get("radiusEsperaSeg", d.radiusEsperaSeg);
        p.radiusReintentos = get("radiusReintentos", d.radiusReintentos);
        p.framedMtu = get("framedMtu", d.framedMtu);
        p.keaIp = get("keaIp", d.keaIp);
        p.keaCtrlUrl = get("keaCtrlUrl", d.keaCtrlUrl);
        p.jdbcUrl = get("jdbcUrl", d.jdbcUrl);
        p.jdbcUsuario = get("jdbcUsuario", d.jdbcUsuario);
        p.jdbcClave = get("jdbcClave", d.jdbcClave);
        p.macAutenticador = get("macAutenticador", d.macAutenticador);
        p.rebotarPuertoAlExpirar = get("rebotarPuertoAlExpirar", d.rebotarPuertoAlExpirar);
        p.autenticandoSeg = get("autenticandoSeg", d.autenticandoSeg);
        p.sinDhcpSeg = get("sinDhcpSeg", d.sinDhcpSeg);
        p.rebotePuertoSeg = get("rebotePuertoSeg", d.rebotePuertoSeg);
        p.eapIdentidadIntervaloSeg = get("eapIdentidadIntervaloSeg", d.eapIdentidadIntervaloSeg);
        p.eapIdentidadIntentos = get("eapIdentidadIntentos", d.eapIdentidadIntentos);
        return p;
    }
}
