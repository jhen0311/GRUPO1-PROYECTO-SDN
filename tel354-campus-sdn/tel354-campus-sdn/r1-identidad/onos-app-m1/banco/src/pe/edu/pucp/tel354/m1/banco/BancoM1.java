package pe.edu.pucp.tel354.m1.banco;

import pe.edu.pucp.tel354.m1.nucleo.M1Nucleo;
import pe.edu.pucp.tel354.m1.nucleo.Parametros;
import pe.edu.pucp.tel354.m1.nucleo.Plataforma;
import pe.edu.pucp.tel354.m1.nucleo.Sesion;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plataforma de prueba: el "switch" es puente.py; las reglas se imprimen como en dump-flows. */
public class BancoM1 implements Plataforma {
    static final String DPID = "of:0000000000000001";
    final DatagramSocket s;
    final InetSocketAddress puente = new InetSocketAddress("127.0.0.1", 9901);

    BancoM1() throws Exception {
        s = new DatagramSocket(new InetSocketAddress("127.0.0.1", 9900));
    }

    public void enviarTrama(String dpid, int puerto, byte[] t) {
        byte[] d = new byte[t.length + 1];
        d[0] = (byte) puerto;
        System.arraycopy(t, 0, d, 1, t.length);
        try {
            s.send(new DatagramPacket(d, d.length, puente));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String r(String txt) {
        System.out.println("REGLA+ " + txt);
        return txt;
    }

    public List<Object> instalarAutenticacion(Sesion x, List<Object> antes) {
        retirar(antes);
        List<Object> l = new ArrayList<>();
        int t = x.restanteSeg();
        l.add(r(String.format("table=0 priority=525 in_port=%d,dl_src=%s,udp,tp_dst=67 actions=CONTROLLER hard=%d", x.puerto, x.mac, t)));
        l.add(r(String.format("table=0 priority=505 in_port=%d,dl_src=%s,ip actions=CONTROLLER hard=%d", x.puerto, x.mac, t)));
        return l;
    }

    public List<Object> instalarActivacion(Sesion x, List<AccesoCurso> cursos, List<Object> antes) {
        retirar(antes);
        List<Object> l = new ArrayList<>();
        int t = x.restanteSeg();
        l.add(r(String.format("table=0 priority=520 in_port=%d,dl_src=%s,ip,nw_src=%s actions=push_vlan,set_vlan:%d,goto_table:1 hard=%d", x.puerto, x.mac, x.ip, x.vlan, t)));
        l.add(r(String.format("table=2 priority=200 dl_vlan=%d,ip,nw_dst=%s actions=pop_vlan,set_dl_dst:%s,output:%d", x.vlan, x.ip, x.mac, x.puerto)));
        l.add(r(String.format("table=2 priority=190 dl_vlan=%d,dl_dst=%s actions=pop_vlan,output:%d", x.vlan, x.mac, x.puerto)));
        for (AccesoCurso c : cursos) {
            l.add(r(String.format("table=1 priority=110 ip,nw_src=%s,nw_dst=%s,%s,tp_dst=%d actions=goto_table:2  (curso %s)", x.ip, c.ip, c.protocolo.toLowerCase(), c.puerto, c.curso)));
        }
        return l;
    }

    public void retirar(List<Object> reglas) {
        for (Object o : reglas) {
            System.out.println("REGLA- " + o);
        }
    }

    public void rebotarPuerto(String dpid, int puerto) {
        System.out.println("REBOTE " + dpid + "/" + puerto);
    }

    public static void main(String[] a) throws Exception {
        BancoM1 b = new BancoM1();
        Parametros p = new Parametros();
        M1Nucleo n = new M1Nucleo(p, b);
        n.iniciar();
        System.out.println("banco M1 listo");
        byte[] buf = new byte[9000];
        while (true) {
            DatagramPacket dg = new DatagramPacket(buf, buf.length);
            b.s.receive(dg);
            byte[] d = Arrays.copyOf(dg.getData(), dg.getLength());
            n.procesarTrama(DPID, d[0], Arrays.copyOfRange(d, 1, d.length));
        }
    }
}
