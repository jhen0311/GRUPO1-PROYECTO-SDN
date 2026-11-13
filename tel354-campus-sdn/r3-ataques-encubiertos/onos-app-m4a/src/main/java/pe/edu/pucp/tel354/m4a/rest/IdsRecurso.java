package pe.edu.pucp.tel354.m4a.rest;

import com.fasterxml.jackson.databind.JsonNode;
import org.onosproject.rest.AbstractWebResource;
import pe.edu.pucp.tel354.m4a.M4aManager;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;

/**
 * Alertas de Suricata hacia M4a (LLSD R3, Tabla 9: IdsAlert). Las envía el adaptador del IDS
 * (r3-ataques-encubiertos/ids/ids_adapter.py), una por llamada, con la autenticación de la API de ONOS:
 *
 * <pre>
 * POST /onos/campus-ids/v1/alertas
 *   {"src_ip", "dst_ip", "dst_port", "proto", "sid", "categoria", "firma", "severidad", "sensor"}
 * </pre>
 *
 * Responde 200 si la alerta se procesó o se descartó a propósito (el adaptador no debe reintentar) y
 * 400 si está mal formada. Vive en M4a para no tocar tel354-api ni la REST de M5.
 */
@Path("v1")
public class IdsRecurso extends AbstractWebResource {

    @POST
    @Path("alertas")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response alerta(InputStream in) {
        JsonNode b;
        try {
            b = in != null ? mapper().readTree(in) : mapper().createObjectNode();
        } catch (Exception e) {
            return error(Response.Status.BAD_REQUEST, "JSON ilegible");
        }
        String p = b.path("proto").asText("tcp");
        int proto = "udp".equalsIgnoreCase(p) ? 17 : "icmp".equalsIgnoreCase(p) ? 1 : 6;
        M4aManager.ResultadoIds r = get(M4aManager.class).alertaIds(b.path("src_ip").asText(""),
                b.path("dst_ip").asText(""), proto, b.path("dst_port").asInt(0), b.path("sid").asInt(0),
                b.path("categoria").asText(""), b.path("firma").asText(""), b.path("severidad").asInt(3),
                b.path("sensor").asText(""));
        if (r == M4aManager.ResultadoIds.INVALIDA) {
            return error(Response.Status.BAD_REQUEST, "Alerta del IDS inválida");
        }
        return ok(mapper().createObjectNode().put("resultado", r.name())).build();
    }

    private static Response error(Response.Status st, String msg) {
        return Response.status(st).entity("{\"error\": \"" + msg.replace("\"", "'") + "\"}").build();
    }
}
