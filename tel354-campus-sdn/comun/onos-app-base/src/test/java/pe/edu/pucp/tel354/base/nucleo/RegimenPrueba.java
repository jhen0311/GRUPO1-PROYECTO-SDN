package pe.edu.pucp.tel354.base.nucleo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Prueba del conjunto de reglas de M7 y M8 sin ONOS (probar_base.sh): conteos de la Tabla de
 * reglas (acceso = H + 5, core = 9P + D + 1) y cookies únicas dentro de cada switch.
 */
public final class RegimenPrueba {

    private static int fallas;

    private static void verificar(boolean ok, String que) {
        System.out.println((ok ? "  OK    " : "  FALLA ") + que);
        if (!ok) {
            fallas++;
        }
    }

    private static boolean unicas(List<Regla> r) {
        Set<String> s = new HashSet<>();
        for (Regla x : r) {
            if (!s.add(x.clave())) {
                return false;
            }
        }
        return true;
    }

    private static long cuenta(List<Regla> r, Regla.Modulo m) {
        return r.stream().filter(x -> x.modulo == m).count();
    }

    private static List<Integer> puertos(int h) {
        List<Integer> l = new ArrayList<>();
        for (int p = 2; p < 2 + h; p++) {
            l.add(p);
        }
        return l;
    }

    public static void main(String[] a) {
        List<Regimen.Destino> servidores = Collections.singletonList(
                new Regimen.Destino("10.200.0.0/16", "fa:16:3e:00:00:01", 1));

        System.out.println("== Slice: sw2 (pabellón 1, uplink 1, hosts 2 y 3)");
        List<Regla> sw2 = Regimen.acceso(1, Arrays.asList(2, 3));
        sw2.forEach(r -> System.out.println("    " + r));
        verificar(sw2.size() == 7, "acceso con H = 2: 7 reglas (" + sw2.size() + ")");
        verificar(cuenta(sw2, Regla.Modulo.M7) == 5 && cuenta(sw2, Regla.Modulo.M8) == 2, "M7 5 y M8 2");
        verificar(unicas(sw2), "cookies únicas en el switch de acceso");

        System.out.println("== Slice: sw3 (pabellón 2, uplink 3, hosts 1 y 2: cableado cruzado)");
        List<Regla> sw3 = Regimen.acceso(3, Arrays.asList(1, 2));
        verificar(sw3.size() == 7, "acceso con el uplink en el puerto 3: 7 reglas");
        verificar(sw3.stream().anyMatch(r -> r.prioridad == 600 && r.puertoEntrada == 3), "600 en el puerto 3");

        System.out.println("== Acceso sin enlace con el core");
        List<Regla> sin = Regimen.acceso(-1, Collections.<Integer>emptyList());
        verificar(sin.size() == 2, "solo 900 y 10 (" + sin.size() + ")");

        System.out.println("== Core del slice (pabellón 1 por el puerto 2, pabellón 2 por el 3, servidores por el 1)");
        Map<Integer, Integer> pab = new TreeMap<>();
        pab.put(1, 2);
        pab.put(2, 3);
        List<Regla> core = Regimen.core(pab, servidores);
        core.forEach(r -> System.out.println("    " + r));
        verificar(core.size() == 20, "core con P = 2 y D = 1: 20 reglas (" + core.size() + ")");
        verificar(unicas(core), "cookies únicas en el core");

        System.out.println("== Fórmulas (independientes del número de usuarios)");
        boolean okAcc = true;
        for (int h = 0; h <= 48; h++) {
            List<Regla> r = Regimen.acceso(1, puertos(h));
            okAcc &= r.size() == h + 5 && unicas(r);
        }
        verificar(okAcc, "acceso = H + 5 para H = 0..48");
        boolean okCore = true;
        for (int p = 1; p <= Regimen.PABELLON_MAX; p++) {
            Map<Integer, Integer> m = new TreeMap<>();
            for (int n = 1; n <= p; n++) {
                m.put(n, n + 1);
            }
            List<Regla> r = Regimen.core(m, servidores);
            okCore &= r.size() == 9 * p + 1 + 1 && unicas(r);
        }
        verificar(okCore, "core = 9P + D + 1 para P = 1..15");
        Map<Integer, Integer> fuera = new TreeMap<>();
        fuera.put(16, 5);
        verificar(Regimen.core(fuera, servidores).size() == 2, "pabellón 16 fuera del plan: se ignora");

        System.out.println(fallas == 0 ? "Resultado: todas las verificaciones pasan" : "Resultado: " + fallas + " fallas");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
