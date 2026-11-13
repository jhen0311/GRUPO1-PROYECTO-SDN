package pe.edu.pucp.tel354.m5.rest;

import org.onlab.rest.AbstractWebApplication;

import java.util.Set;

/** API REST de TI en /onos/campus (LLSD R3, sección 5.8). */
public class M5WebApplication extends AbstractWebApplication {
    @Override
    public Set<Class<?>> getClasses() {
        return getClasses(CampusRecurso.class);
    }
}
