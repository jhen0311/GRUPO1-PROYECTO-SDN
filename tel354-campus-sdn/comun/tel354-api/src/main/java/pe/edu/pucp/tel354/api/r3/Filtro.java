package pe.edu.pucp.tel354.api.r3;

/** Filtro de IncidentAdminService.listar. */
public final class Filtro {

    private final boolean soloAbiertos;
    private final Integer usuarioId;
    private final String usuario;
    private final int limite;

    public Filtro(boolean soloAbiertos, Integer usuarioId, String usuario, int limite) {
        this.soloAbiertos = soloAbiertos;
        this.usuarioId = usuarioId;
        this.usuario = usuario;
        this.limite = limite > 0 ? limite : 100;
    }

    public static Filtro todos() {
        return new Filtro(false, null, null, 100);
    }

    public boolean soloAbiertos() {
        return soloAbiertos;
    }

    public Integer usuarioId() {
        return usuarioId;
    }

    /** uid_ldap, o null. */
    public String usuario() {
        return usuario;
    }

    public int limite() {
        return limite;
    }
}
