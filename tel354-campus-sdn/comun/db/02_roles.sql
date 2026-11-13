-- =====================================================================
-- Roles de PostgreSQL por componente (mínimo privilegio, AVZ07 3.2)
-- Contraseñas de demo: cambiarlas en un despliegue real.
-- =====================================================================
-- Los roles son globales del clúster: se crean solo si no existen (script idempotente).
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN SELECT * FROM (VALUES ('radius_ro','radius-demo'),   -- FreeRADIUS (rlm_sql)
                                 ('onos_m1','m1-demo'),          -- M1: sesiones y dispositivos
                                 ('onos_m2','m2-demo'),          -- M2: matriz de autorización
                                 ('onos_m5','m5-demo'),          -- M5: incidentes y cuarentena (R3)
                                 ('onos_m7','m7-demo'),          -- M7+M8 (tel354.base): rol de cada switch
                                 ('onos_m6','m6-demo'))          -- M6: auditoría (solo INSERT)
                AS t(nombre, clave) LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.nombre) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r.nombre, r.clave);
    ELSE
      EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', r.nombre, r.clave);
    END IF;
  END LOOP;
END $$;

GRANT USAGE ON SCHEMA public TO radius_ro, onos_m1, onos_m2, onos_m5, onos_m6, onos_m7;

-- FreeRADIUS: solo lectura de identidad, cursos y sanciones
GRANT SELECT ON usuario, rol, inscripcion, ciclo, cuarentena TO radius_ro;
GRANT EXECUTE ON FUNCTION campus_rol_efectivo(TEXT) TO radius_ro;

-- M1: dueño de sesiones y dispositivos
GRANT SELECT ON usuario, rol, pabellon, switch_acceso, v_acceso_curso TO onos_m1;
GRANT SELECT, INSERT, UPDATE ON sesion, dispositivo TO onos_m1;
GRANT USAGE ON SEQUENCE sesion_id_seq, dispositivo_id_seq TO onos_m1;

-- M2: lectura de la matriz y de las sesiones (slow-path)
GRANT SELECT ON rol, recurso, permiso_rol, v_permiso_efectivo, v_acceso_curso, sesion, usuario TO onos_m2;
GRANT EXECUTE ON FUNCTION campus_rol_efectivo(TEXT) TO onos_m2;

-- M6: solo INSERT en auth_log (el registro no puede alterarse)
GRANT INSERT ON auth_log TO onos_m6;
GRANT USAGE ON SEQUENCE auth_log_id_seq TO onos_m6;

-- M5 (LLSD R3, sección 6.2): lee sesiones, usuarios y el inventario; escribe incidente y cuarentena
GRANT SELECT ON sesion, usuario, rol, recurso TO onos_m5;
GRANT SELECT, INSERT, UPDATE ON incidente, cuarentena TO onos_m5;
GRANT USAGE ON SEQUENCE incidente_id_seq, cuarentena_id_seq TO onos_m5;

-- M7+M8 (tel354.base): solo el inventario de switches de acceso (dpid -> pabellón)
GRANT SELECT ON switch_acceso TO onos_m7;
