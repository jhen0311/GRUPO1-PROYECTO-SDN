package pe.edu.pucp.tel354.m1.nucleo;

/** EtherTypes que procesa M1. */
public final class EthTipos {
    public static final short IPV4 = 0x0800;
    public static final short ARP = 0x0806;
    public static final short VLAN = (short) 0x8100;
    public static final short EAPOL = (short) 0x888e;

    private EthTipos() {
    }
}
