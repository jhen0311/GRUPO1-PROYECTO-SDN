// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.config;

public interface NetworkConfigRegistry {
    void registerConfigFactory(ConfigFactory f);
    void unregisterConfigFactory(ConfigFactory f);
    void addListener(NetworkConfigListener l);
    void removeListener(NetworkConfigListener l);
    <S, C extends Config<S>> C getConfig(S s, Class<C> c);
}
