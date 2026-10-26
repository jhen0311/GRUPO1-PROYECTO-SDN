# R3 · Detección y mitigación de ataques encubiertos en la intranet

Escaneo de red y de puertos, suplantación de IP y ARP, insistencia sobre recursos denegados y ataques «todos a uno» de usuarios autenticados. Se construye sobre el escenario de R1 y R2.

Diseño de bajo nivel: [`docs/LLSD_R3.md`](docs/LLSD_R3.md) (fuente del documento Word; figuras en `docs/`, regenerables con `figs.py`).

| Módulo | App de ONOS | Función |
|---|---|---|
| tel354-api | bundle (en `comun/`) | Eventos y servicios compartidos |
| M2 | tel354.m2 | Slow-path de la regla 55: DROP 150 y AuthorizationDenied |
| M3 | tel354.m3 | Contadores cada 5 s (regla 301 de N2) |
| M4a | tel354.m4a | Ventanas W₁ = 10 s y W₂ = 600 s; ThreatSuspected |
| M5 | tel354.m5 | Evaluación, contención N0–N2 (reglas 300–302), reserva de 96 entradas, confirmación N3; API REST y comandos `tel354:*` |
| M6 | tel354.m6 | auth_log |

Requisitos previos en M1 (LLSD, Tabla 2): proxy ARP solo del gateway con evento ArpProbe, BindingViolation de las reglas 505 y 800, eventos de sesión, revocación y paso forzado a cuarentena.

Estado: diseño listo; implementación según el orden de la Tabla 27 del LLSD. Fichas de cada módulo, acuerdos comunes y estructura prevista: [`../docs/PLAN_MODULOS.md`](../docs/PLAN_MODULOS.md).
