-- =====================================================================
-- 06_m6.sql · Referencias de auth_log sin ampliar los permisos de M6
--
-- M6 corre fuera de ONOS (servicio Python que lee el log de las instancias): sus eventos traen el
-- sesion_id y el uid, pero no el usuario.id ni el dispositivo.id. Según lo acordado, onos_m6 solo
-- tiene INSERT en auth_log (02_roles.sql), así que no puede consultarlos. Este disparador los completa
-- dentro de la base, con los permisos de su dueño (SECURITY DEFINER), antes de cada INSERT:
--   usuario_id     ← sesion.usuario_id, o usuario.id del uid en detalle->>'usuario'
--   dispositivo_id ← sesion.dispositivo_id
-- Solo rellena lo que llega en NULL: si quien inserta ya trae el dato, se respeta.
--
-- Uso: su postgres -c "psql -d campus" < comun/db/06_m6.sql   (setup_db.sh lo carga si existe)
-- =====================================================================
CREATE OR REPLACE FUNCTION auth_log_referencias() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF NEW.sesion_id IS NOT NULL AND (NEW.usuario_id IS NULL OR NEW.dispositivo_id IS NULL) THEN
        SELECT COALESCE(NEW.usuario_id, s.usuario_id), COALESCE(NEW.dispositivo_id, s.dispositivo_id)
          INTO NEW.usuario_id, NEW.dispositivo_id
          FROM sesion s WHERE s.id = NEW.sesion_id;
    END IF;
    IF NEW.usuario_id IS NULL AND NEW.detalle ? 'usuario' THEN
        SELECT u.id INTO NEW.usuario_id FROM usuario u WHERE u.uid_ldap = NEW.detalle->>'usuario';
    END IF;
    RETURN NEW;
END $$;

REVOKE ALL ON FUNCTION auth_log_referencias() FROM PUBLIC;

DROP TRIGGER IF EXISTS auth_log_referencias ON auth_log;
CREATE TRIGGER auth_log_referencias BEFORE INSERT ON auth_log
    FOR EACH ROW EXECUTE FUNCTION auth_log_referencias();

-- La versión anterior de este archivo daba SELECT a onos_m6; se retira para dejarlo en solo INSERT.
REVOKE SELECT ON sesion, usuario FROM onos_m6;
