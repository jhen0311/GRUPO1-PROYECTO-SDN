package pe.edu.pucp.tel354.m5;

import org.onosproject.net.DeviceId;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Versioned;
import pe.edu.pucp.tel354.api.r3.MitigationReserveService;
import pe.edu.pucp.tel354.api.r3.Requisito;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Reserva de mitigación por switch (LLSD R3, sección 4.4): Σ e ≤ M por switch, compartida por R3
 * y R4. El contador vive en un ConsistentMap del clúster ("dpid" -> "usadasR3,usadasR4"), y
 * compute() hace atómica la comprobación y la reserva aunque dos instancias reserven a la vez.
 */
public class Reserva implements MitigationReserveService {

    private final ConsistentMap<String, String> mapa;
    private final IntSupplier capacidad;

    public Reserva(ConsistentMap<String, String> mapa, IntSupplier capacidad) {
        this.mapa = mapa;
        this.capacidad = capacidad;
    }

    private static int[] leer(String v) {
        if (v == null) {
            return new int[2];
        }
        String[] p = v.split(",");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    private static String escribir(int[] u) {
        return u[0] + "," + u[1];
    }

    @Override
    public boolean reservar(DeviceId d, int entradas, Requisito r) {
        if (entradas <= 0) {
            return true;
        }
        AtomicBoolean ok = new AtomicBoolean();
        int m = capacidad.getAsInt();
        mapa.compute(d.toString(), (k, v) -> {
            int[] u = leer(v);
            if (u[0] + u[1] + entradas > m) {
                ok.set(false);
                return escribir(u);
            }
            ok.set(true);
            u[r.ordinal()] += entradas;
            return escribir(u);
        });
        return ok.get();
    }

    @Override
    public void liberar(DeviceId d, int entradas, Requisito r) {
        if (entradas <= 0) {
            return;
        }
        mapa.compute(d.toString(), (k, v) -> {
            int[] u = leer(v);
            u[r.ordinal()] = Math.max(0, u[r.ordinal()] - entradas);
            return escribir(u);
        });
    }

    @Override
    public int libres(DeviceId d) {
        int[] u = leer(Versioned.valueOrNull(mapa.get(d.toString())));
        return Math.max(0, capacidad.getAsInt() - u[0] - u[1]);
    }

    @Override
    public int capacidad(DeviceId d) {
        return capacidad.getAsInt();
    }

    @Override
    public Map<Requisito, Integer> usadas(DeviceId d) {
        int[] u = leer(Versioned.valueOrNull(mapa.get(d.toString())));
        Map<Requisito, Integer> m = new EnumMap<>(Requisito.class);
        m.put(Requisito.R3, u[0]);
        m.put(Requisito.R4, u[1]);
        return m;
    }

    /** Fija el uso de R3 de un switch (reconstrucción desde las reglas presentes). */
    public void fijarR3(DeviceId d, int usadas) {
        mapa.compute(d.toString(), (k, v) -> {
            int[] u = leer(v);
            u[0] = usadas;
            return escribir(u);
        });
    }

    public boolean vacia() {
        return mapa.isEmpty();
    }

    public Iterable<String> switches() {
        return mapa.keySet();
    }
}
