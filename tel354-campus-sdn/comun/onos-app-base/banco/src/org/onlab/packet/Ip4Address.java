// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class Ip4Address extends IpAddress {
    public static Ip4Address valueOf(String s) { Ip4Address a = new Ip4Address(); a.v = s; return a; }
    public byte[] toOctets() {
        String[] p = v.split("\\.");
        return new byte[] {(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]), (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])};
    }
}
