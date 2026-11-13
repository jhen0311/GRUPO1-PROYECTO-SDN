package pe.edu.pucp.tel354.m2.nucleo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prueba del núcleo de M2 sin ONOS (probar_m2.sh): conteo de la Tabla 1 (P + 8), cookies únicas,
 * igualdad con el puente base_slice.py y decisiones de la matriz (rol, curso, ICMP, puertos).
 */
public final class Tabla1Prueba {

    private static int fallas;

    private static void verificar(boolean ok, String que) {
        System.out.println((ok ? "  OK    " : "  FALLA ") + que);
        if (!ok) {
            fallas++;
        }
    }

    /** Inventario del piloto (03_seed_demo.sql): roles con herencia Alumno ⊂ JP ⊂ Docente. */
    private static Politica piloto() {
        List<Politica.Permiso> p = new ArrayList<>();
        p.add(new Politica.Permiso(1, "ALUMNO", 16, 1, "10.200.1.10", "TCP", 443));   // Campus Virtual
        p.add(new Politica.Permiso(1, "ALUMNO", 16, 2, "10.200.1.20", "TCP", 443));   // Biblioteca
        p.add(new Politica.Permiso(2, "JP", 32, 1, "10.200.1.10", "TCP", 443));
        p.add(new Politica.Permiso(2, "JP", 32, 2, "10.200.1.20", "TCP", 443));
        p.add(new Politica.Permiso(3, "DOCENTE", 48, 1, "10.200.1.10", "TCP", 443));
        p.add(new Politica.Permiso(3, "DOCENTE", 48, 2, "10.200.1.20", "TCP", 443));
        p.add(new Politica.Permiso(3, "DOCENTE", 48, 3, "10.200.1.30", "TCP", 5432));  // BD académica
        p.add(new Politica.Permiso(4, "ADMINISTRATIVO", 64, 4, "10.200.1.40", "TCP", 5432));
        p.add(new Politica.Permiso(5, "TI", 80, 5, "10.200.0.5", "TCP", 22));          // gestión TI
        List<Object[]> cursos = new ArrayList<>();
        cursos.add(new Object[]{7, "10.200.2.10", "TCP", 8181});                         // alumno 7 en TEL354
        cursos.add(new Object[]{7, "10.200.2.20", "UDP", 5000});
        return new Politica(p, cursos, 0);
    }

    public static void main(String[] args) {
        Politica pol = piloto();
        String dns = "10.200.0.14";

        System.out.println("Tabla 1");
        List<ReglaT1> t1 = Tabla1.reglas(pol, dns);
        verificar(t1.size() == pol.permisos().size() + 8, "P + 8 reglas: " + t1.size() + " = "
                + pol.permisos().size() + " + 8");
        Set<String> claves = new HashSet<>();
        boolean unicas = true;
        for (ReglaT1 r : t1) {
            unicas &= claves.add(r.clave());
        }
        verificar(unicas, "cookies únicas en el switch (tipo/id/índice)");
        long n100 = t1.stream().filter(r -> r.prioridad == 100).count();
        verificar(n100 == 9, "9 reglas 100 (una por fila de v_permiso_efectivo)");
        List<Integer> prioridades = new ArrayList<>();
        for (ReglaT1 r : Tabla1.comunes(dns)) {
            prioridades.add(r.prioridad);
        }
        verificar(prioridades.equals(Arrays.asList(60, 58, 58, 57, 55, 55, 50, 0)),
                  "comunes como base_slice.py: " + prioridades);
        ReglaT1 cien = t1.get(0);
        verificar("10.16.0.0/12".equals(cien.ipOrigen) && "10.200.1.10/32".equals(cien.ipDestino)
                          && cien.proto == Politica.TCP && cien.puertoDestino == 443 && cien.irATabla == 2
                          && cien.tipo == ReglaT1.TIPO_ROL && cien.id == 1 && cien.indice == 1,
                  "100 del Alumno: src 10.16.0.0/12, dst /32, tcp:443, goto 2, cookie (0x03, recurso 1, k 1)");
        ReglaT1 c55 = Tabla1.comunes(dns).get(4);
        verificar(c55.controlador && c55.meter == ReglaT1.Meter.CAPTURA && "10.0.0.0/8".equals(c55.ipDestino),
                  "55: 10.0.0.0/8 con meter captura al controlador");
        ReglaT1 c57 = Tabla1.comunes(dns).get(3);
        verificar(c57.vlan == 999 && c57.meter == ReglaT1.Meter.CUARENTENA && c57.irATabla == 2,
                  "57: VLAN 999 con meter cuarentena hacia la Tabla 2");
        verificar(Tabla1.comunes(dns).get(7).descarta(), "0: drop por defecto");
        verificar(Tabla1.reglas(new Politica(new ArrayList<>(), new ArrayList<>(), 0), dns).size() == 8,
                  "sin permisos: solo las 8 comunes");

        System.out.println("Matriz");
        int cv = Politica.ipv4("10.200.1.10");
        int bd = Politica.ipv4("10.200.1.30");
        verificar(pol.decidir(16, 1, cv, Politica.TCP, 443) == Politica.Decision.ROL, "Alumno → Campus Virtual: ROL");
        verificar(pol.decidir(16, 1, bd, Politica.TCP, 5432) == Politica.Decision.NINGUNA,
                  "Alumno → BD académica: NINGUNA (DROP 150)");
        verificar(pol.decidir(48, 1, bd, Politica.TCP, 5432) == Politica.Decision.ROL, "Docente → BD académica: ROL");
        verificar(pol.decidir(16, 1, cv, Politica.UDP, 443) == Politica.Decision.NINGUNA, "otro protocolo: NINGUNA");
        verificar(pol.decidir(16, 1, cv, 1, -1) == Politica.Decision.NINGUNA, "ICMP: NINGUNA");
        int lab = Politica.ipv4("10.200.2.10");
        verificar(pol.decidir(16, 7, lab, Politica.TCP, 8181) == Politica.Decision.CURSO,
                  "inscrito en TEL354 → Lab SDN: CURSO");
        verificar(pol.decidir(16, 8, lab, Politica.TCP, 8181) == Politica.Decision.NINGUNA,
                  "no inscrito → Lab SDN: NINGUNA");
        verificar(pol.mismoContenido(piloto()) && !pol.mismoContenido(new Politica(new ArrayList<>(),
                  new ArrayList<>(), 0)), "mismoContenido detecta cambios");
        verificar(Politica.ipv4("10.200.1.10/32") == cv, "ipv4 acepta prefijo /32");

        System.out.println();
        for (ReglaT1 r : t1) {
            System.out.println("    " + r);
        }
        System.out.println(fallas == 0 ? "\nTodo OK" : "\n" + fallas + " fallas");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
