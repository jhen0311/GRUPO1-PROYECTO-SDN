package pe.edu.pucp.tel354.base.banco;

import org.onosproject.app.ApplicationState;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterId;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.base.BaseConfig;
import pe.edu.pucp.tel354.base.BaseManager;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Banco de pruebas de tel354.base (M7 + M8) sin ONOS: ejecuta el BaseManager real contra un ONOS y
 * unos switches simulados (SimOnos) y recorre los escenarios del plan de pruebas (B2 a B11) y los
 * casos de robustez que no se pueden provocar a voluntad en el slice. Lo lanza banco.sh.
 *
 * Topología: la del slice. Core c1 (servidores por el puerto 1), sw 1 (pabellón 1, uplink 1, hosts
 * 2 y 3) y sw 2 (pabellón 2, uplink 3, hosts 1 y 2: cableado cruzado).
 */
public final class BancoBase {

    private static final String M7 = "tel354.m7";
    private static final String M8 = "tel354.m8";
    private static int fallas;
    private static int pruebas;

    /** La app real, con la configuración y el inventario en manos del banco. */
    static final class App extends BaseManager {
        final Map<String, Integer> inventario;
        volatile boolean bdCaida;
        final BaseConfig.Valores valores = new BaseConfig.Valores();

        App(SimOnos o, Map<String, Integer> inventario, int puertoServidores) {
            coreService = o;
            applicationService = o;
            deviceService = o;
            linkService = o;
            flowRuleService = o;
            meterService = o;
            mastershipService = o;
            netcfg = o;
            this.inventario = inventario;
            valores.core = SimOnos.dpid("c1").toString();
            valores.destinos = Collections.singletonList(
                    new BaseConfig.DestinoCfg("10.200.0.0/16", "fa:16:3e:00:00:01", puertoServidores));
            valores.conciliarSeg = 2;                       // en producción, 30 s
        }

        @Override
        protected BaseConfig.Valores cargarConfig() {
            return valores;
        }

        @Override
        protected Map<String, Integer> cargarInventario() throws Exception {
            if (bdCaida) {
                throw new SQLException("Connection to 10.200.3.12:5432 refused");
            }
            return new HashMap<>(inventario);
        }

        void arrancar() {
            activate();
        }

        void detener() {
            deactivate();
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean esperar(BooleanSupplier c, long ms) {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            if (c.getAsBoolean()) {
                return true;
            }
            dormir(50);
        }
        return c.getAsBoolean();
    }

    private static void ok(boolean cumple, String que) {
        pruebas++;
        System.out.println((cumple ? "  OK    " : "  FALLA ") + que);
        if (!cumple) {
            fallas++;
        }
    }

    private static int reglas(SimOnos o, String sw, String app) {
        short id = o.registerApplication(app).id();
        return (int) o.switches.get(SimOnos.dpid(sw)).almacen.values().stream().filter(r -> r.appId() == id).count();
    }

    private static int reglas(SimOnos o, String sw) {
        return reglas(o, sw, M7) + reglas(o, sw, M8);
    }

    private static int meters(SimOnos o, String sw) {
        return (int) o.switches.get(SimOnos.dpid(sw)).metersAlmacen.values().stream()
                .filter(m -> m.appId().name().equals(M7)).count();
    }

    private static int mods(SimOnos o) {
        return o.altas.get() + o.bajas.get() + o.metersCreados.get() + o.metersRetirados.get();
    }

    /** Puerto de entrada de la regla de M7 con esa prioridad, o -1. */
    private static int puertoDe(SimOnos o, String sw, int prioridad) {
        short id = o.registerApplication(M7).id();
        for (DefaultFlowRule r : o.switches.get(SimOnos.dpid(sw)).almacen.values()) {
            if (r.appId() == id && r.priority() == prioridad) {
                Criterion c = r.selector().getCriterion(Criterion.Type.IN_PORT);
                return c == null ? -1 : (int) ((PortCriterion) c).port().toLong();
            }
        }
        return -1;
    }

    /** Puerto de salida de la primera regla del core cuyo texto contiene {@code texto}, o -1. */
    private static int salidaCore(SimOnos o, String texto) {
        for (DefaultFlowRule r : o.switches.get(SimOnos.dpid("c1")).almacen.values()) {
            if (r.toString().contains(texto)) {
                for (Instruction i : r.treatment().allInstructions()) {
                    if (i instanceof Instructions.OutputInstruction) {
                        return (int) ((Instructions.OutputInstruction) i).port().toLong();
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Estado final coherente: toda regla de M7 y M8 del almacén está en el switch y en ADDED, y
     * toda regla con meter usa un meter de M7 que existe en el almacén y en el switch.
     * Devuelve null si es coherente, o la primera incoherencia.
     */
    private static String incoherencia(SimOnos o) {
        short m7 = o.registerApplication(M7).id();
        short m8 = o.registerApplication(M8).id();
        for (SimOnos.Sw s : o.switches.values()) {
            for (DefaultFlowRule r : s.almacen.values()) {
                if (r.appId() != m7 && r.appId() != m8) {
                    continue;
                }
                if (!s.instaladas.containsKey(r.id().value())) {
                    return s.id + ": en el almacén pero no en el switch: " + r;
                }
                if (r.state() != FlowEntry.FlowEntryState.ADDED) {
                    return s.id + ": no está ADDED: " + r;
                }
                Instructions.MeterInstruction mi = r.treatment().metered();
                if (mi != null) {
                    Meter m = s.metersAlmacen.get(mi.meterId());
                    if (m == null || !m.appId().name().equals(M7) || !s.metersInstalados.contains(mi.meterId())) {
                        return s.id + ": usa un meter que no existe: " + r;
                    }
                }
            }
        }
        return null;
    }

    private static boolean coherente(SimOnos o) {
        return esperar(() -> incoherencia(o) == null, 6000);
    }

    private static long lineas(String texto) {
        return LoggerFactory.LINEAS.stream().filter(l -> l.contains(texto)).count();
    }

    private static void titulo(String t) {
        System.out.println("== " + t);
    }

    // ------------------------------------------------------------------ escenarios

    public static void main(String[] a) {
        LoggerFactory.imprimir = a.length > 0 && a[0].equals("-v");
        long t0 = System.currentTimeMillis();
        slice();
        pabellonApagado();
        System.out.println();
        System.out.println(String.format("%d verificaciones en %.0f s. %s", pruebas, (System.currentTimeMillis() - t0) / 1000.0,
                                         fallas == 0 ? "Resultado: todas las verificaciones pasan" : "Resultado: " + fallas + " fallas"));
        System.exit(fallas == 0 ? 0 : 1);
    }

    private static void slice() {
        SimOnos o = new SimOnos();
        Map<String, Integer> inv = new ConcurrentHashMap<>();
        inv.put(SimOnos.dpid("1").toString(), 1);
        inv.put(SimOnos.dpid("2").toString(), 2);

        titulo("E1 (B2). Arranque: los switches se conectan antes de que ONOS descubra los enlaces");
        o.conectar("c1", 1, 2, 3);
        o.conectar("1", 1, 2, 3);
        o.conectar("2", 1, 2, 3);
        App app = new App(o, inv, 0);
        app.arrancar();
        ok(esperar(() -> reglas(o, "1") == 2 && reglas(o, "2") == 2, 4000),
           "sin enlaces: cada switch de acceso tiene solo la 900 y la 10 (" + reglas(o, "1") + ", " + reglas(o, "2") + ")");
        ok(reglas(o, "c1") == 1 && meters(o, "1") == 0,
           "sin enlaces: el core tiene solo el descarte y aún no hay meters (" + reglas(o, "c1") + " reglas)");
        o.enlazar("c1", 2, "1", 1);
        o.enlazar("c1", 3, "2", 3);
        ok(esperar(() -> reglas(o, "1") == 7 && reglas(o, "2") == 7 && reglas(o, "c1") == 20, 8000),
           "con enlaces: 7 + 7 + 20 reglas (" + reglas(o, "1") + ", " + reglas(o, "2") + ", " + reglas(o, "c1") + ")");
        ok(reglas(o, "1", M7) == 5 && reglas(o, "1", M8) == 2 && reglas(o, "c1", M7) == 0,
           "appId por módulo: acceso M7 5 + M8 2; core solo M8");
        ok(meters(o, "1") == 2 && meters(o, "2") == 2, "2 meters ctl_P por switch de acceso");
        ok(puertoDe(o, "1", 600) == 1 && puertoDe(o, "2", 600) == 3, "uplink descubierto: sw1 puerto 1, sw2 puerto 3 (cruzado)");
        ok(salidaCore(o, "10.200.0.0/16") == 1, "servidores por el único puerto libre del core (1)");
        ok(coherente(o), "estado coherente: todo ADDED y cada 800 con su meter" + nota(o));
        ok(o.rechazadas.get() == 0, "ninguna regla rechazada por el switch (el meter llega antes que su 800)");
        ok(esperar(() -> lineas("régimen base completo") == 3, 4000),
           "una línea «régimen base completo» por switch (" + lineas("régimen base completo") + ")");

        titulo("E2 (B4). Reinicio de la app sin desactivarla");
        int antes = mods(o);
        app.detener();
        app = new App(o, inv, 0);
        app.arrancar();
        dormir(3500);
        ok(mods(o) == antes, "0 Flow-Mods y 0 meters nuevos tras el reinicio (" + (mods(o) - antes) + ")");
        ok(reglas(o, "1") == 7 && reglas(o, "2") == 7 && reglas(o, "c1") == 20 && coherente(o), "régimen intacto");

        titulo("E3. Reinicio de la app cuando ONOS aún no ha redescubierto los enlaces");
        app.detener();
        o.desenlazar("c1", "1", false);
        o.desenlazar("c1", "2", false);
        antes = mods(o);
        app = new App(o, inv, 0);
        app.arrancar();
        dormir(3500);
        ok(mods(o) == antes, "sin enlaces a la vista no retira nada: usa lo último conocido, leído de sus reglas ("
                             + (mods(o) - antes) + " cambios)");
        o.enlazar("c1", 2, "1", 1);
        o.enlazar("c1", 3, "2", 3);
        dormir(2500);
        ok(mods(o) == antes && coherente(o), "al reaparecer los enlaces tampoco cambia nada");

        titulo("E4. El descubrimiento pierde un enlace con la red en marcha");
        antes = mods(o);
        o.desenlazar("c1", "1", true);
        dormir(3000);
        ok(mods(o) == antes && reglas(o, "1") == 7 && reglas(o, "c1") == 20, "no retira la 600, la Tabla 2 ni el tramo del core");
        o.enlazar("c1", 2, "1", 1);

        titulo("E5 (B5). Pabellón nuevo: una fila y el switch se conecta");
        inv.put(SimOnos.dpid("3").toString(), 3);
        o.agregarPuerto("c1", 4);
        long ini = System.currentTimeMillis();
        o.conectar("3", 1, 2, 3);
        o.enlazar("c1", 4, "3", 1);
        boolean listo = esperar(() -> reglas(o, "3") == 7 && meters(o, "3") == 2 && reglas(o, "c1") == 29, 8000);
        ok(listo, "switch nuevo con 7 reglas y 2 meters; core con 29 (" + reglas(o, "3") + ", " + meters(o, "3") + ", "
                  + reglas(o, "c1") + ") en " + (System.currentTimeMillis() - ini) + " ms simulados");
        ok(salidaCore(o, "10.200.0.0/16") == 1, "el puerto de los servidores no cambia al aparecer el puerto 4");
        ok(coherente(o) && o.rechazadas.get() == 0, "estado coherente y sin rechazos" + nota(o));

        titulo("E6 (B6). La base de datos manda: sw2 pasa del pabellón 2 al 4");
        int altas = o.altas.get();
        int bajas = o.bajas.get();
        int acceso = reglas(o, "2");
        inv.put(SimOnos.dpid("2").toString(), 4);
        ok(esperar(() -> o.altas.get() - altas == 9 && o.bajas.get() - bajas == 9, 6000),
           "core: +9 -9 reglas (+" + (o.altas.get() - altas) + " -" + (o.bajas.get() - bajas) + ")");
        ok(salidaCore(o, "VLAN_VID=104") == 3 && salidaCore(o, "VLAN_VID=102") == -1 && reglas(o, "c1") == 29,
           "VLAN 104 por el puerto de sw2 y ninguna regla de la VLAN 102");
        ok(reglas(o, "2") == acceso && coherente(o), "las reglas del switch de acceso no cambian");

        titulo("E7 (B7). Switch que no está en switch_acceso");
        o.conectar("9", 1, 2, 3);
        o.enlazar("c1", 5, "9", 1);
        dormir(3000);
        ok(reglas(o, "9") == 0 && meters(o, "9") == 0, "0 reglas y 0 meters de M7 y M8");
        ok(reglas(o, "c1") == 29, "el core no le abre camino");

        titulo("E8 (B8). Puerto de host nuevo y puerto retirado");
        o.agregarPuerto("1", 4);
        ok(esperar(() -> reglas(o, "1") == 8 && meters(o, "1") == 3, 6000),
           "al agregar: 8 reglas y 3 meters (" + reglas(o, "1") + ", " + meters(o, "1") + ")");
        ok(coherente(o) && o.rechazadas.get() == 0, "la 800 nueva sale después de su meter" + nota(o));
        o.quitarPuerto("1", 4);
        ok(esperar(() -> reglas(o, "1") == 7 && meters(o, "1") == 2, 6000),
           "al quitar: 7 reglas y 2 meters (" + reglas(o, "1") + ", " + meters(o, "1") + ")");
        ok(coherente(o), "estado coherente" + nota(o));

        titulo("E9 (B10). Caída de PostgreSQL");
        antes = mods(o);
        long avisos = lineas("no se pudo leer switch_acceso");
        app.bdCaida = true;
        dormir(5000);
        ok(mods(o) == antes, "el régimen no cambia con la base caída");
        ok(lineas("no se pudo leer switch_acceso") - avisos == 1, "un solo aviso en el log");
        long vueltas = lineas("switch_acceso con");
        app.bdCaida = false;
        ok(esperar(() -> lineas("switch_acceso con") > vueltas, 4000) && mods(o) == antes, "al volver la base se relee y no cambia nada");

        titulo("E10 (B9). Reinicio de un switch: pierde reglas y meters, mismo datapath-id");
        antes = mods(o);
        int re = o.reinstaladas.get();
        o.reiniciarSwitch("1");
        ok(esperar(() -> o.switches.get(SimOnos.dpid("1")).instaladas.size() == 7
                         && o.switches.get(SimOnos.dpid("1")).metersInstalados.size() == 2, 9000),
           "el switch recupera sus 7 reglas y 2 meters (" + o.switches.get(SimOnos.dpid("1")).instaladas.size() + ", "
           + o.switches.get(SimOnos.dpid("1")).metersInstalados.size() + ")");
        ok(mods(o) == antes && o.reinstaladas.get() - re == 7, "las reinstala ONOS desde su almacén; la app no envía nada");
        ok(coherente(o), "estado coherente" + nota(o));

        titulo("E11. Alguien borra un meter ctl_P (p. ej., por REST)");
        SimOnos.Sw sw2 = o.switches.get(SimOnos.dpid("2"));
        MeterId victima = sw2.metersAlmacen.keySet().iterator().next();
        o.withdraw(DefaultMeterRequest.builder().forDevice(sw2.id).fromApp(o.registerApplication(M7))
                           .withUnit(Meter.Unit.PKTS_PER_SEC).burst()
                           .withBands(Collections.singletonList(DefaultBand.builder().withRate(10).build())).remove(), victima);
        ok(esperar(() -> meters(o, "2") == 2 && !sw2.metersAlmacen.containsKey(victima) && incoherencia(o) == null, 12000),
           "la app crea otro meter y reemplaza la 800 que usaba el borrado" + nota(o));
        ok(reglas(o, "2") == 7, "sw2 sigue con 7 reglas");

        titulo("E12. Un switch sale de switch_acceso");
        inv.remove(SimOnos.dpid("3").toString());
        ok(esperar(() -> reglas(o, "3") == 0 && meters(o, "3") == 0 && reglas(o, "c1") == 20, 8000),
           "se retiran sus reglas, sus meters y su tramo del core (" + reglas(o, "3") + ", " + meters(o, "3") + ", "
           + reglas(o, "c1") + ")");
        ok(reglas(o, "1") == 7 && reglas(o, "2") == 7 && coherente(o), "los demás switches no cambian");

        titulo("E13 (B11). Desactivación de la app");
        o.estadoApp = ApplicationState.INSTALLED;
        app.detener();
        ok(reglas(o, "1") + reglas(o, "2") + reglas(o, "c1") == 0, "0 reglas de M7 y M8");
        ok(meters(o, "1") + meters(o, "2") == 0, "0 meters de M7");
        dormir(1500);
        ok(mods(o) >= 0 && reglas(o, "1") + reglas(o, "2") + reglas(o, "c1") == 0, "ya detenida, la app no reinstala nada");
    }

    /** Con un pabellón apagado, el puerto de los servidores no se puede descubrir: debe venir fijado. */
    private static void pabellonApagado() {
        Map<String, Integer> inv = new ConcurrentHashMap<>();
        inv.put(SimOnos.dpid("1").toString(), 1);
        inv.put(SimOnos.dpid("2").toString(), 2);

        titulo("E14. Arranque con el pabellón 2 apagado y el puerto de servidores por descubrir");
        SimOnos o = new SimOnos();
        o.conectar("c1", 1, 2, 3);
        o.conectar("1", 1, 2, 3);
        o.enlazar("c1", 2, "1", 1);
        App app = new App(o, inv, 0);
        app.arrancar();
        ok(esperar(() -> reglas(o, "1") == 7 && reglas(o, "c1") == 10, 8000),
           "sw1 completo y su tramo del core (" + reglas(o, "1") + ", " + reglas(o, "c1") + ")");
        ok(salidaCore(o, "10.200.0.0/16") == -1 && lineas("sin resolver") > 0,
           "limitación: con dos puertos libres en el core no se instala la ruta a los servidores, y el log lo dice");
        app.detener();

        titulo("E15. El mismo arranque con el puerto fijado en la network config (lo que hace el instalador)");
        SimOnos o2 = new SimOnos();
        o2.conectar("c1", 1, 2, 3);
        o2.conectar("1", 1, 2, 3);
        o2.enlazar("c1", 2, "1", 1);
        App app2 = new App(o2, inv, 1);
        app2.arrancar();
        ok(esperar(() -> reglas(o2, "1") == 7 && reglas(o2, "c1") == 11 && salidaCore(o2, "10.200.0.0/16") == 1, 8000),
           "sw1 con servicio aunque el pabellón 2 esté apagado: core con 11 reglas (" + reglas(o2, "c1") + ")");
        o2.conectar("2", 1, 2, 3);
        o2.enlazar("c1", 3, "2", 3);
        ok(esperar(() -> reglas(o2, "2") == 7 && reglas(o2, "c1") == 20, 8000) && coherente(o2) && o2.rechazadas.get() == 0,
           "al encender el pabellón 2: 7 reglas en sw2 y 20 en el core" + nota(o2));
        app2.detener();
    }

    private static String nota(SimOnos o) {
        String x = incoherencia(o);
        return x == null ? "" : " [" + x + "]";
    }
}
