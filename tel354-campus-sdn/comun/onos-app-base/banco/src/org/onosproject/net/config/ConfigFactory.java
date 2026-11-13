// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.config;

public abstract class ConfigFactory<S, C extends Config<S>> {
    protected ConfigFactory(SubjectFactory<S> f, Class<C> c, String clave) { }
    public abstract C createConfig();
}
