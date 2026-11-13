package pe.edu.pucp.tel354.m5.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.net.DeviceId;
import org.onosproject.rest.AbstractWebResource;
import pe.edu.pucp.tel354.api.r3.Filtro;
import pe.edu.pucp.tel354.api.r3.IdsAlertService;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.api.r3.MitigationReserveService;
import pe.edu.pucp.tel354.api.r3.Modo;
import pe.edu.pucp.tel354.api.r3.Requisito;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionService;
import pe.edu.pucp.tel354.m5.Formato;
import pe.edu.pucp.tel354.m5.M5Manager;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Interfaz formal de TI (LLSD R3, sección 5.8): la usan scripts, un SIEM o un tablero, con la
 * autenticación de la API de ONOS (onos/rocks en el piloto). Cualquier instancia del clúster atiende.
 *
 * <pre>
 * GET  /onos/campus/v1/incidentes?estado=EN_INVESTIGACION&amp;usuario=alumno.demo
 * GET  /onos/campus/v1/incidentes/{id}
 * POST /onos/campus/v1/incidentes/{id}/confirmar   {"horas": 24, "motivo": "…"}
 * POST /onos/campus/v1/incidentes/{id}/descartar   {"motivo": "…"}
 * GET  /onos/campus/v1/r3/modo     PUT {"modo": "OBSERVACION"}
 * GET  /onos/campus/v1/r3/reserva
 * GET  /onos/campus/v1/sesiones?usuarioId=3      GET /onos/campus/v1/sesiones/{id}
 * </pre>
 */
@Path("v1")
public class CampusRecurso extends AbstractWebResource {

    @Context
    private SecurityContext seguridad;

    private String solicitante() {
        return "rest:" + (seguridad != null && seguridad.getUserPrincipal() != null
                ? seguridad.getUserPrincipal().getName() : "anónimo");
    }

    private JsonNode cuerpo(InputStream in) {
        try {
            return in != null ? mapper().readTree(in) : mapper().createObjectNode();
        } catch (Exception e) {
            return mapper().createObjectNode();
        }
    }

    private static Response error(Response.Status st, String msg) {
        return Response.status(st).entity("{\"error\": \"" + msg.replace("\"", "'") + "\"}").build();
    }

    /**
     * Alertas de Suricata (LLSD R3, IdsAlert). El adaptador de la VM-IDS envía una por llamada:
     * {"src_ip", "dst_ip", "dst_port", "proto", "sid", "categoria", "firma", "severidad", "sensor"}.
     * Responde 200 si la alerta se procesó o se descartó a propósito (el adaptador no debe reintentar)
     * y 400 si está mal formada.
     */
    @POST
    @Path("ids/alertas")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response alertaIds(InputStream in) {
        JsonNode b = cuerpo(in);
        String p = b.path("proto").asText("tcp");
        int proto = "udp".equalsIgnoreCase(p) ? 17 : "icmp".equalsIgnoreCase(p) ? 1 : 6;
        IdsAlertService.Resultado r;
        try {
            r = get(IdsAlertService.class).alerta(b.path("src_ip").asText(""), b.path("dst_ip").asText(""),
                    proto, b.path("dst_port").asInt(0), b.path("sid").asInt(0),
                    b.path("categoria").asText(""), b.path("firma").asText(""),
                    b.path("severidad").asInt(3), b.path("sensor").asText(""));
        } catch (RuntimeException e) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "M4a no está activa");
        }
        if (r == IdsAlertService.Resultado.INVALIDA) {
            return error(Response.Status.BAD_REQUEST, "Alerta del IDS inválida");
        }
        return ok(mapper().createObjectNode().put("resultado", r.name())).build();
    }

    @GET
    @Path("incidentes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response incidentes(@QueryParam("estado") String estado, @QueryParam("usuario") String usuario,
                               @DefaultValue("100") @QueryParam("limite") int limite) {
        boolean abiertos = "EN_INVESTIGACION".equalsIgnoreCase(estado);
        List<Incidente> l = get(IncidentAdminService.class).listar(new Filtro(abiertos, null, usuario, limite));
        ObjectNode r = mapper().createObjectNode();
        ArrayNode a = r.putArray("incidentes");
        for (Incidente i : l) {
            if (estado == null || abiertos || estado.equalsIgnoreCase(String.valueOf(i.estado()))) {
                a.add(Formato.incidente(mapper(), i, false));
            }
        }
        return ok(r).build();
    }

    @GET
    @Path("incidentes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response incidente(@PathParam("id") long id) {
        Incidente i = get(IncidentAdminService.class).detalle(id);
        return i == null ? error(Response.Status.NOT_FOUND, "No existe el incidente " + id)
                : ok(Formato.incidente(mapper(), i, true)).build();
    }

    @POST
    @Path("incidentes/{id}/confirmar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirmar(@PathParam("id") long id, InputStream in) {
        JsonNode c = cuerpo(in);
        String motivo = c.path("motivo").asText("");
        if (motivo.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "El motivo es obligatorio");
        }
        Duration d = c.has("minutos") ? Duration.ofMinutes(c.get("minutos").asInt())
                : Duration.ofHours(c.path("horas").asInt(24));
        try {
            Incidente i = get(IncidentAdminService.class).confirmar(id, d, solicitante(), motivo, "rest");
            return ok(Formato.incidente(mapper(), i, true)).build();
        } catch (IllegalArgumentException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return error(Response.Status.CONFLICT, e.getMessage());
        }
    }

    @POST
    @Path("incidentes/{id}/descartar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response descartar(@PathParam("id") long id, InputStream in) {
        String motivo = cuerpo(in).path("motivo").asText("");
        if (motivo.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "El motivo es obligatorio");
        }
        try {
            Incidente i = get(IncidentAdminService.class).descartar(id, solicitante(), motivo, "rest");
            return ok(Formato.incidente(mapper(), i, true)).build();
        } catch (IllegalArgumentException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return error(Response.Status.CONFLICT, e.getMessage());
        }
    }

    @GET
    @Path("r3/modo")
    @Produces(MediaType.APPLICATION_JSON)
    public Response modo() {
        return ok(mapper().createObjectNode().put("modo", get(IncidentAdminService.class).modo().name())).build();
    }

    @PUT
    @Path("r3/modo")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cambiarModo(InputStream in) {
        String m = cuerpo(in).path("modo").asText("");
        try {
            get(IncidentAdminService.class).modo(Modo.valueOf(m.toUpperCase()), solicitante(), "rest");
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, "modo debe ser ACTIVO u OBSERVACION");
        }
        return modo();
    }

    @GET
    @Path("r3/reserva")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reserva() {
        MitigationReserveService r = get(MitigationReserveService.class);
        ObjectNode o = mapper().createObjectNode();
        ArrayNode a = o.putArray("switches");
        for (DeviceId d : ((M5Manager) r).switchesConReserva()) {
            Map<Requisito, Integer> u = r.usadas(d);
            a.addObject().put("switch", d.toString()).put("capacidad", r.capacidad(d))
                    .put("r3", u.get(Requisito.R3)).put("r4", u.get(Requisito.R4)).put("libres", r.libres(d));
        }
        return ok(o).build();
    }

    @GET
    @Path("sesiones")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sesiones(@QueryParam("usuarioId") int usuarioId) {
        ObjectNode o = mapper().createObjectNode();
        ArrayNode a = o.putArray("sesiones");
        for (SesionInfo s : get(SessionService.class).getSessionsByUser(usuarioId)) {
            a.add(Formato.sesion(mapper(), s));
        }
        return ok(o).build();
    }

    @GET
    @Path("sesiones/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sesion(@PathParam("id") long id) {
        SesionInfo s = get(SessionService.class).getSession(id);
        return s == null ? error(Response.Status.NOT_FOUND, "No existe la sesión " + id)
                : ok(Formato.sesion(mapper(), s)).build();
    }
}
