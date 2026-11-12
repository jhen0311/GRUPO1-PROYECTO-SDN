// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** El log de la app se guarda en LINEAS para que el banco lo consulte (y lo imprima con -v). */
public final class LoggerFactory {
    public static final List<String> LINEAS = new CopyOnWriteArrayList<>();
    public static volatile boolean imprimir;

    private LoggerFactory() { }

    public static Logger getLogger(Class<?> c) {
        return new Logger() {
            public void info(String f, Object... a) { linea("INFO ", f, a); }
            public void warn(String f, Object... a) { linea("WARN ", f, a); }
        };
    }

    private static void linea(String nivel, String f, Object[] a) {
        StringBuilder b = new StringBuilder(nivel);
        int i = 0;
        int p;
        int desde = 0;
        while ((p = f.indexOf("{}", desde)) >= 0 && i < a.length) {
            b.append(f, desde, p).append(a[i++]);
            desde = p + 2;
        }
        b.append(f.substring(desde));
        if (i < a.length && a[a.length - 1] instanceof Throwable) {
            java.io.StringWriter w = new java.io.StringWriter();
            ((Throwable) a[a.length - 1]).printStackTrace(new java.io.PrintWriter(w));
            b.append('\n').append(w);
        }
        LINEAS.add(b.toString());
        if (imprimir) {
            System.out.println("      | " + b);
        }
    }
}
