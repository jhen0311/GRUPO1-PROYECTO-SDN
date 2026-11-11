package pe.edu.pucp.tel354.m5;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowRule;
import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.Destino;
import pe.edu.pucp.tel354.api.r3.EstadoIncidente;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.api.r3.Nivel;
import pe.edu.pucp.tel354.api.r3.NivelAtaque;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Incidente abierto en la memoria de la instancia que lo atiende (la master del switch de la sesión
 * de origen). Guarda las reglas instaladas por sesión para retirarlas, renovarlas y liberar la
 * reserva. Una «réplica» es un incidente cuya contención esta instancia aplicó a una sesión nueva
 * del usuario en otro switch: solo libera su reserva al vencer.
 */
public class IncidenteVivo {

    public long id;
    public int usuarioId;
    public String usuario;
    public SesionInfo origen;
    public TipoAtaque tipo;
    public NivelAtaque nivelAtaque;
    public Criticidad criticidad;
    /** Nivel aplicado (incidente.accion). */
    public Nivel nivel = Nivel.N0;
    public Nivel nivelCalculado = Nivel.N0;
    public boolean revisionPrioritaria;
    public final List<Destino> destinos = new ArrayList<>();
    public ObjectNode evidencia;
    public long detectadoMs = System.currentTimeMillis();
    /** Vencimiento de la contención (o de la ventana de agregación en N0). */
    public long expiraMs;
    public int renovaciones;
    public boolean replica;
    /** Reglas por sesión (las de N1 o N2 de este incidente). */
    public final Map<Long, List<FlowRule>> reglas = new LinkedHashMap<>();
    public final Map<Long, DeviceId> switchDeSesion = new LinkedHashMap<>();

    public int entradasEn(DeviceId d) {
        int n = 0;
        for (Map.Entry<Long, List<FlowRule>> e : reglas.entrySet()) {
            if (d.equals(switchDeSesion.get(e.getKey()))) {
                n += e.getValue().size();
            }
        }
        return n;
    }

    public boolean destinoUnico() {
        return destinos.size() == 1;
    }

    public Incidente vista() {
        List<Long> ses = new ArrayList<>(reglas.keySet());
        return Incidente.builder().id(id).sesionId(origen != null ? origen.id() : -1).usuarioId(usuarioId)
                .usuario(usuario).tipo(tipo).nivelAtaque(nivelAtaque).criticidad(criticidad).nivel(nivel)
                .nivelCalculado(nivelCalculado).revisionPrioritaria(revisionPrioritaria)
                .estado(EstadoIncidente.EN_INVESTIGACION).detectadoMs(detectadoMs).expiraMs(expiraMs)
                .sesiones(ses).evidencia(evidencia != null ? evidencia.toString() : "{}").build();
    }

    @Override
    public String toString() {
        return "incidente " + id + " [" + usuario + " " + tipo + " " + nivelAtaque + "/" + criticidad + " " + nivel
                + (replica ? " réplica" : "") + ", " + reglas.size() + " sesiones]";
    }
}
