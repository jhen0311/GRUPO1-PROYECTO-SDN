-- Generado por tools/yaml2sql.py a partir de /mnt/user-data/uploads/database.yaml
BEGIN;
-- aviso: MAC no válida para Minerva McGonagall (00:44:11:22:44:A7:2A); el dispositivo lo registrará M1
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id) VALUES ('LAB001', 'minerva.mcgonagall', 'Minerva McGonagall', (SELECT id FROM rol WHERE nombre='ALUMNO')) ON CONFLICT (uid_ldap) DO NOTHING;
-- aviso: MAC no válida para Severus Snape (00:44:11:2F:33:D8:3C); el dispositivo lo registrará M1
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id) VALUES ('LAB002', 'severus.snape', 'Severus Snape', (SELECT id FROM rol WHERE nombre='ALUMNO')) ON CONFLICT (uid_ldap) DO NOTHING;
-- aviso: MAC no válida para Albus Dumbledore (00:44:11:3F:22:C3:77); el dispositivo lo registrará M1
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id) VALUES ('LAB003', 'albus.dumbledore', 'Albus Dumbledore', (SELECT id FROM rol WHERE nombre='ALUMNO')) ON CONFLICT (uid_ldap) DO NOTHING;
INSERT INTO recurso (nombre, ip, protocolo, puerto) VALUES ('Server 3 (ssh)', '10.200.2.3', 'TCP', 22) ON CONFLICT (ip, protocolo, puerto) DO NOTHING;
INSERT INTO recurso (nombre, ip, protocolo, puerto) VALUES ('Server 3 (web)', '10.200.2.3', 'TCP', 80) ON CONFLICT (ip, protocolo, puerto) DO NOTHING;
INSERT INTO recurso (nombre, ip, protocolo, puerto) VALUES ('Server 3 (dev)', '10.200.2.3', 'TCP', 3000) ON CONFLICT (ip, protocolo, puerto) DO NOTHING;
INSERT INTO recurso (nombre, ip, protocolo, puerto) VALUES ('Server 4 (ssh)', '10.200.2.4', 'TCP', 22) ON CONFLICT (ip, protocolo, puerto) DO NOTHING;
INSERT INTO curso (codigo, nombre) VALUES ('HGW101', 'Potions') ON CONFLICT (codigo) DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) SELECT u.id, cu.id, ci.id, 'ALUMNO' FROM usuario u, curso cu, ciclo ci WHERE u.uid_ldap='severus.snape' AND cu.codigo='HGW101' AND ci.codigo='2026-2' ON CONFLICT DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) SELECT u.id, cu.id, ci.id, 'ALUMNO' FROM usuario u, curso cu, ciclo ci WHERE u.uid_ldap='albus.dumbledore' AND cu.codigo='HGW101' AND ci.codigo='2026-2' ON CONFLICT DO NOTHING;
UPDATE recurso SET curso_id=(SELECT id FROM curso WHERE codigo='HGW101') WHERE ip='10.200.2.3' AND protocolo='TCP' AND puerto=22 AND curso_id IS NULL;
INSERT INTO permiso_rol (rol_id, recurso_id) SELECT r.id, rc.id FROM rol r, recurso rc WHERE r.nombre='ALUMNO' AND rc.ip='10.200.2.3' AND rc.protocolo='TCP' AND rc.puerto=22 ON CONFLICT DO NOTHING;
UPDATE recurso SET curso_id=(SELECT id FROM curso WHERE codigo='HGW101') WHERE ip='10.200.2.3' AND protocolo='TCP' AND puerto=80 AND curso_id IS NULL;
INSERT INTO permiso_rol (rol_id, recurso_id) SELECT r.id, rc.id FROM rol r, recurso rc WHERE r.nombre='ALUMNO' AND rc.ip='10.200.2.3' AND rc.protocolo='TCP' AND rc.puerto=80 ON CONFLICT DO NOTHING;
INSERT INTO curso (codigo, nombre) VALUES ('HGW102', 'Transfiguration') ON CONFLICT (codigo) DO NOTHING;
-- HGW102 está INACTIVE: no se crean inscripciones ni permisos
INSERT INTO curso (codigo, nombre) VALUES ('HGW103', 'Defence Against the Dark Arts') ON CONFLICT (codigo) DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) SELECT u.id, cu.id, ci.id, 'ALUMNO' FROM usuario u, curso cu, ciclo ci WHERE u.uid_ldap='albus.dumbledore' AND cu.codigo='HGW103' AND ci.codigo='2026-2' ON CONFLICT DO NOTHING;
UPDATE recurso SET curso_id=(SELECT id FROM curso WHERE codigo='HGW103') WHERE ip='10.200.2.4' AND protocolo='TCP' AND puerto=22 AND curso_id IS NULL;
INSERT INTO permiso_rol (rol_id, recurso_id) SELECT r.id, rc.id FROM rol r, recurso rc WHERE r.nombre='ALUMNO' AND rc.ip='10.200.2.4' AND rc.protocolo='TCP' AND rc.puerto=22 ON CONFLICT DO NOTHING;
COMMIT;
