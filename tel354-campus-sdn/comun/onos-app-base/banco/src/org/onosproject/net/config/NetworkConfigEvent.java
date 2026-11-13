// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.config;

public class NetworkConfigEvent implements org.onosproject.event.Event<NetworkConfigEvent.Type, Object> {
    public enum Type { CONFIG_ADDED, CONFIG_UPDATED, CONFIG_REMOVED }
    public Type type() { return null; }
    public Object subject() { return null; }
    public long time() { return 0; }
    public Class configClass() { return null; }
}
