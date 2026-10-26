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
                                 ('onos_m6','m6-demo'))          -- M6: auditoría (solo INSERT)
                AS t(nombre, clave) LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.nombre) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r.nombre, r.clave);
    ELSE
      EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', r.nombre, r.clave);
    END IF;
  END LOOP;
END $$;

GRANT USAGE ON SCHEMA public TO radius_ro, onos_m1, onos_m2, onos_m6;

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
