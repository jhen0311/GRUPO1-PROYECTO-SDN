package pe.edu.pucp.tel354.m5;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;

import java.time.Instant;

/** Representación JSON de incidentes y sesiones para la API REST de TI. */
public final class Formato {

    private Formato() {
    }

    private static String ts(long ms) {
        return ms > 0 ? Instant.ofEpochMilli(ms).toString() : null;
    }

    public static ObjectNode incidente(ObjectMapper m, Incidente i, boolean conEvidencia) {
        ObjectNode o = m.createObjectNode();
        o.put("id", i.id());
        o.put("sesion", i.sesionId());
        o.put("usuario", i.usuario());
        o.put("tipo", String.valueOf(i.tipo()));
        o.put("nivelAtaque", String.valueOf(i.nivelAtaque()));
        o.put("criticidad", String.valueOf(i.criticidad()));
        o.put("nivel", String.valueOf(i.nivel()));
        o.put("nivelCalculado", String.valueOf(i.nivelCalculado()));
        o.put("estado", String.valueOf(i.estado()));
        o.put("revisionPrioritaria", i.revisionPrioritaria());
        o.put("detectadoEn", ts(i.detectadoMs()));
        o.put("expiraEn", ts(i.expiraMs()));
        o.put("mitigadoEn", ts(i.mitigadoMs()));
        if (conEvidencia) {
            try {
                o.set("evidencia", m.readTree(i.evidencia()));
            } catch (Exception e) {
                o.put("evidencia", i.evidencia());
            }
            ArrayNode a = o.putArray("sesionesContenidas");
            i.sesionesContenidas().forEach(a::add);
        }
        return o;
    }

    public static ObjectNode sesion(ObjectMapper m, SesionInfo s) {
        ObjectNode o = m.createObjectNode();
        o.put("id", s.id());
        o.put("usuario", s.usuario());
        o.put("rol", s.rol());
        o.put("mac", String.valueOf(s.mac()));
        o.put("switch", String.valueOf(s.dispositivo()));
        o.put("puerto", String.valueOf(s.puerto()));
        o.put("ip", s.ip() != null ? s.ip().toString() : null);
        o.put("vlan", s.vlan());
        o.put("estado", String.valueOf(s.estado()));
        o.put("inicio", ts(s.inicioMs()));
        return o;
    }
}
