package pe.edu.pucp.tel354.m4a;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

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
}
