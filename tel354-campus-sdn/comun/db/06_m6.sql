-- =====================================================================
-- 06_m6.sql · Permiso de lectura mínimo para M6 (auditoría fuera de ONOS)
--
-- M6 corre en Python fuera de ONOS: lee los eventos del log y no recibe el usuario.id ni el
-- dispositivo.id que traerían los eventos OSGi. Con estas columnas (solo lectura) completa
-- usuario_id y dispositivo_id de auth_log en el mismo INSERT, a partir de sesion_id o del uid.
-- Sin este archivo M6 funciona igual: escribe sesion_id y deja el uid en detalle.
--
-- Uso: su postgres -c "psql -d campus" < comun/db/06_m6.sql   (setup_db.sh lo carga si existe)
-- =====================================================================
GRANT SELECT (id, usuario_id, dispositivo_id) ON sesion TO onos_m6;
GRANT SELECT (id, uid_ldap) ON usuario TO onos_m6;
