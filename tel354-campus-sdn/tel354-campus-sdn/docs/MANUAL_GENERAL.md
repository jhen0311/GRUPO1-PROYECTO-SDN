# Manual general de despliegue de la red de campus SDN en Mininet

**TEL354 · PUCP 2026-2 · Grupo 01 · Versión 4 · 26/10/2026**

Repositorio: `tel354-campus-sdn`.

Este manual explica cómo preparar desde cero, en la Mininet-VM de cada integrante, todo lo que comparten los cinco requerimientos: la VM, el software, los servicios de identidad, la topología y el controlador. Luego indica cómo se crea e instala cada módulo, en orden de R1 a R5.

Las pruebas de cada requerimiento están en manuales aparte:

| Manual | Contenido | Estado |
|---|---|---|
| `MANUAL_PRUEBAS_R1.md` | Autenticación, sesión, DHCP, confidencialidad, invitado, expiración, límite de sesiones y latencia | Listo (validado el 19/10) |
| `MANUAL_PRUEBAS_R2.md` | Batería de acceso por rol y curso, y conteo de flow entries | Listo (validado el 19/10) |
| `MANUAL_PRUEBAS_R3.md` | Casos E1 a E14 del LLSD de R3 | Se escribirá con los módulos de R3 |
| `MANUAL_PRUEBAS_R4.md` | DDoS de fuerza bruta | Pendiente (LLSD de R4) |
| `MANUAL_PRUEBAS_R5.md` | Seguridad perimetral | Pendiente (LLSD de R5) |

*Tabla G.0. Manuales del repositorio.*

**Cómo leer los comandos.** Antes de cada bloque se indica dónde ejecutarlo (el prompt o la ventana). El bloque contiene solo comandos, sin `sudo`: en la VM se trabaja siempre como root (`sudo -i`).

---

## 0. Resumen para retomar el trabajo (también sirve para iniciar otra conversación)

| Tema | Acuerdo vigente |
|---|---|
| Enfoque | Zero Trust: ningún equipo tiene red por estar conectado; cada sesión se autentica, recibe solo los permisos de su rol y sus cursos, y queda vigilada (sección 1) |
| Stack | ONOS 1.13.0 (Java 8), Open vSwitch 2.13.1 con OpenFlow 1.3, Mininet 2.3.0 en Ubuntu 20.04. Servicios: FreeRADIUS 3.0.20, OpenLDAP, PostgreSQL 12 y Kea 2.4.2 (de ISC) |
| Regla del proyecto | Cada módulo es una app propia de ONOS escrita en **Java** (bundle OSGi) que usa solo la API pública del controlador. **No se modifica el código de ONOS**; sus ajustes van por `cfg set`. Los scripts de Python y Bash son externos: instalación, pruebas y el régimen proactivo por REST |
| Autenticación | M1 es un autenticador 802.1X de paso (RFC 3579). El túnel EAP-TTLS/PAP va de extremo a extremo entre el supplicant y FreeRADIUS; ONOS nunca ve la contraseña |
| Identidad y privilegios | LDAP guarda las credenciales (SHA-512 crypt). PostgreSQL es la fuente del rol, los cursos, las sesiones y el vencimiento de las cuentas de invitado |
| DHCP | Kea externo (la app DHCP de ONOS solo maneja un rango y no clasifica por opción 82). M1 hace de relay con la opción 82: sub-opción 1 = N (pabellón) y sub-opción 2 = k (rol) |
| Direccionamiento | IP 10.(16k+N).x.y/12, gateway virtual 10.(16k).0.1 con MAC 02:00:00:00:00:‹16k›. VLAN 100+N, o 999 en cuarentena |
| Autorización | R2 proactiva: reglas 100 por rol. Servidores de cursos por inscripción: una regla 110 por sesión y servidor |
| Cookie | `appId (63–48) | tipo (47–40) | id (39–8) | índice (7–0)` |
| Modo de trabajo | Red automática (sin `--manual`). Login y logout manuales (`entrar.sh` y `logout.sh`) |
| Estado al 26/10 | R1 y R2 validados y medidos. Correcciones del 23/10 en el código (sondeo de `login.sh` a 0,1 s y cierre por límite de sesiones sin rebote): **falta recompilar M1 y repetir la latencia**. R3: LLSD listo; módulos según `PLAN_MODULOS.md` |

---

## 1. Enfoque Zero Trust de la solución

La red no confía en un equipo por estar conectado a un puerto del campus. Cada principio de Zero Trust se traduce en un mecanismo concreto:

| Principio | Cómo se cumple | Requerimiento |
|---|---|---|
| Denegación por defecto | Table-miss en drop en todas las tablas; un puerto sin sesión solo deja pasar EAPOL y ARP hacia el controlador | R1, R2 |
| Verificación explícita de cada sesión | 802.1X con EAP-TTLS contra LDAP antes de tener IP; reautenticación al vencer el Session-Timeout | R1 |
| Identidad ligada al tráfico | Binding puerto + MAC + IP (regla 520); otra IP desde la misma MAC cae en la 505 | R1 |
| Mínimo privilegio | Reglas por rol (100) y por curso inscrito (110); todo lo demás cae en la captura 55 | R2 |
| Microsegmentación | Sin tráfico entre usuarios; el proxy ARP solo responde por el gateway | R1, R2, R3 |
| Vigilancia continua y respuesta | Detección por comportamiento (P, D, N, F) y contención gradual N0–N3 | R3, R4 |
| Perímetro no confiable | El edge filtra entrada y salida; la NAT la hace el proveedor | R5 |
| Credenciales protegidas | Cifradas en todos los tramos (TLS, EAP-TTLS, LDAPS, PostgreSQL con TLS) | Transversal |

*Tabla G.1. Principios de Zero Trust y mecanismos del diseño.*

Lo que queda fuera del alcance es la **postura del equipo** (antivirus, parches): se verifica al usuario y su comportamiento en la red, no el estado del dispositivo.

---

## 2. Requisitos y topología

| Componente | Versión o detalle | Uso |
|---|---|---|
| Oracle VirtualBox | La instalada en Windows | Ejecuta la VM |
| Mininet-VM | Imagen OVF oficial: Ubuntu 20.04.1, Mininet 2.3.0, OVS 2.13.1 | Switches y hosts |
| ONOS | 1.13.0 sobre OpenJDK 8 | Controlador |
| PuTTY y pscp (o WinSCP) | Cliente SSH y copia de archivos | Acceso a la VM |
| `tel354-campus-sdn.zip` | Repositorio del grupo | Todo el software |
| Memoria | 6 GB para la VM (mínimo 4 GB), 2 CPU | ONOS, servicios y Mininet en la misma VM |

*Tabla G.2. Componentes necesarios.*

| Nodo | En el slice | Función | Identificador o dirección |
|---|---|---|---|
| core | SW1 | Switch core | of:0000000000000100 |
| edge | — | Switch edge con funciones de firewall (R5) | of:0000000000000200 |
| as1 | SW2 | Acceso, pabellón 1 (VLAN 101) | of:0000000000000001 |
| as2 | SW3 | Acceso, pabellón 2 (VLAN 102) | of:0000000000000002 |
| h1, h2 | H1, H2 | En as1, puertos 2 y 3. Usuarios previstos: alumno.demo y docente.demo | MAC 00:00:00:10:01:01 y 00:00:00:30:01:02; sin IP hasta el login |
| h3, h4 | H3, H4 | En as2, puertos 2 y 3. Usuarios previstos: ti.demo, y h4 libre (invitado, sanción o atacante de R3) | MAC 00:00:00:50:02:01 y 00:00:00:60:02:02 |
| svc | VM de servicios | FreeRADIUS .10, OpenLDAP .11, PostgreSQL .12, Kea .13, DNS .14, gestión .5, recursos 10.200.1–3.x | 10.200.0.10/16 con alias |
| ctl | Red de control | ONOS (namespace raíz), relay y NAS de M1 | 172.16.0.10/16 |

*Tabla G.3. Nodos de la topología (`comun/topo/campus_topo_demo.py`).*

Puertos:

- **core:** 1 → edge, 2 → svc (puerto T), 3 → ctl (puerto C), 4 → as1, 5 → as2.
- **as1/as2:** 1 → uplink, 2 y 3 → hosts.

Todos los switches funcionan en OpenFlow 1.3 con `fail_mode=secure`. El techo de Packet-In es 200 pps en los switches de acceso y 100 pps en el core. El control es in-band: los mensajes OpenFlow de as1 y as2 pasan por el core hacia ONOS.

---

## 3. Preparación de la VM (una vez)

1. **Imagen.** Descargar la imagen OVF de Mininet 2.3.0 sobre Ubuntu 20.04.1 desde la página de *releases* de Mininet en GitHub, descomprimirla e importarla en VirtualBox (*Archivo → Importar servicio virtualizado*).
2. **VirtualBox:** 6144 MB de RAM y 2 CPU. **Adaptador 1 en solo anfitrión** (red 192.168.56.0/24, queda como eth0) y **adaptador 2 en NAT** (queda como eth1, salida a Internet).
3. **Consola:** usuario `mininet`, clave `mininet`. Cada ventana empieza pasando a root.

   En `mininet@mininet-vm:~$` ejecutar:
   ```bash
   sudo -i
   ```
4. **Red NAT.** No persiste, así que se repite en cada arranque.

   En `root@mininet-vm:~#` ejecutar:
   ```bash
   ip link set eth1 up && dhclient eth1 && ip -br addr
   ```
   eth0 debe tener 192.168.56.101 (PuTTY y GUI de ONOS) y eth1 una IP de NAT (10.0.3.x o similar).
5. **Internet.** Comprobar la salida.

   En `root@mininet-vm:~#` ejecutar:
   ```bash
   ping -c3 www.google.com
   ```
   Si no resuelve el nombre, en `root@mininet-vm:~#` ejecutar:
   ```bash
   echo "nameserver 8.8.8.8" >> /etc/resolv.conf
   ```
6. **PuTTY:** sesión `mininet-vm` a 192.168.56.101:22, con **Window → Translation → UTF-8**. Sin UTF-8 las tildes aparecen como `▒`.

---

## 4. Copia del repositorio

1. **Borrar versiones anteriores**, para no mezclar archivos.

   En `root@mininet-vm:~#` ejecutar:
   ```bash
   rm -rf /home/mininet/tel354-demo /home/mininet/tel354-demo.zip /home/mininet/tel354-campus-sdn /home/mininet/tel354-campus-sdn.zip
   ```
2. **Copiar el zip desde Windows.**

   En la terminal de Windows (cmd o PowerShell), en la carpeta donde está el zip, ejecutar:
   ```
   pscp tel354-campus-sdn.zip mininet@192.168.56.101:/home/mininet/
   ```
3. **Descomprimir en la VM.**

   En `root@mininet-vm:~#` ejecutar:
   ```bash
   cd /home/mininet && unzip -q tel354-campus-sdn.zip && cd tel354-campus-sdn && ls
   ```

| Carpeta | Contenido | Lenguaje |
|---|---|---|
| `comun/config/demo.env` | Parámetros comunes: direcciones, secretos de prueba, ruta de los hooks de Kea | — |
| `comun/topo/` | `campus_topo_demo.py`: la topología base | Python (Mininet) |
| `comun/db/` | Modelo de datos (14 tablas), roles de BD por componente, datos de la demo, credencial de invitado y `setup_db.sh` | SQL y Bash |
| `comun/lib/onos_rest.py` | Cliente REST de ONOS y constructores de reglas | Python |
| `r1-identidad/onos-app-m1/` | **Módulo M1**: código, `pom.xml`, `m1-netcfg.json`, `instalar_m1.sh` y banco de pruebas | **Java** (app de ONOS) |
| `r1-identidad/servicios/` | `ldap/` (incluye `crear_invitado.py`), `radius/` y `kea/` | Configuración, Bash y Python |
| `r1-identidad/host/` | `entrar.sh`, `login.sh`, `logout.sh`: el «equipo» del usuario | Bash |
| `r1-identidad/tests/`, `r2-autorizacion/tests/` | Pruebas de R1 y R2 | Python |
| `r2-autorizacion/proactivo/` | `onos_static.py`: régimen proactivo por REST (sección 7) | Python |
| `r3-ataques-encubiertos/` | LLSD de R3 y sus figuras; aquí vivirán las apps de R3 | Java (por crear) |
| `r4-…`, `r5-…` | README (pendientes) | — |
| `scripts/` | `instalar_dependencias.sh`, `gen_certs.sh`, `evidencias_r1r2.sh` y `servicios/` (arranque y parada dentro de svc) | Bash y Python |
| `docs/` | Este manual, los manuales de pruebas, `PENDIENTES.md` y `PLAN_MODULOS.md` | — |

*Tabla G.4. Estructura del repositorio.*

> Los archivos se copian siempre con pscp o WinSCP. Pegar código en la terminal altera la indentación y provoca `IndentationError`.

---

## 5. Dependencias, ONOS y servicios (una vez)

Desde aquí, salvo que se indique otro prompt, se trabaja en la raíz del repositorio: `root@mininet-vm:/home/mininet/tel354-campus-sdn#`.

### 5.1. Dependencias

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash scripts/instalar_dependencias.sh
```

Tarda entre 5 y 10 minutos. Debe terminar con todas las líneas en **OK**:

- Java 8, Kea 2.4, hook lease_cmds y Kea con PostgreSQL;
- `eapol_test`, `freeradius`, `slapd`, `psql`, `dnsmasq`;
- `wpa_supplicant`, `wpa_cli`, `dhclient`, `mvn`;
- `nmap`, `hping3` y `arpspoof` (pruebas de R3).

| Paso | Qué hace | Por qué |
|---|---|---|
| 1/6 policy-rc.d | Impide que apt arranque servicios | El postinst de FreeRADIUS fallaba y dejaba paquetes sin configurar |
| 2/6 Paquetes | Java 8, servicios, supplicant, Maven, dhclient, arping, tcpdump, herramientas de compilación y de prueba de R3. Fija Java 8 como alternativa | Maven instala Java 11 como dependencia, pero ONOS 1.13 y los módulos usan Java 8 |
| 3/6 eapol_test | Lo compila desde wpa_supplicant 2.9, sin D-Bus | El paquete no existe en Ubuntu 20.04 |
| 4/6 Kea 2.4 | Repositorio de ISC, con `isc-kea-hooks` | Ubuntu 20.04 trae Kea 1.6 |
| 5/6 systemd | Desactiva los servicios en el namespace raíz | Corren dentro de svc |
| 6/6 | Comprobación | — |

*Tabla G.5. Pasos de `scripts/instalar_dependencias.sh`.*

La línea «NOPE: Checking for apt dependency apt-transport-https» del paso 4 es normal.

### 5.2. ONOS 1.13.0

En `root@mininet-vm:~#` ejecutar:
```bash
cd /tmp && curl -LO https://repo1.maven.org/maven2/org/onosproject/onos-releases/1.13.0/onos-1.13.0.tar.gz
ls -lh onos-1.13.0.tar.gz
tar -xzf onos-1.13.0.tar.gz -C /opt && mv /opt/onos-1.13.0 /opt/onos && rm onos-1.13.0.tar.gz
```

El archivo debe pesar unos 229 MB; si pesa pocos KB, la descarga falló y hay que repetirla antes de descomprimir.

### 5.3. Certificados, LDAP, base de datos y RADIUS

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar, en este orden:
```bash
bash scripts/gen_certs.sh
bash r1-identidad/servicios/ldap/setup_ldap.sh
bash comun/db/setup_db.sh
bash r1-identidad/servicios/radius/setup_radius.sh
```

| Orden | Script | Resultado esperado |
|---|---|---|
| 1 | `gen_certs.sh` | `/etc/tel354/certs` con `ca.pem`, `radius.pem`, `ldap.pem`, `db.pem`, sus claves y `dh`. `db.key` queda con grupo postgres |
| 2 | `setup_ldap.sh` | 6 usuarios y «OpenLDAP configurado». Añade la regla de AppArmor para que slapd lea los certificados |
| 3 | `setup_db.sh` | «PostgreSQL 12 configurado». Las líneas NOTICE de Kea son normales. Aplica `01` a `04`, crea la base `campus` en UTF-8 y deja PostgreSQL en **UTC** y con **TLS** (`hostssl` para onos_m1, onos_m2 y onos_m6) |
| 4 | `setup_radius.sh` | «Configuration appears to be OK» |

| Usuario | Contraseña | Rol efectivo | Uso |
|---|---|---|---|
| alumno.demo | `Alumno2026!` | ALUMNO | h1, inscrito en TEL354 |
| docente.demo | `Docente2026!` | DOCENTE | h2, inscrito en TEL354 |
| ti.demo | `TI2026!` | TI | h3, único con SSH de gestión |
| jp.demo | `Jp2026!` | JP | Doble rol (alumno base con inscripción JP) |
| inactivo.demo | `Inactivo2026!` | Rechazo | Cuenta inactiva |
| sancion.demo | `Sancion2026!` | CUARENTENA | Sanción vigente: VLAN 999, sin reglas 110 |
| invitado-NNNN | Generada por `crear_invitado.py` | INVITADO | Credencial temporal |

*Tabla G.6. Usuarios de la demo. La identidad externa del túnel es `anonymous`.*

| Rol | Session-Timeout | Sesiones simultáneas |
|---|---|---|
| ALUMNO, JP | 8 h (28 800 s) | 2 |
| DOCENTE, TI | 12 h (43 200 s) | 3 |
| ADMINISTRATIVO | 12 h | 2 |
| INVITADO | 4 h, o lo que falte para el vencimiento de la cuenta | 1 |
| CUARENTENA | Lo que falte de la sanción, con tope de 12 h | 1 |

*Tabla G.7. Duración de la sesión y sesiones simultáneas por rol (tabla `rol`).*

> **Si la VM ya tenía PostgreSQL de una versión anterior:** repetir `setup_db.sh` (aplica UTC, TLS y la credencial de invitado). Si Kea ya había entregado leases fuera de UTC, borrarlos con `DELETE FROM lease4` en la base `kea` y, en el host, `rm /run/dhclient-h1-eth0.leases`.

---

## 6. Cada sesión de trabajo: ONOS y topología

### 6.1. Ventanas

| Ventana | Prompt | Uso | Nunca |
|---|---|---|---|
| 1 · ONOS | `onos>` | Consola de ONOS en primer plano | Escribir `logout` o cerrarla: apaga ONOS |
| 2 · Mininet | `mininet>` | CLI de Mininet | Editar archivos: `#` inicia un comentario y corta el comando |
| 3 · Raíz | `root@mininet-vm:/home/mininet/tel354-campus-sdn#` | Scripts, instalación de módulos, `ovs-ofctl`, `psql` | — |
| 4+ · Hosts | `[h1] /home/mininet/tel354-campus-sdn#` | Una por host, abierta con `entrar.sh hX` | — |

Para abrir más ventanas: clic derecho en la barra de PuTTY → *Duplicate Session*, y luego `sudo -i`.

En las ventanas 2 y 3 (`root@mininet-vm:~#`), antes de empezar, ejecutar:
```bash
export PYTHONIOENCODING=utf-8 LANG=C.UTF-8
cd /home/mininet/tel354-campus-sdn
```

### 6.2. Ventana 1: ONOS

En `root@mininet-vm:~#` ejecutar:
```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ONOS_APPS=drivers,openflow
/opt/onos/bin/onos-service start
```

Tras ~1 min aparece el prompt `onos>`. En `onos>` ejecutar:
```
apps -s -a
```

Deben aparecer drivers, optical-model, hostprovider, lldpprovider, openflow-base y openflow. **fwd no debe aparecer**: su reenvío reactivo saltaría a R2. Si aparece, en `onos>` ejecutar:
```
app deactivate org.onosproject.fwd
```

- **GUI:** `http://192.168.56.101:8181/onos/ui` (usuario `onos`, clave `rocks`). Si rechaza las credenciales, en la ventana 3 ejecutar `/opt/onos/bin/onos-user-password onos rocks`.
- **Notas de la consola:** en `onos>` se escribe solo el comando. El `grep` de Karaf no admite `-E` ni alternativas con `|`. `log:display` muestra solo un búfer reciente; para buscar en todo el historial se usa `/opt/onos/apache-karaf-*/data/log/karaf.log*` desde la ventana 3.

### 6.3. Ventana 2: topología, sin `--manual`

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
mn -c && python3 comun/topo/campus_topo_demo.py --services
```

Resultado esperado: el plan de la demo (h1–h4 con «DHCP tras 802.1X»), PostgreSQL, OpenLDAP, FreeRADIUS, Kea DHCPv4, DNS y los recursos en **OK**, y el prompt `mininet>`.

| Opción | Efecto |
|---|---|
| (ninguna) | Hosts sin IP, servicios detenidos |
| `--services` | Arranca los servicios dentro de svc. Kea arranca con `TZ=UTC` |
| `--manual` | **Solo diagnóstico:** IP fija y ARP estático, sin M1 (sección 10) |
| `--controller IP` | ONOS en otra dirección (por defecto 127.0.0.1) |

*Tabla G.8. Opciones de `campus_topo_demo.py`.*

Para verificar, en `mininet>` ejecutar:
```
net
sh ovs-vsctl --columns=target,controller_rate_limit list controller
sh ovs-ofctl -O OpenFlow13 meter-features as1
```

Y en `onos>`:
```
devices
links
```

| Comando | Esperado |
|---|---|
| `net` | core con edge, svc, ctl, as1 y as2; as1 con h1 y h2; as2 con h3 y h4 |
| `list controller` | 200 en as1 y as2, 100 en core |
| `meter-features` | `band_types: drop`, `capabilities: kbps pktps burst stats` |
| `devices` y `links` | 4 dispositivos (`available=true`, `OF_13`) y 6 enlaces (3 bidireccionales) |
| GUI, topología (tecla **H** muestra los hosts) | 4 devices, 6 links, **0 hosts**, 8 flows (LLDP y BDDP de ONOS) |

### 6.4. Cómo se entra a un host

Mininet crea cada host como un `bash` dentro de su propio **namespace de red**: comparte el disco y los procesos de la VM, pero tiene sus propias interfaces, IP, rutas y caché ARP. `r1-identidad/host/entrar.sh` busca el PID de ese bash, abre una shell en su namespace con `nsenter` y, sin `--shell`, ejecuta además `login.sh`, que equivale a «encender la PC y conectar el cable».

Para entrar sin login, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/host/entrar.sh h1 --shell
```

Para entrar con login (802.1X y DHCP), en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/host/entrar.sh h1
```

| Forma | Ventaja | Limitación |
|---|---|---|
| `entrar.sh` (nsenter) | Funciona **sin IP**, una ventana por host, terminal interactiva | — |
| `mininet> h1 cmd` | Rápido para un comando | No es interactivo; no sirve para escribir la contraseña |
| `xterm h1` | Ventana propia | Requiere X11 (la VM no tiene entorno gráfico) |
| SSH al host | Como en el slice | Requiere IP: solo es posible después del login |

---

## 7. Régimen proactivo: `onos_static.py`

### 7.1. Qué instala y por qué hoy es obligatorio

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/proactivo/onos_static.py
ping -c2 10.200.0.10
```

El script instala por la API REST de ONOS todo lo que no depende de las sesiones:

| Parte | Reglas | Módulo que la debería solicitar |
|---|---|---|
| Régimen base del switch de acceso | 900 (EAPOL), 800 (ARP por puerto), 600 (uplink), 10 (descarte) y los meters | M7 |
| Caminos fijos | Tabla 2 de acceso (200 a servicios, 180 por defecto) y las reglas del core | M8 |
| Política por rol | Tabla 1: 100 por rol (desde `v_permiso_efectivo`), 60, 58, 57, 55, 50 y 0 | M2 |

*Tabla G.9. Contenido de `onos_static.py`.*

**Hoy es obligatorio:** sin la regla 900 ningún EAPOL llega a M1 y nadie puede iniciar sesión; sin las reglas del core, ONOS no alcanza los servicios. Por eso se ejecuta antes de instalar M1.

Resultado esperado: **core** con 29 reglas (31 en el switch, contando LLDP y BDDP de ONOS) y **as1 y as2** con 28 reglas cada uno y 5 meters. Para verificar, en `onos>` ejecutar:
```
flows -s pending_add
meters
```

Debe dar `flowRuleCount=0` en los 4 switches y 5 meters por switch de acceso en ADDED. Si los meters fallan, en la ventana 3 ejecutar `onos_static.py --borrar` y luego `onos_static.py --sin-meters`.

### 7.2. Lo estático y lo dinámico

Las reglas de esta sección son **proactivas por diseño**: su número no depende de los usuarios (por eso escalan), y la parte dinámica (sesión, IP, cursos, contención) la instalan los módulos en cada evento. Lo que sí falta es que estas reglas proactivas también las gestione el controlador:

- que se instalen solas cuando un switch se conecta, y no al correr un script;
- que la Tabla 1 se recalcule cuando cambia la política en PostgreSQL (`policy_changed`);
- que lleven el appId de su módulo y no el de REST.

**Propuesta (por confirmar):** reemplazar `onos_static.py` por dos apps: **M2** asume la Tabla 1 (además de su slow-path) y una app **M7/M8** asume el régimen base y los caminos al detectar cada switch (`DeviceListener`). Cuando existan, `onos_static.py` queda solo para diagnóstico. Está registrado en `PLAN_MODULOS.md`.

---

## 8. Cómo se crea un módulo

### 8.1. Dónde vive y en qué lenguaje

Los módulos M1 a M8 se escriben en **Java**, porque viven dentro de ONOS: cada uno es un bundle OSGi empaquetado como `.oar` que Karaf carga en el mismo proceso del controlador. Python no puede ejecutarse dentro de ONOS 1.13; un programa externo en Python solo puede hablar con ONOS por la API REST, que no entrega los Packet-In en tiempo real. Por eso Python se usa en los scripts externos (pruebas, instalación y el régimen proactivo) y Java en los módulos.

M1, el único módulo implementado, está en `r1-identidad/onos-app-m1/`:

| Ruta | Contenido |
|---|---|
| `pom.xml` | Proyecto Maven: app `tel354.m1`, dependencias de ONOS 1.13.0 y del driver de PostgreSQL |
| `src/main/java/pe/edu/pucp/tel354/m1/nucleo/` | Lógica sin dependencias de ONOS: RADIUS, EAP de paso, relay DHCP, proxy ARP, sesiones, JDBC |
| `src/main/java/pe/edu/pucp/tel354/m1/onos/` | Adaptador con los servicios de ONOS: PacketService, FlowRuleService, DeviceAdminService, NetworkConfigRegistry |
| `m1-netcfg.json` | Parámetros (RADIUS, Kea, JDBC) que se cargan como network config |
| `instalar_m1.sh` | Script Bash: compila con Maven, carga la netcfg e instala la app por REST |
| `banco/` | Banco de pruebas del núcleo sin ONOS |
| `target/tel354-m1-1.0.0.oar` | Resultado de la compilación (se genera en la VM) |

*Tabla G.10. Estructura de M1.*

### 8.2. Receta para los módulos nuevos

Cada módulo nuevo sigue el patrón de M1:

1. **Proyecto.** Copiar `pom.xml` de M1 y cambiar `artifactId`, `onos.app.name` (por ejemplo, `tel354.m2`), título y dependencias. Agregar la dependencia del bundle común `tel354-api` desde R3.
2. **Código.** `nucleo/` con la lógica y `onos/` con un componente (`@Component`) que obtiene los servicios con `@Reference`, registra sus procesadores y listeners en `@Activate` y los retira en `@Deactivate`. Los eventos se atienden en un ejecutor propio de un solo hilo.
3. **Configuración.** Un `mX-netcfg.json` con la clave del appId.
4. **Instalación.** Un `instalar_mX.sh` igual al de M1.
5. **Pruebas.** Su sección en el manual de pruebas del requerimiento.

Los acuerdos completos (contrato `tel354-api`, cookies, roles de base de datos, mensajes de log para medir) están en `PLAN_MODULOS.md`, sección 2.

---

## 9. Módulos por requerimiento (en orden de R1 a R5)

### 9.1. R1 · M1: identidad y sesiones

**Estado:** implementado y validado. **Requisito previo:** sección 7.

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/onos-app-m1/instalar_m1.sh
```

1. **Compila con Maven.** La primera vez descarga las dependencias de ONOS 1.13.0 (varios minutos) y genera `target/tel354-m1-1.0.0.oar`.
2. **Carga la network config:** NAS/relay 172.16.0.10, RADIUS 10.200.0.10:1812, Kea 10.200.0.13 y JDBC con `sslmode=verify-ca`.
3. **Reinstala y activa la app.** Esperado: «Estado: ACTIVE | versión 1.0.0».

> **Con el zip del 23/10 en adelante hay que compilar** (sin `--sin-compilar`): incluye el cierre por límite de sesiones sin rebote.

Para verificar, en `onos>` ejecutar:
```
apps -s -a | grep tel354
log:display | grep M1
```

Deben aparecer «M1 iniciado», «autenticador 802.1X listo» y «relay DHCP listo».

| Opción de `instalar_m1.sh` | Uso |
|---|---|
| `--sin-compilar` | Reinstala el `.oar` ya compilado y recarga la netcfg (cada sesión nueva de ONOS) |
| `--desinstalar` | Retira la app |

**Pruebas:** `MANUAL_PRUEBAS_R1.md`.

**Pendiente (M1 v1.1):** meter `ctl_P` en 525 y 505; cierre por enlace caído y por 60 s sin DHCP; y los cambios que necesita R3 (P1 a P3, P5, P6 y P8).

### 9.2. R2 · M2: autorización

**Estado:** la política proactiva la instala hoy `onos_static.py` (sección 7) y el acceso por curso (regla 110) lo instala M1. **Falta la app M2** (`r2-autorizacion/onos-app-m2/`), con:

- el slow-path de la regla 55: DROP 150 de 8 s y evento AuthorizationDenied (lo necesita R3);
- la Tabla 1 proactiva y su recálculo con `policy_changed` (propuesta de la sección 7.2).

Instalación prevista, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#`:
```bash
bash r2-autorizacion/onos-app-m2/instalar_m2.sh
```

**Pruebas:** `MANUAL_PRUEBAS_R2.md` (hoy validan la parte proactiva).

### 9.3. R3 · tel354-api, M3, M4a, M5 y M6

**Estado:** LLSD completo (`r3-ataques-encubiertos/docs/LLSD_R3.md`); implementación por hacer. Orden: `tel354-api` y M1 v1.1 → M2 → M6 → M4a → M5 en OBSERVACION → M5 en ACTIVO y M3 → M1 P6 y P8 → medición.

Instalación prevista, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#`:
```bash
bash r3-ataques-encubiertos/instalar_r3.sh
```

El script compilará e instalará, en orden, `tel354-api`, M6, M4a, M3 y M5, y cargará `r3-netcfg.json` con los umbrales y las criticidades. Rol de base de datos nuevo: `onos_m5` (`comun/db/05_r3.sql`).

**Pruebas:** `MANUAL_PRUEBAS_R3.md` (casos E1 a E14), cuando existan los módulos.

### 9.4. R4 · M4b y M5 (mitigación de DDoS)

**Estado:** por diseñar (LLSD de R4). Reutiliza los contadores de M3, la reserva de 96 entradas de M5 y las reglas reservadas 910 y 120 (cookie 0x07). **Pruebas:** `MANUAL_PRUEBAS_R4.md`.

### 9.5. R5 · edge y adaptador del IDS

**Estado:** por diseñar (LLSD de R5). Requiere un host «internet» en el edge dentro de `campus_topo_demo.py`, las reglas del edge (en M8) y el adaptador de Suricata hacia la API REST de M4a. **Pruebas:** `MANUAL_PRUEBAS_R5.md`.

---

## 10. Modo de diagnóstico (`--manual`)

`campus_topo_demo.py --services --manual`, junto con `r1-identidad/diagnostico/sesion_manual.py hX`, emula el **resultado** de M1 sin la admisión: IP fija, ARP estático y las mismas reglas de sesión. Sirve solo para aislar fallas: si R2 funciona en manual y no en automático, el problema está en R1 (M1, RADIUS, Kea o el host).

---

## 11. Apagado, reinicio y problemas frecuentes

### 11.1. Apagado

1. Detener los servicios antes de salir de Mininet, porque siguen corriendo aunque el namespace desaparezca. En `mininet>` ejecutar:
   ```
   svc bash /home/mininet/tel354-campus-sdn/scripts/servicios/stop_services.sh
   exit
   ```
2. Limpiar Mininet. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
   ```bash
   mn -c
   ```
3. Apagar ONOS. En `onos>` ejecutar:
   ```
   logout
   ```
4. Para apagar la VM, en `root@mininet-vm:~#` ejecutar `poweroff`.

### 11.2. Reinicio en otra sesión (sin repetir la instalación)

1. Red NAT (sección 3, paso 4).
2. ONOS (sección 6.2).
3. Topología (sección 6.3).
4. Régimen proactivo y módulos. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
   ```bash
   python3 r2-autorizacion/proactivo/onos_static.py
   bash r1-identidad/onos-app-m1/instalar_m1.sh --sin-compilar
   ```
5. `entrar.sh hX` por host.

### 11.3. Problemas frecuentes de la infraestructura

Los problemas propios de cada requerimiento están en su manual de pruebas.

| Síntoma | Causa | Acción |
|---|---|---|
| eth0 sin 192.168.56.101 o sin Internet | Adaptadores de VirtualBox en orden inverso | Adaptador 1 en solo anfitrión, adaptador 2 en NAT (sección 3) |
| `fwd` activa | Faltó exportar `ONOS_APPS` | `app deactivate org.onosproject.fwd` |
| `devices` vacío | Mininet arrancó antes que ONOS | Esperar, `mn -c` y relanzar |
| «RTNETLINK: File exists» | Restos de una ejecución anterior | `mn -c` |
| PostgreSQL en FALLO al levantar la topología | El clúster sigue activo en el namespace raíz | `systemctl stop postgresql@12-main` y reiniciar los servicios en svc |
| `onos_static.py` no conecta a PostgreSQL | Reglas del core sin instalar o en PENDING_ADD | `flows -s pending_add`; `ping -c2 10.200.0.10` |
| Error al crear meters | ONOS rechazó la banda | `--borrar` y luego `--sin-meters` |
| Caracteres `▒` o `UnicodeEncodeError` | Terminal en latin-1 | PuTTY en UTF-8; `export PYTHONIOENCODING=utf-8 LANG=C.UTF-8` |
| «Unterminated quoted string» en la CLI de Mininet | `#` inicia un comentario | Usar la ventana 3 |
| `grep: undefined option -E` en `onos>` | El `grep` de Karaf no admite `-E` ni `|` | Un término por comando, o `grep -E` sobre `karaf.log*` en la ventana 3 |
| `log:display` no muestra eventos antiguos | Búfer limitado | Buscar en `/opt/onos/apache-karaf-*/data/log/karaf.log*` |
| Una app de ONOS queda INSTALLED pero no ACTIVE; «missing requirement» en el log | `Import-Package` del bundle | Revisar el `pom.xml` (en M1: `!org.checkerframework.*`) y recompilar |
| Una regla 40000 de ARP en los switches | Intercepción del núcleo de ONOS | `cfg set org.onosproject.provider.host.impl.HostLocationProvider requestInterceptsEnabled false` |
| La GUI muestra cada host dos veces, una «unknown» | ONOS identifica hosts por MAC y VLAN | Normal; `hosts` en `onos>` lo confirma (`MANUAL_PRUEBAS_R1.md`, sección 3.2) |
| Las horas de `sesion` no coinciden con el reloj | La base de datos está en UTC y la VM en hora local (UTC−7) | Restar 7 h a la hora UTC |

*Tabla G.11. Síntomas, causas y acciones.*

---

## Anexo A. Prioridades, tablas y cookies ocupadas

| Switch | Tabla | Prio | Regla | Quién la instala |
|---|---|---|---|---|
| Acceso | 0 | 910 | EAPOL del puerto → drop (R4, reservada) | Pendiente (M5) |
| Acceso | 0 | 900 | EAPOL → controlador | `onos_static.py` (M7) |
| Acceso | 0 | 800 | ARP por puerto de host → controlador (meter `ctl_P`, 10 pps) | `onos_static.py` |
| Acceso | 0 | 600 | Desde el uplink → Tabla 2 | `onos_static.py` |
| Acceso | 0 | 525 / 520 / 505 | DHCP / binding / IP incoherente, por sesión | M1 |
| Acceso | 0 | 10 | Descarte | `onos_static.py` |
| Acceso | 1 | 300–302 | Contención de R3 (N1 y N2) | Pendiente (M5) |
| Acceso | 1 | 150 | DROP del slow-path (8 s) | Pendiente (M2) |
| Acceso | 1 | 120 | Limitación de R4 (reservada) | Pendiente (M5) |
| Acceso | 1 | 110 | Servidor de curso, por sesión | M1 |
| Acceso | 1 | 100 | Permiso por rol (`v_permiso_efectivo`) | `onos_static.py` (M2) |
| Acceso | 1 | 60 | DNS | `onos_static.py` |
| Acceso | 1 | 58 / 57 | Cuarentena (VLAN 999): descarte interno / salida con meter | `onos_static.py` |
| Acceso | 1 | 55 | Destino interno no autorizado → controlador (meter de captura, 50 pps) | `onos_static.py` |
| Acceso | 1 | 50 / 0 | Salida por defecto / descarte | `onos_static.py` |
| Acceso | 2 | 200 / 190 | Entrega al host, por sesión | M1 |
| Acceso | 2 | 200 / 180 | Hacia servicios / por defecto (pop VLAN, uplink) | `onos_static.py` |
| Core | 0 | 205 / 200 / 190 / 0 | Cuarentena por pabellón / servidores y retorno por rol / Edge / descarte | `onos_static.py` |

*Tabla A.1. Prioridades del pipeline (AVZ07 y LLSD de R3).*

| Tipo (bits 47–40) | Dueño (appId en bits 63–48) | Identificador (bits 39–8) | Reglas |
|---|---|---|---|
| 0x01 régimen base | App dueña (hoy REST de `onos_static.py`) | Regla base | 900, 800, 600, 10 |
| 0x02 sesión | M1; el DROP 150 con el appId de M2 | `sesion.id` | 525, 505, 520, 200, 190, 110; 150 |
| 0x03 rol | M2 | `recurso.id` | 100 |
| 0x04 común | M2 | Regla | 60, 58, 57, 55, 50, 0 |
| 0x05 camino | M8 | Camino | Tabla 2 fija y core |
| 0x06 contención | M5 | `incidente.id` | 300–302 |
| 0x07 mitigación | M5 | Mitigación | 910, 120, 350 (R4) |

*Tabla A.2. Formato de la cookie. Hoy, las reglas de `onos_static.py` llevan el appId de REST.*
