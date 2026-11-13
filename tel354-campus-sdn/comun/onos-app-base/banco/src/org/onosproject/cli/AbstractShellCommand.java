// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.cli;

public abstract class AbstractShellCommand {
    protected abstract void doExecute() throws Exception;
    public static <T> T get(Class<T> c) { return null; }
    protected void print(String f, Object... a) { System.out.println(String.format(f, a)); }
}
