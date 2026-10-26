-- =====================================================================
-- TEL354 Grupo 01 · Modelo de datos de R1 y R2 (AVZ07, sección 3.2)
-- Compatible con PostgreSQL 12 (Mininet-VM, Ubuntu 20.04) y 16.
-- =====================================================================
BEGIN;

-- 0 · ubicación ---------------------------------------------------------
CREATE TABLE pabellon (
    id       SMALLINT PRIMARY KEY CHECK (id BETWEEN 1 AND 13),
    nombre   VARCHAR(60) NOT NULL,
    vlan_id  SMALLINT NOT NULL UNIQUE CHECK (vlan_id = 100 + id)
);

CREATE TABLE switch_acceso (
    dpid        VARCHAR(23) PRIMARY KEY,          -- of:0000000000000001
    pabellon_id SMALLINT NOT NULL UNIQUE REFERENCES pabellon(id),
    ip_gestion  INET NOT NULL UNIQUE
);

-- 2 · identidad ---------------------------------------------------------
CREATE TABLE rol (
    id              SMALLSERIAL PRIMARY KEY,
    nombre          VARCHAR(20) NOT NULL UNIQUE,  -- en mayúsculas (viaja en Filter-Id)
    octeto          SMALLINT NOT NULL UNIQUE CHECK (octeto % 16 = 0 AND octeto BETWEEN 16 AND 112),
    session_timeout INTEGER NOT NULL CHECK (session_timeout BETWEEN 60 AND 43200),
    max_sesiones    SMALLINT NOT NULL CHECK (max_sesiones BETWEEN 1 AND 3),
    rama            VARCHAR(20) NOT NULL,         -- ACADEMICA, ADMINISTRATIVA, TI, MINIMA
    rango           SMALLINT NOT NULL
);

CREATE TABLE usuario (
    id          SERIAL PRIMARY KEY,
    codigo      VARCHAR(12) NOT NULL UNIQUE,
    uid_ldap    VARCHAR(64) NOT NULL UNIQUE,
    nombres     VARCHAR(120) NOT NULL,
    rol_base_id SMALLINT NOT NULL REFERENCES rol(id),
    estado      VARCHAR(12) NOT NULL DEFAULT 'ACTIVO'
                CHECK (estado IN ('ACTIVO','INACTIVO','SUSPENDIDO'))
);

-- 1 · dispositivo (inventario de equipos de usuario; lo escribe M1) -------
CREATE TABLE dispositivo (
    id          SERIAL PRIMARY KEY,
    mac         MACADDR NOT NULL UNIQUE,
    hostname    VARCHAR(64),                      -- opción DHCP 12; sin UNIQUE
    sistema_op  VARCHAR(64),                      -- opción DHCP 60
    switch_dpid VARCHAR(23) REFERENCES switch_acceso(dpid),
    puerto      INTEGER,
    estado      VARCHAR(12) NOT NULL DEFAULT 'ACTIVO'
);

-- 3 · cursos ------------------------------------------------------------
CREATE TABLE ciclo (
    id     SMALLSERIAL PRIMARY KEY,
    codigo VARCHAR(10) NOT NULL UNIQUE,
    inicio DATE NOT NULL,
    fin    DATE NOT NULL CHECK (fin > inicio)
);

CREATE TABLE curso (
    id     SERIAL PRIMARY KEY,
    codigo VARCHAR(12) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL
);

CREATE TABLE inscripcion (
    usuario_id INTEGER  NOT NULL REFERENCES usuario(id),
    curso_id   INTEGER  NOT NULL REFERENCES curso(id),
    ciclo_id   SMALLINT NOT NULL REFERENCES ciclo(id),
    tipo       VARCHAR(20) NOT NULL,              -- coincide con rol.nombre (ALUMNO, JP, DOCENTE)
    PRIMARY KEY (usuario_id, curso_id, ciclo_id)
);

-- 5 · autorización ------------------------------------------------------
CREATE TABLE recurso (
    id        SERIAL PRIMARY KEY,
    nombre    VARCHAR(80) NOT NULL,
    ip        INET NOT NULL,
    protocolo VARCHAR(3) NOT NULL CHECK (protocolo IN ('TCP','UDP')),
    puerto    INTEGER NOT NULL CHECK (puerto BETWEEN 1 AND 65535),
    curso_id  INTEGER REFERENCES curso(id),
    UNIQUE (ip, protocolo, puerto)                -- una fila por servicio
);

CREATE TABLE permiso_rol (
    rol_id     SMALLINT NOT NULL REFERENCES rol(id),
    recurso_id INTEGER  NOT NULL REFERENCES recurso(id) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, recurso_id)
);

-- 4 · sesión (la escribe M1) --------------------------------------------
CREATE TABLE sesion (
    id              BIGSERIAL PRIMARY KEY,        -- identificador de la cookie 0x02
    usuario_id      INTEGER NOT NULL REFERENCES usuario(id),
    dispositivo_id  INTEGER NOT NULL REFERENCES dispositivo(id),
    switch_dpid     VARCHAR(23) NOT NULL REFERENCES switch_acceso(dpid),
    puerto          INTEGER NOT NULL,
    rol_efectivo_id SMALLINT NOT NULL REFERENCES rol(id),
    ip              INET,
    vlan_id         SMALLINT,
    estado          VARCHAR(12) NOT NULL DEFAULT 'AUTENTICADA'
                    CHECK (estado IN ('AUTENTICADA','ACTIVA','CERRADA')),
    inicio          TIMESTAMPTZ NOT NULL DEFAULT now(),
    fin             TIMESTAMPTZ,
    motivo_cierre   VARCHAR(16) CHECK (motivo_cierre IN
                    ('DESCONEXION','EXPIRACION','REVOCACION','MOVILIDAD','LIMITE_SESIONES','CUARENTENA'))
);
CREATE INDEX sesion_activa_idx ON sesion (usuario_id) WHERE estado <> 'CERRADA';

-- 6 · incidentes (los escribe M5; en la demo de R1 y R2 solo sostienen la cuarentena)
CREATE TABLE incidente (
    id           BIGSERIAL PRIMARY KEY,           -- identificador de la cookie 0x06
    sesion_id    BIGINT NOT NULL REFERENCES sesion(id),
    requisito    VARCHAR(4)  NOT NULL,
    tipo_ataque  VARCHAR(30) NOT NULL,
    fuente       VARCHAR(4)  NOT NULL,            -- M1, M2, M4a, M8
    severidad    VARCHAR(12) NOT NULL,            -- nivel de ataque
    accion       VARCHAR(2)  NOT NULL,            -- N0..N3
    evidencia    JSONB NOT NULL DEFAULT '{}'::jsonb,
    detectado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    mitigado_en  TIMESTAMPTZ,
    expira_en    TIMESTAMPTZ
);

CREATE TABLE cuarentena (
    id           BIGSERIAL PRIMARY KEY,
    usuario_id   INTEGER NOT NULL REFERENCES usuario(id),
    incidente_id BIGINT  NOT NULL UNIQUE REFERENCES incidente(id),
    inicio       TIMESTAMPTZ NOT NULL DEFAULT now(),
    fin          TIMESTAMPTZ NOT NULL,
    liberada_por VARCHAR(64),
    CHECK (fin > inicio)
);

-- 7 · auditoría (M6: solo INSERT) ----------------------------------------
CREATE TABLE auth_log (
    id             BIGSERIAL PRIMARY KEY,
    ts             TIMESTAMPTZ NOT NULL DEFAULT now(),
    evento         VARCHAR(30) NOT NULL,
    sesion_id      BIGINT  REFERENCES sesion(id),
    usuario_id     INTEGER REFERENCES usuario(id),
    dispositivo_id INTEGER REFERENCES dispositivo(id),
    detalle        JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- ---------------------------------------------------------------------
-- Estado derivado del incidente (AVZ07, sección 3.2)
-- ---------------------------------------------------------------------
CREATE VIEW v_incidente_estado AS
SELECT i.*,
       CASE WHEN EXISTS (SELECT 1 FROM cuarentena c WHERE c.incidente_id = i.id) THEN 'CONFIRMADO'
            WHEN i.mitigado_en IS NOT NULL THEN 'DESCARTADO'
            ELSE 'EN_INVESTIGACION' END AS estado
FROM incidente i;

-- ---------------------------------------------------------------------
-- Permisos efectivos por rol, con herencia (Alumno ⊂ JP ⊂ Docente).
-- M2 convierte cada fila en una regla 100 (AVZ07, sección 7.1).
-- Excluye los recursos de cursos (recurso.curso_id no nulo): a esos solo
-- llegan los inscritos, con reglas por sesión (v_acceso_curso).
-- ---------------------------------------------------------------------
CREATE VIEW v_permiso_efectivo AS
SELECT DISTINCT r.id AS rol_id, r.nombre AS rol, r.octeto,
       rc.id AS recurso_id, rc.nombre AS recurso, rc.ip, rc.protocolo, rc.puerto
FROM rol r
JOIN rol rb ON rb.rama = r.rama AND rb.rango <= r.rango
JOIN permiso_rol p ON p.rol_id = rb.id
JOIN recurso rc ON rc.id = p.recurso_id
WHERE r.rama <> 'MINIMA' AND rc.curso_id IS NULL;

-- ---------------------------------------------------------------------
-- Acceso por curso: cada usuario alcanza solo los servidores de los cursos
-- en los que está inscrito en el ciclo vigente (con cualquier tipo de
-- inscripción: ALUMNO, JP o DOCENTE). M1 instala una regla 110 por fila
-- al activar la sesión (match por la IP de la sesión), con cookie 0x02.
-- ---------------------------------------------------------------------
CREATE VIEW v_acceso_curso AS
SELECT DISTINCT u.id AS usuario_id, u.uid_ldap, cu.codigo AS curso, i.tipo,
       rc.id AS recurso_id, rc.nombre AS recurso, rc.ip, rc.protocolo, rc.puerto
FROM inscripcion i
JOIN usuario u ON u.id = i.usuario_id
JOIN ciclo c   ON c.id = i.ciclo_id AND current_date BETWEEN c.inicio AND c.fin
JOIN curso cu  ON cu.id = i.curso_id
JOIN recurso rc ON rc.curso_id = i.curso_id
WHERE u.estado = 'ACTIVO';

-- ---------------------------------------------------------------------
-- Resolución del rol efectivo (AVZ07, sección 3.3, pasos 1 a 4).
-- Devuelve 'NOMBRE|octeto|session_timeout|max_sesiones' o 'REJECT'.
-- FreeRADIUS la invoca en post-auth del túnel interno.
-- ---------------------------------------------------------------------
-- SECURITY DEFINER: quien la invoca (FreeRADIUS, M2) no necesita leer cuarentena ni inscripcion directamente
CREATE FUNCTION campus_rol_efectivo(p_uid TEXT) RETURNS TEXT
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
    u        usuario%ROWTYPE;
    restante INTEGER;
    r        rol%ROWTYPE;
BEGIN
    SELECT * INTO u FROM usuario WHERE uid_ldap = p_uid;
    IF NOT FOUND OR u.estado <> 'ACTIVO' THEN
        RETURN 'REJECT';                                   -- paso 1: cuenta inexistente o no activa
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

    RETURN r.nombre || '|' || r.octeto || '|' || r.session_timeout || '|' || r.max_sesiones;  -- paso 4
END $$;

-- ---------------------------------------------------------------------
-- Aviso de cambio de política a M2 (LISTEN policy_changed, AVZ07 7.8)
-- ---------------------------------------------------------------------
CREATE FUNCTION notificar_politica() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('policy_changed', TG_TABLE_NAME);
    RETURN NULL;
END $$;
CREATE TRIGGER permiso_rol_cambio AFTER INSERT OR UPDATE OR DELETE ON permiso_rol
    FOR EACH STATEMENT EXECUTE PROCEDURE notificar_politica();
CREATE TRIGGER recurso_cambio AFTER INSERT OR UPDATE OR DELETE ON recurso
    FOR EACH STATEMENT EXECUTE PROCEDURE notificar_politica();
-- un cambio de inscripción altera el acceso por curso de las sesiones abiertas
CREATE TRIGGER inscripcion_cambio AFTER INSERT OR UPDATE OR DELETE ON inscripcion
    FOR EACH STATEMENT EXECUTE PROCEDURE notificar_politica();

COMMIT;
