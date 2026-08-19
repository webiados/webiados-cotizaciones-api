# Hoja de ruta — Cotizaciones API

> **P0 del ecosistema.** Consultable durante todo el desarrollo.
> Qué hacer ahora: [`../SIGUIENTE.md`](../SIGUIENTE.md) · Tareas: [`../TASKLIST.md`](../TASKLIST.md)
> Contexto: `../../Demos-Webiados-Clientes/CONTROL.md`
> **Sin fechas: por orden.**

## Objetivo

Que **toda** cotización de Webiados nazca, viva y se mida acá. Hoy el servicio está desplegado y
funcionando, y las cotizaciones reales se escriben a mano en Markdown.

**El trabajo no es construir. Es hacer que se use.**

## Por qué importa

Sin esto no hay forma de saber cuántas cotizaciones se enviaron ni cuántas se cerraron. Y sin ese
dato no se puede medir la meta que ordena todo Webiados: **🎯 10 conversaciones reales al mes**.

Además se improvisan montos fuera de `pricing.md`, que es una regla dura del ecosistema.

## Estado inicial

Desplegado en Railway, último commit 2026-07-22. Spring Boot 3.x + PostgreSQL + Flyway + JWT.
Formulario público en `cotiza.webiados.com`, panel privado de administración.

> ✅ **`cotiza.webiados.com` ya resuelve** (verificado 2026-08-19: CNAME →
> `d2g6tt2d.up.railway.app`, `/actuator/health` → `200 {"status":"UP"}`). Quedó apuntando a
> **Railway, o sea a esta API**: devuelve JSON, no una pantalla. El servicio está vivo en
> `https://cotizaciones-api-production-e0fb.up.railway.app` y el frontend en `webiados.com`
> (`/admin` y `/cotizacion/{codigo}`). El subdominio se deja escrito a propósito: se arregla
> el DNS, no los documentos. Detalle del pendiente en el buzón
> `../../Demos-Webiados-Clientes/docs/reportes/webiados-cotizaciones-api.md`.
> Nota aparte: **no existe formulario público** de solicitud de cotización — ver
> [`AUDITORIA.md`](AUDITORIA.md) §2.1. El flujo es saliente.

Dominio: `Quote` (con estados `PENDING → REVIEWED → SENT → ACCEPTED / REJECTED`), `QuoteOption`,
`Selection`, `AdminUser`. `V3__add_landing_fields.sql` ya agregó `titulo`, `mensaje` e `imagenes`
para landings de marca.

---

# Plan de implementación

## Sprint 1 — Adopción *(P0 — el sprint más importante y el que menos código tiene)*

**Meta:** que la próxima cotización real salga de acá y no de un Markdown.

| # | Tarea | Verificación |
|---|---|---|
| 1.1 | Verificar que el servicio responde en producción y que el panel abre | Login exitoso, panel cargando |
| 1.2 | Cargar Macarena Larraín — Opción C, $380.000 + IVA, estado `ACCEPTED` | Visible en el panel con su estado |
| 1.3 | Cargar Pastelería Vientos del Sur — 3 opciones, estado `SENT` | Visible en el panel con su estado |
| 1.4 | Detectar qué impide usarlo para la próxima cotización | Lista escrita de impedimentos concretos |
| 1.5 | Resolver esos impedimentos | — |
| 1.6 | **Enviar una cotización real a un cliente desde el sistema** | El cliente la recibió |

**DoD del sprint:** una cotización enviada a un cliente real, generada acá.

> Si algo impide usarlo, **ese impedimento es el trabajo** — no una excusa para volver al
> Markdown. Lo más probable: que el PDF/landing no se vea tan bien como el Markdown exportado.

## Sprint 2 — Una sola fuente de precios *(P1)*

**Meta:** que sea imposible improvisar un monto.

| # | Tarea | Verificación |
|---|---|---|
| 2.1 | Consumir `GET /api/v1/pricing` del Core | Las `Selection` se pueblan desde el Core |
| 2.2 | Job o comando de sincronización de `Selection` | Un cambio en `pricing.md` se refleja acá |
| 2.3 | Si no se puede sincronizar, documentar por qué y cómo se mantienen alineados | Documento escrito |
| 2.4 | Test: ningún monto hardcodeado en el código | Test en verde |

**DoD del sprint:** cambiar un precio en `pricing.md` cambia lo que ve el cliente, sin tocar
código.

> 🔒 **`pricing.md` §10-15 son INTERNAS** (costos, márgenes, pisos). No se exponen nunca, por
> ninguna vía.

## Sprint 3 — Entrada desde el embudo *(P1)*

**Meta:** que un lead que responde no obligue a retipear nada.

| # | Tarea | Verificación |
|---|---|---|
| 3.1 | Endpoint para crear `Quote` desde un lead del Core | Un lead se convierte en cotización |
| 3.2 | Integración con `buscadorLeads` | Un lead del buscador llega acá sin retipear |
| 3.3 | El formulario público de `webiados.com` termina acá, no en un correo | Un envío del sitio crea una `Quote` |

**DoD del sprint:** `lead → cotización` sin intervención manual de datos.

## Sprint 4 — Medición *(P2)*

**Meta:** que este servicio sea la fuente de verdad del embudo comercial.

| # | Tarea | Verificación |
|---|---|---|
| 4.1 | Endpoint de métricas: enviadas, aceptadas, rechazadas, monto en pipeline | Devuelve datos reales |
| 4.2 | Tasa de cierre por período | Calculada, no estimada |
| 4.3 | Exponerlo al dashboard de agencia del Core | El dashboard lo consume |

**DoD del sprint:** se puede responder "¿cuántas cotizaciones envié este mes y cuántas cerré?"
sin abrir una planilla.

---

## Qué NO hacer

- **No reescribirlo.** Funciona, está desplegado, tiene Flyway, JWT y rate limiting. El problema
  es de adopción.
- **No agregar CRM, facturación ni gestión de proyectos.** Eso es el dashboard de agencia y vive
  en el Core. Acá se hacen cotizaciones.
- **No duplicar el dominio de cotizaciones en el Core.** El Core lo consume de acá.
- **No exponer endpoints sin auth** salvo el formulario público que ya existe.

## Reglas

1. Migraciones con Flyway, probadas sobre base vacía **y** sobre base con datos.
2. El dinero es entero. IVA 19%. Prohibido `float` para montos.
3. Los precios se leen de `pricing.md`. Nunca se hardcodean ni se improvisan en una reunión.
4. **No se despliega a producción sin autorización explícita de Felipe.**
