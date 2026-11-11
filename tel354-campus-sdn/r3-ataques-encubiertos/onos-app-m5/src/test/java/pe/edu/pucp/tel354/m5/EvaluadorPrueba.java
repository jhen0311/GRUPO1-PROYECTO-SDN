package pe.edu.pucp.tel354.m5;

import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.Nivel;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;

/**
 * Prueba de la evaluación de M5 sin ONOS (casos E1, E3 y E5 a E9 del LLSD R3, Tabla 25).
 * Ejecutar con r3-ataques-encubiertos/onos-app-m5/probar_evaluador.sh
 */
public final class EvaluadorPrueba {

    private static int fallos = 0;

    private static void verificar(String caso, Object obtenido, Object esperado) {
        boolean ok = esperado.equals(obtenido);
        if (!ok) {
            fallos++;
        }
        System.out.printf("%-58s %-5s %s%n", caso, ok ? "OK" : "FALLA",
                          ok ? obtenido : obtenido + " (esperado " + esperado + ")");
    }

    public static void main(String[] a) {
        Criticidades c = new Criticidades(Criticidad.MAYOR)
                .agregar("10.200.0.0/24", Criticidad.CRITICO).agregar("172.16.0.0/16", Criticidad.CRITICO)
                .agregar("10.200.3.0/24", Criticidad.CRITICO).agregar("10.200.1.10/32", Criticidad.CRITICO)
                .agregar("10.200.1.20/32", Criticidad.MENOR).agregar("10.200.1.30/32", Criticidad.MAYOR)
                .agregar("10.200.1.40/32", Criticidad.MAYOR).agregar("10.200.2.0/24", Criticidad.MENOR);
        verificar("criticidad 10.200.1.30 (BD académica)", c.de("10.200.1.30"), Criticidad.MAYOR);
        verificar("criticidad 10.200.0.13 (Kea)", c.de("10.200.0.13"), Criticidad.CRITICO);
        verificar("criticidad 10.200.2.20 (curso)", c.de("10.200.2.20"), Criticidad.MENOR);
        verificar("criticidad 10.17.0.50 (otro usuario, por defecto)", c.de("10.17.0.50"), Criticidad.MAYOR);

        Evaluador.Contexto limpio = new Evaluador.Contexto();
        verificar("E1 escaneo de puertos a MAYOR", Evaluador.evaluar(TipoAtaque.ESCANEO_PUERTOS,
                Criticidad.MAYOR, true, false, limpio).nivel, Nivel.N1);
        verificar("E1' escaneo de puertos a MENOR", Evaluador.evaluar(TipoAtaque.ESCANEO_PUERTOS,
                Criticidad.MENOR, true, false, limpio).nivel, Nivel.N0);
        verificar("E3 escaneo de red a CRITICO, sin destino único", Evaluador.evaluar(TipoAtaque.ESCANEO_RED,
                Criticidad.CRITICO, false, false, limpio).nivel, Nivel.N2);
        Evaluador.Resultado e5 = Evaluador.evaluar(TipoAtaque.SUPLANTACION, Criticidad.MAYOR, true, false, limpio);
        verificar("E5 suplantación de IP: N2", e5.nivel, Nivel.N2);
        verificar("E5 revisión prioritaria", e5.revisionPrioritaria, true);
        verificar("E5 sin confirmación automática", e5.confirmarAutomatico, false);
        verificar("E6 suplantación del gateway: confirmación automática", Evaluador.evaluar(TipoAtaque.SUPLANTACION,
                Criticidad.CRITICO, true, true, limpio).confirmarAutomatico, true);
        Evaluador.Contexto serv = new Evaluador.Contexto();
        serv.coordinadoEnPuertoServicio = true;
        verificar("E7 coordinado en puerto de servicio (MAYOR): N1", Evaluador.evaluar(TipoAtaque.ATAQUE_COORDINADO,
                Criticidad.MAYOR, true, false, serv).nivel, Nivel.N1);
        verificar("E7' coordinado a CRITICO: N2", Evaluador.evaluar(TipoAtaque.ATAQUE_COORDINADO,
                Criticidad.CRITICO, true, false, limpio).nivel, Nivel.N2);
        verificar("E8 insistencia a MENOR: N0", Evaluador.evaluar(TipoAtaque.ACCESO_REITERADO,
                Criticidad.MENOR, true, false, limpio).nivel, Nivel.N0);
        Evaluador.Contexto reinc = new Evaluador.Contexto();
        reinc.previos24h = 1;
        verificar("E9 reincidencia: escaneo de puertos MAYOR sube a N2", Evaluador.evaluar(TipoAtaque.ESCANEO_PUERTOS,
                Criticidad.MAYOR, true, false, reinc).nivel, Nivel.N2);
        Evaluador.Contexto grave = new Evaluador.Contexto();
        grave.previos24h = 2;
        grave.fuertesPrevios24h = 2;
        verificar("tercer Fuerte en 24 h, sin contención vigente", Evaluador.evaluar(TipoAtaque.SUPLANTACION,
                Criticidad.MAYOR, true, false, grave).confirmarAutomatico, true);
        grave.contencionesVigentes = true;
        verificar("tercer Fuerte en 24 h, con contención vigente", Evaluador.evaluar(TipoAtaque.SUPLANTACION,
                Criticidad.MAYOR, true, false, grave).confirmarAutomatico, false);
        System.out.println(fallos == 0 ? "Todas las pruebas pasaron" : fallos + " pruebas fallaron");
        System.exit(fallos == 0 ? 0 : 1);
    }
}
