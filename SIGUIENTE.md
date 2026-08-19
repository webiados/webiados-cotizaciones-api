# SIGUIENTE — Cotizaciones API

> **Prioridad en el ecosistema: P0** · Decidido 2026-07-27
> Contexto global: `../Demos-Webiados-Clientes/CONTROL.md`
> Estrategia comercial: `../Demos-Webiados-Clientes/docs/estrategia_comercial.md`
> **Sin fechas: por orden.**

## Por qué este repo es P0

Es una de las **cuatro máquinas construidas y apagadas**. Este servicio está **vivo y
desplegado** (`cotizaciones-api-production-e0fb.up.railway.app`, Railway; V4 desplegada
2026-07-31) — y sin embargo las dos cotizaciones reales del mes, Macarena Larraín y la
pastelería Vientos del Sur, **se escribieron a mano en Markdown y se exportaron a PDF**.

> ℹ️ `cotiza.webiados.com` **ya resuelve** (verificado 2026-08-19): apunta por CNAME a
> Railway, o sea a **esta API** — devuelve JSON, no una pantalla. El panel y la landing siguen
> en `webiados.com` (`/admin` y `/cotizacion/{codigo}`), frontend Angular en `webiados/webiados`.

Eso significa tres cosas, todas malas: se pierde el historial comercial, se improvisan montos
fuera de `pricing.md`, y no hay forma de saber cuántas cotizaciones se enviaron ni cuántas se
cerraron. Sin ese dato no se puede medir la meta de **🎯 10 conversaciones reales al mes**.

**El trabajo acá no es construir. Es hacer que se use.**

---

## Qué hacer, en orden

### 1. Cargar las cotizaciones reales que existen *(P0)* — ⏳ listo, espera a Felipe

Payloads y script listos en `docs/carga-inicial/` (probados contra Postgres real). V4 ya está
desplegada, así que los endpoints existen en producción. **Felipe corre `cargar.sh`** (el modelo
no escribe en producción ni recibe credenciales). Estados en el modelo V4:

| Cliente | Estado | Dónde está el original |
|---|---|---|
| Macarena Larraín — Opción C, $380.000 + IVA | `SELECTED` | `../Demos-Webiados-Clientes/docs/Cotizaciones/` |
| Pastelería Vientos del Sur — 3 opciones (con mensualidad) | `SENT` | `../Demos-Webiados-Clientes/docs/Cotizaciones/Cotizacion_Pasteleria_VientosdelSur.md` |

**Criterio de verificación:** entrar al panel y ver las dos, con su estado correcto.

### 2. Que la próxima cotización nazca acá, no en Markdown *(P0)* — ✅ backend listo, falta desplegar el panel

El impedimento real **no** era la landing: era que el **panel no tenía botón de Enviar**. El
backend ya envía (V4: `POST /{id}/send` manda link+clave al cliente y marca `SENT`; más
`mark-sent`, `reject`, `precioMensual`). El **frontend** ya quedó enganchado en la rama
`feat/panel-envio-cotizaciones-v4` de `webiados/webiados` (Vercel) — **falta que Felipe autorice
su deploy** (mergear a `main` = producción).

**Criterio de verificación:** una cotización enviada a un cliente real desde el sistema.

### 3. Leer los precios de `pricing.md`, no tenerlos escritos acá *(P1)*

Regla dura de Webiados: **los precios se leen de `pricing.md`** vía `GET /api/v1/pricing` del
Core. **Replanteado** — ver `docs/SPRINT2_PRECIOS.md`: `Selection` es una bitácora, no un
catálogo; la entidad correcta es un `PriceItem` read-through del endpoint del Core.

**Nunca hardcodear un monto ni improvisarlo en una reunión.**

### 4. Recibir los leads del outbound *(P1)*

`../buscadorLeads/` genera leads y `../Demos-Webiados-Clientes/core/` los captura. Un lead que
responde tiene que poder convertirse en `Quote` sin recapturar los datos a mano.

**Criterio de verificación:** un lead del buscador termina como cotización sin retipear nada.

### 5. Alimentar el dashboard de agencia *(P2)*

Cuando exista el dashboard (fase 2, en el Core), este servicio es la fuente del embudo:
cotizaciones enviadas, aceptadas, rechazadas y monto en pipeline. **No duplicar ese dominio en
el Core** — se consume desde acá.

---

## Qué NO hacer

- **No reescribirlo.** Funciona, está desplegado y tiene Flyway, JWT y rate limiting. El problema
  es de adopción, no de código.
- **No agregar CRM, facturación ni gestión de proyectos acá.** Eso es el dashboard de agencia y
  vive en el Core. Este servicio hace cotizaciones.
- **No exponer nada sin auth** salvo el formulario público que ya existe.

## Reglas

1. Migraciones con Flyway, siempre. Probadas sobre base vacía y sobre base con datos.
2. El dinero es entero. IVA 19%. Prohibido `float` para montos.
3. No se toca producción sin autorización explícita de Felipe.
