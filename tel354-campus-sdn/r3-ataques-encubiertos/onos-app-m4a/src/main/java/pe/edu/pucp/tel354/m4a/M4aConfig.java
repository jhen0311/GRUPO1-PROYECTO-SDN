package pe.edu.pucp.tel354.m4a;

import com.fasterxml.jackson.databind.JsonNode;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;

/** Network config de M4a (clave "r3" de tel354.m4a; LLSD R3, sección 7). */
public class M4aConfig extends Config<ApplicationId> {

    public static final String CLAVE = "r3";

    public int w1() {
        return get("w1", 10);
    }

    public int w2() {
        return get("w2", 600);
    }

    public int umbral(String nombre, int porDefecto) {
        return object.path("umbrales").path(nombre).asInt(porDefecto);
    }

    // ------------------------------------------------------------------ IDS (Suricata)

    /** Ventana, en segundos, durante la cual una alerta repetida del mismo tipo se agrega como actualización. */
    public int idsVentana() {
        return object.path("ids").path("ventanaSeg").asInt(60);
    }

    /**
     * Tipo de ataque de una alerta: primero por sid (ids.firmas), luego por la categoría de Suricata
     * (ids.categorias; es el texto de alert.category, no el nombre corto del classtype) y por último
     * ids.tipoPorDefecto. Devuelve null si ninguno aplica: la alerta se ignora.
     */
    public TipoAtaque tipoIds(int sid, String categoria) {
        JsonNode ids = object.path("ids");
        String t = ids.path("firmas").path(String.valueOf(sid)).asText("");
        if (t.isEmpty() && categoria != null && !categoria.isEmpty()) {
            t = ids.path("categorias").path(categoria).asText("");
        }
        if (t.isEmpty()) {
            t = ids.path("tipoPorDefecto").asText("");
        }
        if (t.isEmpty()) {
            return null;
        }
        try {
            return TipoAtaque.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
