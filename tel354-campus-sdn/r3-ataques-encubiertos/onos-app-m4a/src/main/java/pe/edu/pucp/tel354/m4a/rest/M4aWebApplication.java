package pe.edu.pucp.tel354.m4a.rest;

import org.onlab.rest.AbstractWebApplication;

import java.util.Set;

/** Entrada REST de las alertas del IDS en /onos/campus-ids (mismo patrón que la REST de M5). */
public class M4aWebApplication extends AbstractWebApplication {
    @Override
    public Set<Class<?>> getClasses() {
        return getClasses(IdsRecurso.class);
    }
}
