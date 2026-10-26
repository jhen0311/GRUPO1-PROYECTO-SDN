-- =====================================================================
-- Credencial temporal de invitado (R1): vigencia por usuario.
-- Idempotente: se puede aplicar sobre una base ya creada.
--   su postgres -c "psql -d campus -f comun/db/04_invitado.sql"
-- La cuenta vence en usuario.vence_en: desde ese instante FreeRADIUS la
-- rechaza, y mientras está vigente el Session-Timeout no pasa del vencimiento.
-- =====================================================================
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS vence_en TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION campus_rol_efectivo(p_uid TEXT) RETURNS TEXT
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
    u        usuario%ROWTYPE;
    restante INTEGER;
    r        rol%ROWTYPE;
    t        INTEGER;
BEGIN
    SELECT * INTO u FROM usuario WHERE uid_ldap = p_uid;
    IF NOT FOUND OR u.estado <> 'ACTIVO' THEN
        RETURN 'REJECT';                                   -- paso 1: cuenta inexistente o no activa
    END IF;
    IF u.vence_en IS NOT NULL AND u.vence_en <= now() THEN
        RETURN 'REJECT';                                   -- credencial temporal vencida
    END IF;

    SELECT LEAST(EXTRACT(EPOCH FROM (q.fin - now())), 43200)::int INTO restante
    FROM cuarentena q
    WHERE q.usuario_id = u.id AND now() BETWEEN q.inicio AND q.fin
    ORDER BY q.fin DESC LIMIT 1;
    IF FOUND THEN                                          -- paso 1: sanción vigente
        SELECT * INTO r FROM rol WHERE nombre = 'CUARENTENA';
        RETURN r.nombre || '|' || r.octeto || '|' || GREATEST(restante, 60) || '|' || r.max_sesiones;
    END IF;

    SELECT x.* INTO r                                      -- pasos 2 y 3: rol base o inscripción de la misma rama
    FROM rol b
    JOIN rol x ON x.id = b.id
               OR (x.rama = b.rama AND x.nombre IN (
                     SELECT i.tipo FROM inscripcion i
                     JOIN ciclo c ON c.id = i.ciclo_id
                     WHERE i.usuario_id = u.id
                       AND current_date BETWEEN c.inicio AND c.fin))
    WHERE b.id = u.rol_base_id
    ORDER BY x.rango DESC
    LIMIT 1;

    t := r.session_timeout;
    IF u.vence_en IS NOT NULL THEN                         -- la sesión no sobrevive a la credencial
        t := LEAST(t, GREATEST(EXTRACT(EPOCH FROM (u.vence_en - now()))::int, 60));
    END IF;
    RETURN r.nombre || '|' || r.octeto || '|' || t || '|' || r.max_sesiones;  -- paso 4
END $$;
