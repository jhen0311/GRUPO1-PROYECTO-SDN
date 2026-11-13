-- Generado por comun/db/gen_usuarios.py --n 20 --dias-sancion 30
-- Cuentas de prueba del piloto (OpenLDAP: r1-identidad/servicios/ldap/usuarios_piloto.txt)
BEGIN;

-- alumno01..alumno20 (rol base ALUMNO, inscripción ALUMNO)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('26AL001', 'alumno01', 'Alumno de prueba 01', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL002', 'alumno02', 'Alumno de prueba 02', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL003', 'alumno03', 'Alumno de prueba 03', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL004', 'alumno04', 'Alumno de prueba 04', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL005', 'alumno05', 'Alumno de prueba 05', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL006', 'alumno06', 'Alumno de prueba 06', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL007', 'alumno07', 'Alumno de prueba 07', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL008', 'alumno08', 'Alumno de prueba 08', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL009', 'alumno09', 'Alumno de prueba 09', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL010', 'alumno10', 'Alumno de prueba 10', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL011', 'alumno11', 'Alumno de prueba 11', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL012', 'alumno12', 'Alumno de prueba 12', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL013', 'alumno13', 'Alumno de prueba 13', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL014', 'alumno14', 'Alumno de prueba 14', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL015', 'alumno15', 'Alumno de prueba 15', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL016', 'alumno16', 'Alumno de prueba 16', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL017', 'alumno17', 'Alumno de prueba 17', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL018', 'alumno18', 'Alumno de prueba 18', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL019', 'alumno19', 'Alumno de prueba 19', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26AL020', 'alumno20', 'Alumno de prueba 20', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno01'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno02'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno03'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno04'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno05'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno06'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno07'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno08'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno09'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno10'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno11'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno12'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno13'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno14'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno15'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno16'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno17'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno18'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno19'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'alumno20'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO')
ON CONFLICT DO NOTHING;

-- jp01..jp20 (rol base ALUMNO, inscripción JP)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('26JP001', 'jp01', 'Jefe de practica de prueba 01', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP002', 'jp02', 'Jefe de practica de prueba 02', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP003', 'jp03', 'Jefe de practica de prueba 03', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP004', 'jp04', 'Jefe de practica de prueba 04', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP005', 'jp05', 'Jefe de practica de prueba 05', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP006', 'jp06', 'Jefe de practica de prueba 06', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP007', 'jp07', 'Jefe de practica de prueba 07', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP008', 'jp08', 'Jefe de practica de prueba 08', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP009', 'jp09', 'Jefe de practica de prueba 09', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP010', 'jp10', 'Jefe de practica de prueba 10', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP011', 'jp11', 'Jefe de practica de prueba 11', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP012', 'jp12', 'Jefe de practica de prueba 12', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP013', 'jp13', 'Jefe de practica de prueba 13', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP014', 'jp14', 'Jefe de practica de prueba 14', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP015', 'jp15', 'Jefe de practica de prueba 15', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP016', 'jp16', 'Jefe de practica de prueba 16', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP017', 'jp17', 'Jefe de practica de prueba 17', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP018', 'jp18', 'Jefe de practica de prueba 18', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP019', 'jp19', 'Jefe de practica de prueba 19', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26JP020', 'jp20', 'Jefe de practica de prueba 20', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp01'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp02'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp03'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp04'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp05'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp06'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp07'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp08'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp09'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp10'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp11'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp12'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp13'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp14'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp15'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp16'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp17'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp18'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp19'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'jp20'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'JP')
ON CONFLICT DO NOTHING;

-- docente01..docente20 (rol base DOCENTE, inscripción DOCENTE)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('DOC001', 'docente01', 'Docente de prueba 01', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC002', 'docente02', 'Docente de prueba 02', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC003', 'docente03', 'Docente de prueba 03', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC004', 'docente04', 'Docente de prueba 04', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC005', 'docente05', 'Docente de prueba 05', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC006', 'docente06', 'Docente de prueba 06', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC007', 'docente07', 'Docente de prueba 07', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC008', 'docente08', 'Docente de prueba 08', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC009', 'docente09', 'Docente de prueba 09', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC010', 'docente10', 'Docente de prueba 10', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC011', 'docente11', 'Docente de prueba 11', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC012', 'docente12', 'Docente de prueba 12', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC013', 'docente13', 'Docente de prueba 13', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC014', 'docente14', 'Docente de prueba 14', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC015', 'docente15', 'Docente de prueba 15', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC016', 'docente16', 'Docente de prueba 16', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC017', 'docente17', 'Docente de prueba 17', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC018', 'docente18', 'Docente de prueba 18', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC019', 'docente19', 'Docente de prueba 19', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL),
 ('DOC020', 'docente20', 'Docente de prueba 20', (SELECT id FROM rol WHERE nombre = 'DOCENTE'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente01'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente02'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente03'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente04'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente05'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente06'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente07'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente08'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente09'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente10'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente11'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente12'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente13'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente14'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente15'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente16'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente17'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente18'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente19'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'docente20'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'DOCENTE')
ON CONFLICT DO NOTHING;

-- administrativo01..administrativo20 (rol base ADMINISTRATIVO)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('ADM001', 'administrativo01', 'Administrativo de prueba 01', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM002', 'administrativo02', 'Administrativo de prueba 02', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM003', 'administrativo03', 'Administrativo de prueba 03', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM004', 'administrativo04', 'Administrativo de prueba 04', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM005', 'administrativo05', 'Administrativo de prueba 05', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM006', 'administrativo06', 'Administrativo de prueba 06', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM007', 'administrativo07', 'Administrativo de prueba 07', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM008', 'administrativo08', 'Administrativo de prueba 08', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM009', 'administrativo09', 'Administrativo de prueba 09', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM010', 'administrativo10', 'Administrativo de prueba 10', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM011', 'administrativo11', 'Administrativo de prueba 11', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM012', 'administrativo12', 'Administrativo de prueba 12', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM013', 'administrativo13', 'Administrativo de prueba 13', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM014', 'administrativo14', 'Administrativo de prueba 14', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM015', 'administrativo15', 'Administrativo de prueba 15', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM016', 'administrativo16', 'Administrativo de prueba 16', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM017', 'administrativo17', 'Administrativo de prueba 17', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM018', 'administrativo18', 'Administrativo de prueba 18', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM019', 'administrativo19', 'Administrativo de prueba 19', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL),
 ('ADM020', 'administrativo20', 'Administrativo de prueba 20', (SELECT id FROM rol WHERE nombre = 'ADMINISTRATIVO'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;

-- ti01..ti20 (rol base TI)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('TIC001', 'ti01', 'Analista de TI de prueba 01', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC002', 'ti02', 'Analista de TI de prueba 02', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC003', 'ti03', 'Analista de TI de prueba 03', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC004', 'ti04', 'Analista de TI de prueba 04', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC005', 'ti05', 'Analista de TI de prueba 05', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC006', 'ti06', 'Analista de TI de prueba 06', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC007', 'ti07', 'Analista de TI de prueba 07', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC008', 'ti08', 'Analista de TI de prueba 08', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC009', 'ti09', 'Analista de TI de prueba 09', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC010', 'ti10', 'Analista de TI de prueba 10', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC011', 'ti11', 'Analista de TI de prueba 11', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC012', 'ti12', 'Analista de TI de prueba 12', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC013', 'ti13', 'Analista de TI de prueba 13', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC014', 'ti14', 'Analista de TI de prueba 14', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC015', 'ti15', 'Analista de TI de prueba 15', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC016', 'ti16', 'Analista de TI de prueba 16', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC017', 'ti17', 'Analista de TI de prueba 17', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC018', 'ti18', 'Analista de TI de prueba 18', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC019', 'ti19', 'Analista de TI de prueba 19', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL),
 ('TIC020', 'ti20', 'Analista de TI de prueba 20', (SELECT id FROM rol WHERE nombre = 'TI'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;

-- invitado01..invitado20 (rol base INVITADO)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('INV001', 'invitado01', 'Invitado de prueba 01', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV002', 'invitado02', 'Invitado de prueba 02', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV003', 'invitado03', 'Invitado de prueba 03', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV004', 'invitado04', 'Invitado de prueba 04', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV005', 'invitado05', 'Invitado de prueba 05', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV006', 'invitado06', 'Invitado de prueba 06', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV007', 'invitado07', 'Invitado de prueba 07', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV008', 'invitado08', 'Invitado de prueba 08', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV009', 'invitado09', 'Invitado de prueba 09', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV010', 'invitado10', 'Invitado de prueba 10', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV011', 'invitado11', 'Invitado de prueba 11', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV012', 'invitado12', 'Invitado de prueba 12', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV013', 'invitado13', 'Invitado de prueba 13', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV014', 'invitado14', 'Invitado de prueba 14', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV015', 'invitado15', 'Invitado de prueba 15', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV016', 'invitado16', 'Invitado de prueba 16', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV017', 'invitado17', 'Invitado de prueba 17', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV018', 'invitado18', 'Invitado de prueba 18', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV019', 'invitado19', 'Invitado de prueba 19', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2')),
 ('INV020', 'invitado20', 'Invitado de prueba 20', (SELECT id FROM rol WHERE nombre = 'INVITADO'), 'ACTIVO', (SELECT fin FROM ciclo WHERE codigo = '2026-2'))
ON CONFLICT DO NOTHING;

-- sancion01..sancion20 (rol base ALUMNO, inscripción ALUMNO)
INSERT INTO usuario (codigo, uid_ldap, nombres, rol_base_id, estado, vence_en) VALUES
 ('26SA001', 'sancion01', 'Alumno sancionado de prueba 01', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA002', 'sancion02', 'Alumno sancionado de prueba 02', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA003', 'sancion03', 'Alumno sancionado de prueba 03', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA004', 'sancion04', 'Alumno sancionado de prueba 04', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA005', 'sancion05', 'Alumno sancionado de prueba 05', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA006', 'sancion06', 'Alumno sancionado de prueba 06', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA007', 'sancion07', 'Alumno sancionado de prueba 07', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA008', 'sancion08', 'Alumno sancionado de prueba 08', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA009', 'sancion09', 'Alumno sancionado de prueba 09', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA010', 'sancion10', 'Alumno sancionado de prueba 10', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA011', 'sancion11', 'Alumno sancionado de prueba 11', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA012', 'sancion12', 'Alumno sancionado de prueba 12', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA013', 'sancion13', 'Alumno sancionado de prueba 13', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA014', 'sancion14', 'Alumno sancionado de prueba 14', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA015', 'sancion15', 'Alumno sancionado de prueba 15', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA016', 'sancion16', 'Alumno sancionado de prueba 16', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA017', 'sancion17', 'Alumno sancionado de prueba 17', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA018', 'sancion18', 'Alumno sancionado de prueba 18', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA019', 'sancion19', 'Alumno sancionado de prueba 19', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL),
 ('26SA020', 'sancion20', 'Alumno sancionado de prueba 20', (SELECT id FROM rol WHERE nombre = 'ALUMNO'), 'ACTIVO', NULL)
ON CONFLICT DO NOTHING;
INSERT INTO inscripcion (usuario_id, curso_id, ciclo_id, tipo) VALUES
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion01'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion02'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion03'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion04'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion05'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion06'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion07'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion08'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion09'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion10'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion11'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion12'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion13'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion14'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion15'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion16'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion17'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion18'), (SELECT id FROM curso WHERE codigo = 'CUR03'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion19'), (SELECT id FROM curso WHERE codigo = 'TEL354'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO'),
 ((SELECT id FROM usuario WHERE uid_ldap = 'sancion20'), (SELECT id FROM curso WHERE codigo = 'TEL141'), (SELECT id FROM ciclo WHERE codigo = '2026-2'), 'ALUMNO')
ON CONFLICT DO NOTHING;

-- Sanción vigente de sancion01..sancion20: equipo, sesión cerrada, incidente N3 y cuarentena
DO $$
DECLARE u RECORD; d INTEGER; s BIGINT; i BIGINT;
BEGIN
  FOR u IN SELECT id, uid_ldap, right(uid_ldap, 2)::int AS n FROM usuario
           WHERE uid_ldap LIKE 'sancion__' AND uid_ldap <> 'sancion.demo' LOOP
    CONTINUE WHEN EXISTS (SELECT 1 FROM cuarentena q WHERE q.usuario_id = u.id AND q.fin > now());
    INSERT INTO dispositivo (mac, hostname, switch_dpid, puerto)
      VALUES (('02:5a:00:00:00:' || lpad(to_hex(u.n), 2, '0'))::macaddr, u.uid_ldap || '-pc',
              'of:0000000000000002', 3)
      ON CONFLICT (mac) DO UPDATE SET hostname = EXCLUDED.hostname RETURNING id INTO d;
    INSERT INTO sesion (usuario_id, dispositivo_id, switch_dpid, puerto, rol_efectivo_id, ip, vlan_id,
                        estado, inicio, fin, motivo_cierre)
      VALUES (u.id, d, 'of:0000000000000002', 3, (SELECT id FROM rol WHERE nombre = 'ALUMNO'),
              ('10.18.1.' || u.n)::inet, 102, 'CERRADA', now() - interval '2 hours',
              now() - interval '1 hour', 'CUARENTENA') RETURNING id INTO s;
    INSERT INTO incidente (sesion_id, requisito, tipo_ataque, fuente, severidad, accion, evidencia,
                           detectado_en, expira_en)
      VALUES (s, 'R3', 'SUPLANTACION', 'SIM', 'Fuerte', 'N3',
              '{"nota": "sanción de prueba (gen_usuarios.py)", "criticidad": "CRITICO", "cierre": "CONFIRMACION"}',
              now() - interval '90 minutes', now() - interval '1 hour') RETURNING id INTO i;
    INSERT INTO cuarentena (usuario_id, incidente_id, inicio, fin)
      VALUES (u.id, i, now() - interval '1 hour', now() + interval '30 days');
  END LOOP;
END $$;
COMMIT;
