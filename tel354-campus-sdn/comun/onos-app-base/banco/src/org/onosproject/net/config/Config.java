// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.config;

public abstract class Config<S> {
    protected com.fasterxml.jackson.databind.node.ObjectNode object;
    protected String get(String k, String d) { return d; }
    protected int get(String k, int d) { return d; }
}
