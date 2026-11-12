// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package com.fasterxml.jackson.databind;

public abstract class JsonNode implements Iterable<JsonNode> {
    public abstract JsonNode path(String f);
    public abstract boolean isArray();
    public abstract String asText();
    public abstract String asText(String d);
    public abstract int asInt(int d);
}
