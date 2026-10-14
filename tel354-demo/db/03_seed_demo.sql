-- =====================================================================
-- Datos de la demo (topología Mininet: 2 pabellones, 4 hosts)
-- Inventario reducido de la Tabla 23 del AVZ07. Tres usuarios con
-- permisos (Alumno, Docente y TI) y cuentas auxiliares para la batería
-- de pruebas de R1 (doble rol, cuenta inactiva y usuario sancionado).
-- =====================================================================
BEGIN;

INSERT INTO rol (nombre, octeto, session_timeout, max_sesiones, rama, rango) VALUES
 ('ALUMNO',          16, 28800, 2, 'ACADEMICA',      1),
 ('JP',              32, 28800, 2, 'ACADEMICA',      2),
 ('DOCENTE',         48, 43200, 3, 'ACADEMICA',      3),
 ('ADMINISTRATIVO',  64, 43200, 2, 'ADMINISTRATIVA', 1),
 ('TI',              80, 43200, 3, 'TI',             1),
 ('INVITADO',        96, 14400, 1, 'MINIMA',         0),
 ('CUARENTENA',     112, 43200, 1, 'MINIMA',         0);

INSERT INTO pabellon (id, nombre, vlan_id) VALUES
 (1, 'Pabellón 1 (as1)', 101),
 (2, 'Pabellón 2 (as2)', 102);

INSERT INTO switch_acceso (dpid, pabellon_id, ip_gestion) VALUES
 ('of:0000000000000001', 1, '172.16.0.22'),
 ('of:0000000000000002', 2, '172.16.0.23');

INSERT INTO ciclo (codigo, inicio, fin) VALUES ('2026-2', '2026-08-17', '2026-12-19');

INSERT INTO curso (codigo, nombre) VALUES
 ('TEL354', 'Redes Definidas por Software'),
 ('TEL141', 'Computación en la Nube');

-- Usuarios: uid_ldap = identidad interna del túnel EAP-TTLS
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado) VALUES
 ('20260001', 'alumno.demo',  'Alumno de prueba',          (SELECT id FROM rol WHERE nombre='ALUMNO'),  'ACTIVO'),
 ('D0001',    'docente.demo', 'Docente de prueba',         (SELECT id FROM rol WHERE nombre='DOCENTE'), 'ACTIVO'),
 ('T0001',    'ti.demo',      'Analista de TI de prueba',  (SELECT id FROM rol WHERE nombre='TI'),      'ACTIVO'),
 -- cuentas auxiliares para la batería de R1
 ('20260002', 'jp.demo',      'Alumno con inscripción JP', (SELECT id FROM rol WHERE nombre='ALUMNO'),  'ACTIVO'),
 ('20260003', 'inactivo.demo','Cuenta inactiva',           (SELECT id FROM rol WHERE nombre='ALUMNO'),  'INACTIVO'),
 ('20260004', 'sancion.demo', 'Usuario sancionado',        (SELECT id FROM rol WHERE nombre='ALUMNO'),  'ACTIVO');

-- alumno.demo y docente.demo llevan TEL354; nadie de la demo lleva TEL141:
-- su servidor debe quedar bloqueado para todos (acceso por curso)
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES
 ((SELECT id FROM usuario WHERE uid_ldap='alumno.demo'),  (SELECT id FROM curso WHERE codigo='TEL354'), 1, 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap='docente.demo'), (SELECT id FROM curso WHERE codigo='TEL354'), 1, 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap='jp.demo'),      (SELECT id FROM curso WHERE codigo='TEL354'), 1, 'JP');

-- Recursos: una fila por servicio (IP, protocolo, puerto)
INSERT INTO recurso (nombre, ip, protocolo, puerto, curso_id) VALUES
 ('Campus Virtual (HTTPS)',      '10.200.1.10', 'TCP',  443, NULL),
 ('Campus Virtual (SSH)',        '10.200.1.10', 'TCP',   22, NULL),
 ('Biblioteca (HTTPS)',          '10.200.1.20', 'TCP',  443, NULL),
 ('Lab SDN TEL354 (ONOS GUI)',   '10.200.2.10', 'TCP', 8181, (SELECT id FROM curso WHERE codigo='TEL354')),
 ('Lab SDN TEL354 (SSH)',        '10.200.2.10', 'TCP',   22, NULL),
 ('Nube TEL141 (Horizon)',       '10.200.2.20', 'TCP',  443, (SELECT id FROM curso WHERE codigo='TEL141')),
 ('BD académica',                '10.200.3.10', 'TCP', 5432, NULL),
 ('BD administrativa',           '10.200.3.20', 'TCP', 5432, NULL),
 ('Gestión VM de servicios',     '10.200.0.5',  'TCP',   22, NULL);

-- Permisos directos por rol; la herencia la resuelve v_permiso_efectivo.
-- Los servidores de cursos (curso_id no nulo) no van aquí: los habilita la inscripción.
INSERT INTO permiso_rol (rol_id, recurso_id)
SELECT r.id, rc.id FROM rol r, recurso rc WHERE (r.nombre, rc.nombre) IN (
 ('ALUMNO',         'Campus Virtual (HTTPS)'),
 ('ALUMNO',         'Biblioteca (HTTPS)'),
 ('DOCENTE',        'BD académica'),
 ('ADMINISTRATIVO', 'Campus Virtual (HTTPS)'),
 ('ADMINISTRATIVO', 'BD administrativa'),
 ('TI',             'Campus Virtual (HTTPS)'),
 ('TI',             'Campus Virtual (SSH)'),
 ('TI',             'Lab SDN TEL354 (SSH)'),
 ('TI',             'Gestión VM de servicios'));

-- Sanción vigente para sancion.demo: la cuarentena exige un incidente,
-- y el incidente una sesión previa (cerrada) y su dispositivo.
INSERT INTO dispositivo (mac, hostname, switch_dpid, puerto)
VALUES ('02:00:00:00:99:01', 'equipo-previo', 'of:0000000000000002', 3);
INSERT INTO sesion (usuario_id, dispositivo_id, switch_dpid, puerto, rol_efectivo_id, ip, vlan_id,
                    estado, inicio, fin, motivo_cierre)
VALUES ((SELECT id FROM usuario WHERE uid_ldap='sancion.demo'), 1, 'of:0000000000000002', 3,
        (SELECT id FROM rol WHERE nombre='ALUMNO'), '10.18.0.50', 102,
        'CERRADA', now() - interval '2 hours', now() - interval '1 hour', 'CUARENTENA');
INSERT INTO incidente (sesion_id, requisito, tipo_ataque, fuente, severidad, accion, evidencia)
VALUES (1, 'R3', 'ESCANEO', 'M4a', 'Fuerte', 'N3', '{"nota": "dato de prueba de la demo"}');
INSERT INTO cuarentena (usuario_id, incidente_id, inicio, fin)
VALUES ((SELECT id FROM usuario WHERE uid_ldap='sancion.demo'), 1, now() - interval '1 hour', now() + interval '7 days');

COMMIT;
