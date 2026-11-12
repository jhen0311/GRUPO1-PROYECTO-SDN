// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class Ip4Prefix extends IpPrefix {
    public static Ip4Prefix valueOf(String s) { Ip4Prefix a = new Ip4Prefix(); a.v = s; return a; }
}
