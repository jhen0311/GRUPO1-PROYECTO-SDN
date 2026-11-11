# Guía de validación de M1 y M5 en el slice del LAB5 (VNRT)

**TEL354 · PUCP 2026-2 · Grupo 01 · Versión 1.3 · 10/11/2026**

Esta guía valida en el slice del LAB5 que el código de la versión 2.0 (tel354-api, M1 y M5) funciona: primero que compila contra ONOS 2.7 real, luego que las apps se activan en el controlador y, por último, que hacen su trabajo con hosts reales (802.1X, IP por rol, reglas de sesión, contención y cuarentena). Se parte del slice en su estado inicial, después de `slice_reset.yml`.

El slice del LAB5 no es el piloto del LAB6, pero tiene lo necesario para validar M1 y M5: ONOS 2.7, un switch central, dos switches con dos hosts cada uno y una VM que puede hacer de todos los servidores. Lo que no se puede validar aquí es lo propio del clúster y del control in-band (prueba V9 de la Guía de M1).

**Capturas.** Cada paso indica qué captura tomar (F01 a F34) para el informe de validación. Que se vean el prompt y el comando.

**Cómo leer los comandos.** Antes de cada bloque se indica la VM y el prompt. Los bloques contienen solo comandos y van sin `sudo`: en cada VM se entra con `ubuntu`/`ubuntu` y se pasa a root con `sudo -i`. En la consola de ONOS el prompt es `onos@root >`.

## 1. Correspondencia con el piloto

| LAB6 (piloto) | LAB5 (validación) | Qué hace en la validación |
|---|---|---|
| Controlador (3 instancias) | controller (192.168.0.10): 1 instancia de ONOS 2.7 en Docker | M1, M5 y la API de TI |
| S-SERVICIOS, S-IDENTIDAD, S-RECURSOS, S-CURSOS | controller, en su interfaz de datos hacia sw1 | Las mismas IP 10.200.x.y: Kea, DNS, PostgreSQL, OpenLDAP, FreeRADIUS y recursos de prueba |
| SW-C1 y SW-C2 | sw1 (of:00000000000000c1) | Core: retorno a usuarios por VLAN y entrega a los servidores |
| SW-A1 (pabellón 1) | sw2 (of:0000000000000001) | Switch de acceso de h1 y h2 |
| SW-A2 (pabellón 2) | sw3 (of:0000000000000002) | Switch de acceso de h3 y h4 |
| Ubuntu-1, Kali-1 | h1 (alumno01), h2 (alumno02) | Usuario y atacante del pabellón 1 |
| Ubuntu-2, Kali-2 | h3 (docente01), h4 (jp01) | Usuarios del pabellón 2 (h4 hace de atacante en M5) |
| Control in-band (172.16.0.0/16) | Control fuera de banda por 192.168.0.0/24 (ens3) | No se necesitan reglas para el plano de control |

*Tabla 1. Cómo se reparte el piloto en el slice del LAB5.*

Los scripts no conocen el laboratorio: todo lo que cambia frente al piloto está en el perfil `comun/config/una_instancia.env`, que fija las IP, los datapath-id y la interfaz de datos. Los scripts de servicios, instalación y régimen base lo toman con `TEL354_ENV=una_instancia.env`; con `slice.env` (por defecto) sirven al piloto.

| Nivel | Qué valida | Pasos | Tiempo aproximado |
|---|---|---|---|
| 1 | Compilación real contra ONOS 2.7 | 1 a 3 | 15 min |
| 2 | Activación de las apps en ONOS (OSGi, comandos, REST, driver JDBC) | 4 y 5 | 10 min |
| 3 | Comportamiento con hosts reales | 6 a 14 | 1 h 30 min |

*Tabla 2. Niveles de la validación.*

## 2. Preparación

### Paso 1. Repositorio en controller

En la terminal de tu equipo ejecutar (5800 es el puerto de controller en el gateway del slice; `<IP_GATEWAY>` es la del LAB5):

```bash
scp -P 5800 tel354-campus-sdn-v2.zip ubuntu@<IP_GATEWAY>:
ssh -p 5800 ubuntu@<IP_GATEWAY>
```

En `ubuntu@controller:~$` ejecutar:

```bash
sudo -i
```

En `root@controller:~#` ejecutar:

```bash
mv /home/ubuntu/tel354-campus-sdn-v2.zip /root/ && cd /root && unzip -q -o tel354-campus-sdn-v2.zip
cd tel354-campus-sdn && ls
grep -E "sw[123]" /root/TEL354_LAB5_2026_2/ansible/inventory.ini
```

La última línea muestra las IP de gestión de los switches: sw1 192.168.0.11, sw2 192.168.0.12 y sw3 192.168.0.13. El inventario del LAB5 está en `/root/TEL354_LAB5_2026_2/ansible` y no lista los hosts: su IP de gestión es la de `ens3` (`ip -br addr` en cada uno): h1 192.168.0.21, h2 .22, h3 .23 y h4 .24.

### Paso 2. Paquetes de controller

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
apt update
DEBIAN_FRONTEND=noninteractive apt install -y openjdk-11-jdk maven jq unzip python3-requests python3-psycopg2 \
  postgresql postgresql-client slapd ldap-utils freeradius freeradius-ldap freeradius-postgresql \
  kea-dhcp4-server kea-ctrl-agent kea-admin bind9 openssl
systemctl stop freeradius kea-dhcp4-server kea-ctrl-agent 2>/dev/null; true
```

## 3. Nivel 1: compilación

### Paso 3. Compilar tel354-api, M1 y M5

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
for d in comun/tel354-api r1-identidad/onos-app-m1 r3-ataques-encubiertos/onos-app-m5; do
  (cd $d && mvn -q -U clean install -DskipTests -Dcheckstyle.skip) && echo "OK $d" || echo "FALLA $d"
done
ls comun/tel354-api/target/*.oar r1-identidad/onos-app-m1/target/*.oar r3-ataques-encubiertos/onos-app-m5/target/*.oar
bash r3-ataques-encubiertos/onos-app-m5/probar_evaluador.sh | tail -1
```

Resultado esperado: tres `OK`, tres archivos `.oar` y `Todas las pruebas pasaron`. La primera compilación descarga las dependencias de ONOS 2.7.0 (unos minutos). Los avisos `illegal reflective access` de Error Prone son propios de Java 11 y no afectan.

`-Dcheckstyle.skip` es necesario: el pom padre de ONOS 2.7 ejecuta checkstyle, que exige javadoc completo en cada método y detiene la compilación de tel354-api. `-U` hace que Maven reintente las dependencias que falló en un intento anterior.

**Captura F01:** la salida completa del bloque.

**Si algo falla:** guardar la salida completa del módulo que falló (`cd <módulo> && mvn clean install -DskipTests > /root/mvn.log 2>&1`) y enviarla; es la información que hace falta para corregirlo.

## 4. Nivel 2: activación en ONOS

### Paso 4. ONOS 2.7 en Docker

Se usa la imagen oficial `onosproject/onos:2.7.0`, que ya está en controller, solo con `drivers`, `openflow` y `gui2`. Las apps `fwd` y `proxyarp` no deben estar activas: responderían el ARP y reenviarían tráfico por su cuenta. La carpeta de certificados se monta desde ya; el `ca.pem` se crea en el Paso 8.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker rm -f onos 2>/dev/null; mkdir -p /etc/tel354/certs
docker run -d --name onos --network host --restart unless-stopped \
  -e ONOS_APPS=drivers,openflow,gui2 -v /etc/tel354/certs:/etc/tel354/certs:ro onosproject/onos:2.7.0
until curl -sf -u onos:rocks http://127.0.0.1:8181/onos/v1/applications >/dev/null; do sleep 5; done; echo "ONOS listo"
echo "alias onos-cli='ssh -p 8101 -o StrictHostKeyChecking=no -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa onos@localhost'" >> ~/.bashrc && source ~/.bashrc
```

Con Docker no viene el alias `onos-cli`: la consola de ONOS se abre por SSH al puerto 8101 (usuario `onos`, clave `rocks`), y la última línea crea el alias.

**Captura F02:** la línea `ONOS listo`.

### Paso 5. Instalar las tres apps y comprobarlas

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
for oar in comun/tel354-api/target/tel354-api-2.0.0.oar r1-identidad/onos-app-m1/target/tel354-m1-2.0.0.oar \
           r3-ataques-encubiertos/onos-app-m5/target/tel354-m5-2.0.0.oar; do
  curl -s -u onos:rocks -X POST -H "Content-Type: application/octet-stream" \
       "http://127.0.0.1:8181/onos/v1/applications?activate=true" --data-binary @$oar | jq -r '.name + "  " + .state'
done
sleep 10; curl -s -u onos:rocks http://127.0.0.1:8181/onos/campus/v1/r3/modo; echo
onos-cli
```

En `onos@root >` (clave `rocks`) ejecutar:

```
apps -a -s | grep tel354
scr:list | grep -i tel354
tel354:sesiones
tel354:r3-modo
```

El recurso web de M5 se publica unos segundos después de que la app queda `ACTIVE`: sin el `sleep 10`, el `curl` responde 404. Si se vuelve a ejecutar el bucle, las líneas salen en blanco porque las apps ya están instaladas.

El `grep` de la consola de Karaf no acepta `-E` ni filtra bien patrones alternativos: los logs se filtran desde controller. Después de `logout`, en `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker logs onos 2>&1 | grep -E "M1|M5|ERROR|Exception" | tail -20
```

**Capturas:** F03, la salida del bloque en controller; F04, `apps -a -s`, `scr:list` y `tel354:sesiones` en la consola; F05, `tel354:r3-modo` y el `docker logs` filtrado.

| Comprobación | Resultado esperado |
|---|---|
| `curl … /applications` | `tel354.api ACTIVE`, `tel354.m1 ACTIVE`, `tel354.m5 ACTIVE` |
| `curl … /onos/campus/v1/r3/modo` | `{"modo":"ACTIVO"}`: el recurso web de M5 responde |
| `scr:list` | M1Manager, M5Manager y ApiDocRegistrator en `enabled, 1 instance` (así lo muestra Karaf 4.2), ninguno `UNSATISFIED` |
| `tel354:sesiones`, `tel354:r3-modo` | Responden (los comandos de Karaf están registrados) |
| `docker logs` | `M1: núcleo no iniciado en 172.17.0.1 … se reintenta en 10 s` y `M5: mantenimiento sin BD`: **correcto en este paso**, todavía no hay base de datos ni network config |
| Log | Sin `ClassNotFoundException`, `NoSuchMethodError` ni `Unresolved constraint` |

*Tabla 3. Criterios del nivel 2.*

Con esto queda validado el nivel 2. Salir de la consola con `logout`.

## 5. Nivel 3: comportamiento con hosts reales

### Paso 6. Red de datos de controller

En `root@controller:~/tel354-campus-sdn#` ejecutar para identificar la interfaz de datos (la que no es ens3 ni las de Docker) y darle las IP de los servidores:

```bash
export TEL354_ENV=una_instancia.env
ip -br link | grep -vE "lo|docker|veth|ens3"
bash comun/topo/servicios_coubicados.sh
```

El script toma la interfaz `IF_DATOS=ens4` del perfil. Si la interfaz de datos tiene otro nombre, pasarlo como argumento y cambiar `IF_DATOS` en `comun/config/una_instancia.env`. `TEL354_ENV` queda exportado para los Pasos 8 a 10 en esta terminal.

**Captura F06:** las 13 IP 10.200.x.y en `ens4` y la línea con su MAC.

### Paso 7. Switches: control y datapath-id

`bootstrap.yml` del LAB5 deja cada OVS con `fail_mode=secure`, OpenFlow 1.3 y el controlador en 192.168.0.10:6653. Después se fija el datapath-id de cada switch para que coincida con la semilla de la base de datos (`switch_acceso`).

En `root@controller:~#` ejecutar (pide la contraseña de sudo de los switches: `ubuntu`):

```bash
cd /root/TEL354_LAB5_2026_2/ansible
ansible-playbook bootstrap.yml
ansible sw1 -b -K -m command -a "ovs-vsctl set bridge sw1 other-config:datapath-id=00000000000000c1"
ansible sw2 -b -K -m command -a "ovs-vsctl set bridge sw2 other-config:datapath-id=0000000000000001"
ansible sw3 -b -K -m command -a "ovs-vsctl set bridge sw3 other-config:datapath-id=0000000000000002"
cd /root/tel354-campus-sdn
```

Cada `BECOME password:` pide la contraseña de sudo de los switches (`ubuntu`). Ansible debe ejecutarse desde esa carpeta: ahí están su configuración y el inventario.

En `onos@root >` ejecutar:

```
devices
links
```

Resultado esperado: los tres switches con los id `of:00000000000000c1`, `of:0000000000000001` y `of:0000000000000002`, y cuatro enlaces (sw1↔sw2 y sw1↔sw3, en ambos sentidos). En el slice: sw2 puerto 1 ↔ sw1 puerto 2 y sw3 puerto 3 ↔ sw1 puerto 3.

Los mismos switches aparecen además con su datapath-id anterior y `available=false`. Se retiran con `device-remove <id>` (uno por cada id anterior) y se repite `devices`.

Los enlaces salen como `INDIRECT`: ONOS los descubrió por BDDP (difusión) y no por LLDP, porque la red virtual del slice no reenvía las tramas a las direcciones de grupo reservadas 01:80:c2:00:00:0x. Es un riesgo para el login 802.1X, cuyo EAPOL usa 01:80:c2:00:00:03: se comprueba en V2.

**Captura F07:** `devices` (ya limpio) y `links`.

### Paso 8. Servicios en controller

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash scripts/gen_certs.sh
python3 comun/db/gen_usuarios.py
bash comun/db/setup_db.sh
bash r1-identidad/servicios/ldap/setup_ldap.sh
bash r1-identidad/servicios/radius/setup_radius.sh
python3 r1-identidad/servicios/kea/gen_kea_conf.py --iface ens4 --extra lo --relay 192.168.0.10 > /etc/kea/kea-dhcp4.conf
cp r1-identidad/servicios/kea/kea-ctrl-agent.conf /etc/kea/kea-ctrl-agent.conf
echo -n "kea-demo" > /etc/kea/kea-api-password
chown root:_kea /etc/kea/kea-api-password && chmod 640 /etc/kea/kea-api-password
kea-dhcp4 -t /etc/kea/kea-dhcp4.conf && systemctl restart freeradius kea-dhcp4-server kea-ctrl-agent
systemd-run --unit tel354-recursos -p WorkingDirectory=$PWD python3 -u scripts/servicios/recursos.py --json comun/config/recursos_slice.json
```

En Ubuntu 24.04, `kea-ctrl-agent` no arranca (queda `inactive`, con `ConditionFileNotEmpty` sin cumplir) si no existe `/etc/kea/kea-api-password`, aunque la configuración no use autenticación. FreeRADIUS se reinicia al final porque necesita la base `campus` para arrancar.

`setup_db.sh` lee cada archivo SQL como root y se lo pasa a `psql` por la entrada estándar: el usuario `postgres` no puede entrar a `/root`, donde está el repositorio.

En `root@controller:~/tel354-campus-sdn#` ejecutar para el DNS del campus:

```bash
cat > /etc/bind/named.conf.options <<'EOF'
options {
  directory "/var/cache/bind";
  listen-on { 10.200.0.14; 127.0.0.1; };
  listen-on-v6 { none; };
  allow-query { 10.0.0.0/8; 127.0.0.1; };
  recursion yes;
};
EOF
cat > /etc/bind/named.conf.local <<'EOF'
zone "campus.pucp.edu.pe" { type master; file "/etc/bind/db.campus"; };
EOF
cat > /etc/bind/db.campus <<'EOF'
$TTL 300
@ IN SOA ns.campus.pucp.edu.pe. ti.campus.pucp.edu.pe. (1 300 60 600 300)
@ IN NS ns.campus.pucp.edu.pe.
ns         IN A 10.200.0.14
paideia    IN A 10.200.1.10
biblioteca IN A 10.200.1.20
labsdn     IN A 10.200.2.10
EOF
named-checkconf && systemctl restart named
```

En `root@controller:~/tel354-campus-sdn#` ejecutar para comprobar los servicios:

```bash
systemctl is-active postgresql slapd freeradius kea-dhcp4-server kea-ctrl-agent named tel354-recursos | paste -d' ' <(printf "%s\n" postgresql slapd freeradius kea-dhcp4 kea-ctrl-agent named recursos) -
su postgres -c "psql -d campus -Atc \"SELECT split_part(campus_rol_efectivo(uid_ldap),'|',1), count(*) FROM usuario GROUP BY 1 ORDER BY 1\""
ss -lnup | grep -E ":1812|:67 "
curl -s -X POST -H "Content-Type: application/json" -d '{"command":"version-get","service":["dhcp4"]}' http://10.200.0.13:8000/ | jq -r '.[0].result'
dig +short @10.200.0.14 paideia.campus.pucp.edu.pe
```

Resultado esperado: los siete servicios `active`; 20 cuentas o más por rol (REJECT 1 es `inactivo.demo`, desactivada a propósito); FreeRADIUS en `0.0.0.0:1812`; Kea en `10.200.0.13:67` y `127.0.0.1:67`; `0` del Control Agent y `10.200.1.10` del DNS.

**Captura F08:** la salida completa del bloque.

Kea escucha también en `lo` (`--extra lo`): el relay de M1 está en esta misma VM, así que sus mensajes entran a Kea por loopback y no por ens4.

### Paso 9. Régimen base

`base_auto.py` descubre en ONOS por qué puerto se une cada switch (LLDP) y qué puertos quedan para hosts y para controller, e instala las reglas de sw1 (core) y las Tablas 0, 1 y 2 de sw2 y sw3, con sus meters.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
python3 r2-autorizacion/proactivo/base_auto.py --dry-run
python3 r2-autorizacion/proactivo/base_auto.py
```

Resultado esperado: `core: pabellón 1 por 2, pabellón 2 por 3, controlador (<MAC de ens4>) por 1`, 20 reglas en el core y 28 en cada switch de acceso. En el slice, sw2 tiene el uplink en el puerto 1 y los hosts en 2 y 3; sw3, el uplink en el 3 y los hosts en 1 y 2.

**Captura F09:** la salida de los dos comandos.

### Paso 10. Configuración de M1 y M5

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
python3 r1-identidad/onos-app-m1/gen_m1_netcfg.py > /tmp/m1-netcfg.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @/tmp/m1-netcfg.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @r3-ataques-encubiertos/config/r3-netcfg.json
sleep 15; docker logs onos 2>&1 | grep -E "M1:|M5:" | tail -5
```

`gen_m1_netcfg.py` arma la network config de M1 a partir del perfil (`TEL354_ENV`): con `una_instancia.env` usa `nasIp` = 192.168.0.10; en el clúster usaría `nasIpPorNodo`.

Resultado esperado: `M1: autenticador 802.1X listo (NAS /192.168.0.10:… -> RADIUS 10.200.3.10:1812)`, `M1: relay DHCP listo (/192.168.0.10:67 -> Kea 10.200.0.13:67)` y `M5: modo ACTIVO, reserva 96 por switch, N1 900 s, N2 3600 s`. Los avisos del Paso 5 desaparecen. El `172.17.0.1` de la línea de M1 es el identificador de la instancia de ONOS.

**Captura F10:** la salida del `docker logs`.

### Paso 11. Hosts

En `root@controller:~#` ejecutar (la contraseña es `ubuntu`, una vez por host):

```bash
for ip in 192.168.0.21 192.168.0.22 192.168.0.23 192.168.0.24; do
  scp -rq -o StrictHostKeyChecking=accept-new /root/tel354-campus-sdn /etc/tel354/certs/ca.pem ubuntu@$ip:
done
```

A cada host se entra desde controller con `ssh ubuntu@192.168.0.2X`.

En `ubuntu@h1:~$` (y igual en h2, h3 y h4) ejecutar:

```bash
sudo -i
```

En `root@h1:~#` ejecutar:

```bash
mv /home/ubuntu/tel354-campus-sdn /root/ && mkdir -p /etc/tel354/certs && mv /home/ubuntu/ca.pem /etc/tel354/certs/
DEBIAN_FRONTEND=noninteractive apt install -y wpasupplicant isc-dhcp-client nmap hping3 dsniff dnsutils netcat-openbsd
ip -br addr
```

En h2 y h4 se agrega `python3-scapy` a ese `apt install` (pruebas de tráfico real de M5). La interfaz de datos es `ens4` (la que tenía 10.0.0.x en el LAB5); `ens3` es la de gestión y no se toca. En `root@h1:~#` ejecutar:

```bash
ip addr flush dev ens4 && ip link set ens4 up
cd /root/tel354-campus-sdn
ls /etc/tel354/certs/ca.pem && ip -br addr show ens4
```

Resultado esperado: el `ca.pem` y `ens4` en UP, sin IPv4.

## 6. Pruebas de M1

Ventanas recomendadas: el host a la izquierda y, a la derecha, `root@controller` con `docker logs -f --since 1m onos 2>&1 | grep -E "M1|M5"`; `root@sw2:~#` para las reglas. En `onos-cli` ejecutar una vez `log:set DEBUG pe.edu.pucp.tel354.m1`. Antes de cada prueba, `date` en el host para cruzar los tiempos con el log. Las pruebas son las de la Guía de M1 (Sección 13), con los nombres del LAB5.

| # | Host y comando | Resultado esperado |
|---|---|---|
| V1 | h1: `ping -c 2 -W 1 10.200.1.10` | Sin IP y sin respuesta |
| V2 | h1: `bash r1-identidad/host/login.sh ens4` (alumno01 / Alumno2026!) y luego `curl -s http://10.200.1.10:443/` | IP 10.17.x.y/12, gateway 10.16.0.1; responde el Campus Virtual. En ONOS: EAPOL-Start, Access-Accept, AUTENTICADA, primer DHCPDISCOVER, ACTIVA |
| V3 | sw2: `ovs-ofctl -O OpenFlow13 dump-flows sw2 --no-stats \| grep -E "priority=(525\|520\|505\|200\|190\|110)"` | Las 6 reglas de la sesión; 525 y 505 con `meter:` |
| V4 | h3: login con docente01 (Docente2026!); h4: login con jp01 (Jp2026!) | 10.50.x.y y 10.34.x.y, VLAN 102 |
| V5 | h2: login con alumno02 y `nmap -sn -n 10.17.0.0/24` | `0 hosts up`; ArpProbe en el log |
| V6 | h2: `hping3 -S -a 10.17.0.77 -p 443 -c 3 10.200.1.10` y `timeout 5 arpspoof -i ens4 10.16.0.1` | BindingViolation (IP) y BindingViolation (ARP) inequívoca |
| V7 | ONOS: `tel354:sesiones` y `tel354:sesion-revocar <id de h2>` | El puerto de h2 baja 5 s; EAP-Request/Identity; h2 se reautentica solo |
| V8 | sw2: `ovs-ofctl -O OpenFlow13 mod-port sw2 <puerto de h1> down; sleep 3; ovs-ofctl -O OpenFlow13 mod-port sw2 <puerto de h1> up` | Sesión de h1 cerrada con DESCONEXION |
| V8' | h1: `SIN_DHCP=1 bash r1-identidad/host/login.sh ens4` | A los 60 s, `sin DHCP en 60 s` y cierre con DESCONEXION |
| V10 | h1: `bash r1-identidad/host/logout.sh ens4` | Reglas retiradas y sesión CERRADA |

*Tabla 4. Pruebas de M1 en el LAB5. El número de puerto de cada host en sw2 lo muestra `ovs-ofctl -O OpenFlow13 show sw2`.*

**Capturas F11 a F20:** una por prueba, en el orden de la tabla (V1 = F11, V2 = F12, V3 = F13, V4 = F14, V5 = F15, V6 = F16, V7 = F17, V8 = F18, V8' = F19 y V10 = F20), con el host y el log lado a lado.

En `root@controller:~#` ejecutar para ver las sesiones en la base:

```bash
su postgres -c "psql -d campus -c \"SELECT s.id, u.uid_ldap, s.switch_dpid, s.puerto, host(s.ip), s.vlan_id, s.estado, s.motivo_cierre FROM sesion s JOIN usuario u ON u.id = s.usuario_id ORDER BY s.id DESC LIMIT 10\""
```

**Captura F21:** la salida de la consulta.

## 7. Pruebas de M5

Se siguen las pruebas P1 a P11 de la Guía de M5 (Sección 6), con esta correspondencia: Kali-1 → h2 (alumno02, sw2), Kali-2 → h4 (jp01, sw3), Ubuntu-1 → h1 (alumno01) y Ubuntu-2 → h3 (docente01). Con una sola instancia, todos los comandos se ejecutan en `onos@root >`, y la P7 se descarta en la misma instancia en lugar de otra.

Antes, en `root@controller:~/tel354-campus-sdn#` ejecutar para que N2 venza en dos minutos:

```bash
sed 's/"n2Seg": 3600/"n2Seg": 120/' r3-ataques-encubiertos/config/r3-netcfg.json > /tmp/r3-prueba.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @/tmp/r3-prueba.json
```

| # | Comando (con el id de la sesión de `tel354:sesiones`) | Dónde mirar |
|---|---|---|
| P1 | `tel354:r3-simular <h2> P --destino 10.200.1.30:tcp:21,22,23,25,80,110,135,139,443,445` | sw2: regla 300 hacia 10.200.1.30 (900 s); `tel354:reserva` = 1 |
| P2 | `tel354:r3-simular <h2> N --destino 10.200.1.30:tcp:5432` | sw2: 302, 301 ×2 y 300 con `meter:`; `dump-meters sw2`; en h2, el DNS responde y el Campus Virtual no |
| P3 | `tel354:r3-simular-contador <incidente P2>` | `renovado por 120 s` |
| P4 | h2: `logout.sh` y `login.sh` | Las 4 reglas con la IP nueva |
| P5 | Esperar 120 s | `venció sin renovación`; reserva en 0 |
| P6 | controller: `curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/campus/v1/incidentes/<P2>/confirmar -d '{"minutos": 10, "motivo": "validación P6"}'` | h2 pasa a 10.113.x.y (VLAN 999); solo DNS |
| P7 | `tel354:r3-simular <h4> D --destino 10.200.0.5 --destino 10.200.0.13 … (8 destinos)` y luego `tel354:incidente-descartar <P7> --motivo "validación"` | sw3: N2 y luego sin reglas |
| P8 | Cargar `/tmp/r3-prueba.json` con `"reservaPorSwitch": 2` y `tel354:r3-simular <h1> D …` | `reserva insuficiente`, incidente en N0 con `reserva_llena` |
| P9, P10 | `tel354:r3-simular <h4> SUPLANTACION --destino 10.34.0.77` y luego `… --destino 10.32.0.1 --inequivoca` | N2 + PRIORITARIA; después, confirmación automática y h4 en 10.114.x.y |
| P11 | `tel354:r3-modo OBSERVACION`, `tel354:r3-simular <h3> P --destino 10.200.3.12:tcp:22,80,443,1812,5432,636,8000,8181,3306,25`, `tel354:r3-modo ACTIVO` | Incidente en N0 con nivel calculado N1; sin reglas en sw3 |

*Tabla 5. Pruebas de M5 en el LAB5 (comandos completos en la Guía de M5).*

**Sintaxis en la consola de ONOS (Karaf 4.2):** `tel354:r3-simular` quiere las opciones **antes** de los argumentos y el destino entre comillas:

```
tel354:r3-simular --destino "10.200.1.30:tcp:21,22,23,80,443" <sesion> P
tel354:r3-simular --destino "10.32.0.1" --inequivoca <sesion> SUPLANTACION
```

**Capturas F22 a F29:** P1 = F22, P2 = F23, P3 y P5 = F24, P6 = F25, P7 = F26, P8 = F27, P9 y P10 = F28, y P11 = F29. En cada una, lo indicado en la columna «Dónde mirar».

### Paso 12. Cadena completa con tráfico real (Scapy)

Esta prueba necesita M2, M3 y M4a, que los Pasos 3 y 5 no instalan. Se instalan después de P1 a P11, porque el script vuelve a cargar `r3-netcfg.json` y deshace el cambio de N2 a 120 s. En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
TEL354_ENV=una_instancia.env bash r3-ataques-encubiertos/instalar_r3.sh m2 m3 m4a
```

**Captura F30:** `tel354.m2`, `tel354.m3` y `tel354.m4a` instaladas y activas.

Las pruebas P1 a P11 inyectan la sospecha directamente en M5. Para validar la cadena entera (M2 deniega y publica → M4a cuenta en ventanas → M5 contiene) se genera tráfico real desde un Kali con `r3-ataques-encubiertos/pruebas/ataque.py`, que manda los paquetes a ritmo fijo y deja un CSV con la hora de cada envío. Scapy ya quedó instalado en h2 y h4 en el Paso 11.

En `root@h2:~#` (alumno02, con la sesión ACTIVA) ejecutar:

```bash
python3 r3-ataques-encubiertos/pruebas/ataque.py puertos --destino 10.200.1.30 --puertos 20-35 --tasa 2
python3 r3-ataques-encubiertos/pruebas/ataque.py red --destinos 10.200.1.31-45 --puerto 22 --tasa 2
python3 r3-ataques-encubiertos/pruebas/ataque.py ip-falsa --destino 10.200.1.10 --ip-falsa 10.17.0.77
```

| Escenario | Umbral que dispara | Resultado esperado en ONOS |
|---|---|---|
| `puertos` (SYN a 16 puertos de un destino) | P1 = 10 en W1 = 10 s | THREAT_SUSPECTED (ESCANEO_PUERTOS); M5 instala la contención en sw2 |
| `red` (SYN a 15 destinos) | D1 = 8 en W1 = 10 s | THREAT_SUSPECTED (ESCANEO_RED) |
| `ip-falsa` (SYN con IP de origen ajena) | BindingViolation, sin umbral | SUPLANTACION inmediata |

**Capturas F31 a F33:** un escenario por captura, con h2 y el log lado a lado. **F34:** `tel354:incidentes`. Se envían también los tres CSV que deja el script.

*Tabla 5 bis. Pruebas de M5 con tráfico real. El CSV de cada corrida se cruza con `docker logs onos` para medir el tiempo de detección (hora del THREAT_SUSPECTED menos la del envío que cruzó el umbral).*

El punto ciego es medible y conviene reportarlo: un escaneo más lento que ambos umbrales ($r < P_2/W_2 = 30/600 = 0{,}05$ puertos/s) no se detecta; a ese ritmo, 1000 puertos toman unas 5,6 h. Fuerza bruta y petición maliciosa necesitan sesiones TCP/HTTP reales (hydra, curl): quedan para cuando se integre el IDS.

Para terminar las sanciones de P6 y P10 antes de tiempo, en `root@controller:~#` ejecutar y repetir el login del host:

```bash
su postgres -c "psql -d campus -c \"UPDATE cuarentena SET inicio = now() - interval '2 min', fin = now() - interval '1 min' WHERE fin > now() AND incidente_id IN (SELECT id FROM incidente WHERE fuente = 'SIM')\""
```

## 8. Qué enviar si algo falla

| Nivel | Evidencia |
|---|---|
| 1 | `/root/mvn.log` del módulo que falló |
| 2 | Salida de `apps -a -s`, `scr:list` y `docker logs onos 2>&1 \| grep -iE "tel354\|exception" \| tail -60` |
| 3 | La prueba, el resultado obtenido, `docker logs onos 2>&1 \| grep -E "M1:\|M5:" \| tail -40` y el `dump-flows` del switch |

*Tabla 6. Evidencias para corregir un fallo.*

## 9. Dejar el slice como estaba

En `root@controller:~#` ejecutar:

```bash
docker rm -f onos
sed -i '/alias onos-cli=/d' ~/.bashrc
systemctl disable --now postgresql slapd freeradius kea-dhcp4-server kea-ctrl-agent named 2>/dev/null; systemctl stop tel354-recursos
ip addr flush dev ens4
cd /root/TEL354_LAB5_2026_2/ansible && ansible-playbook slice_reset.yml
```

En cada host, `logout.sh` y luego `netplan apply` devuelven la interfaz de datos a su IP 10.0.0.x del LAB5.
