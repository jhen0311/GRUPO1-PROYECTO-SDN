<div align="center">

# Red de campus SDN · Zero Trust

### Arquitectura de red de campus universitario basada en SDN/OpenFlow, con autenticación 802.1X y control de acceso dinámico por rol

**TEL354 · Redes Definidas por Software**<br>
Pontificia Universidad Católica del Perú · 2026-2 · **Grupo 01**

<br>

[![ONOS](https://img.shields.io/badge/ONOS-2.7.0-0B5394?style=for-the-badge)](https://opennetworking.org/onos/)
[![OpenFlow](https://img.shields.io/badge/OpenFlow-1.3-2E7D32?style=for-the-badge)](https://opennetworking.org/)
[![Open vSwitch](https://img.shields.io/badge/Open%20vSwitch-OVS-455A64?style=for-the-badge)](https://www.openvswitch.org/)
[![Java](https://img.shields.io/badge/Java-11-F57C00?style=for-the-badge)](https://openjdk.org/projects/jdk/11/)

</div>

---

## Contexto

En una red de campus tradicional, cualquier equipo conectado a una toma de red recibe conectividad y puede alcanzar los servidores internos: la red **confía por ubicación**. En un campus con miles de usuarios (alumnos, docentes, personal administrativo, TI e invitados), ese modelo deja expuestos los recursos académicos y administrativos.

Este proyecto propone una red de campus basada en **SDN (Software-Defined Networking)** con un enfoque **Zero Trust**: ningún equipo recibe acceso por estar conectado; cada usuario debe **autenticarse** y la red le concede **solo lo que su rol permite**.

## Objetivo

Diseñar e implementar una arquitectura de red de campus en la que un controlador SDN (**ONOS**) programe dinámicamente los switches (**Open vSwitch**, vía **OpenFlow 1.3**) según la identidad y el rol de cada usuario. La arquitectura cumple cinco requerimientos de seguridad:

| | Requerimiento |
|:-:|---|
| **R1** | Identidad y sesión: autenticación 802.1X y asignación de IP según rol |
| **R2** | Autorización: acceso a recursos según rol y cursos inscritos |
| **R3** | Detección y mitigación de ataques encubiertos de usuarios autenticados |
| **R4** | Detección y mitigación de ataques DDoS |
| **R5** | Seguridad perimetral |

R1 y R2 establecen quién entra a la red y a qué puede acceder. R3, R4 y R5 abordan las **amenazas contra la intranet** que persisten aun con ese control, y que la arquitectura debe detectar y mitigar:

- **R3 · Ataques encubiertos:** un usuario que ya se autenticó puede abusar de su acceso, por ejemplo escaneando la red en busca de servicios o suplantando la identidad de otro equipo. El controlador detecta estos comportamientos y contiene al usuario de forma escalonada.
- **R4 · DDoS:** inundaciones de tráfico desde uno o varios equipos internos para saturar un servidor o el propio controlador. Se detectan por volumen y se limitan en el switch.
- **R5 · Seguridad perimetral:** amenazas que entran o salen por el borde hacia Internet. El switch edge actúa como firewall, apoyado por un IDS.

## Cómo funciona

Cuando un switch se conecta, el controlador le instala por sí solo su **régimen base**: las reglas que no dependen de ningún usuario. Para sumar un pabellón basta con registrar su switch en la base de datos.

Cuando un usuario conecta su equipo, el puerto **no tiene red**. El equipo se autentica con **802.1X** y ONOS valida sus credenciales contra el servidor de autenticación, del que obtiene su **rol**. Con la sesión aceptada, el usuario recibe una IP por DHCP y ONOS instala en el switch de acceso **solo las reglas OpenFlow que su rol permite**. Al cerrar la sesión o vencer su tiempo, esas reglas se retiran y el puerto vuelve a quedar sin acceso.

## Módulos

Cada módulo es una app propia de ONOS (bundle OSGi en Java) que usa solo la API pública del controlador. Comparten eventos y servicios a través del bundle común `tel354-api`.

| Módulo | Función | Estado |
|---|---|---|
| **M1** · Identidad y sesiones | 802.1X, sesión por rol, relay DHCP, proxy ARP del gateway | Validado en el slice |
| **M2** · Autorización | Matriz de permisos por rol (Tabla 1) y denegaciones | Validado en el slice |
| **M3** · Monitoreo | Contadores de las reglas vigiladas | Validado en el slice |
| **M4a** · Detección | Ventanas de tiempo sobre el comportamiento de cada sesión | Implementado, por validar |
| **M5** · Mitigación | Contención escalonada (N1, N2) y cuarentena (N3) | Validado en el slice |
| **M6** · Auditoría | Registro de eventos de seguridad (por ahora, servicio fuera de ONOS) | Validado en el slice |
| **M7 + M8** · Régimen base y caminos fijos | Reglas base de cada switch y caminos del core, al detectarlo | Validado en el slice |

## Entorno de pruebas

La arquitectura se valida sobre un **slice del VNRT**, el testbed del laboratorio, que sirve como maqueta de la red de campus: un switch core, dos switches de acceso que representan dos pabellones y cuatro hosts de usuario, cada uno en su propia VM con Open vSwitch o Linux. ONOS corre en Docker en la VM del controlador, junto a los servicios de identidad y direccionamiento.

La red virtual del slice descarta las tramas 802.1X, así que, por ahora, el login viaja por un túnel VXLAN entre cada host y su switch de acceso. Es una medida provisional, hasta que el laboratorio habilite el reenvío de esas tramas.

La primera versión se desarrolló en **Mininet** con ONOS 1.13 y sigue documentada en el repositorio.

## Tecnologías

| Área | Tecnología |
|---|---|
| Controlador SDN | ONOS 2.7.0 en Docker (apps propias en Java 11/OSGi) |
| Plano de datos | Open vSwitch · OpenFlow 1.3 |
| Pruebas | Slice del VNRT · Mininet 2.3 (versión inicial) |
| Autenticación | IEEE 802.1X · EAP-TTLS · FreeRADIUS · OpenLDAP |
| Datos y direccionamiento | PostgreSQL · ISC Kea DHCP |
| Scripts | Python 3 · Bash |

## Repositorio

El código está en [`tel354-campus-sdn/`](tel354-campus-sdn/), organizado por requerimiento (`r1-identidad/`, `r2-autorizacion/`, `r3-ataques-encubiertos/`…), con lo común en `comun/`. La guía de despliegue y validación en el slice es [`docs/VALIDACION.md`](tel354-campus-sdn/docs/VALIDACION.md).

<div align="center">
<br>
<sub>Proyecto académico · TEL354 · PUCP 2026-2</sub>
</div>
