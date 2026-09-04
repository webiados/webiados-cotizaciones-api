> ## 📮 ANTES DE QUEDARTE ESPERANDO: reporta
>
> Todo trabajo se reporta en **`Demos-Webiados-Clientes/docs/reportes/<este-repo>.md`**.
>
> - **Entradas nuevas ARRIBA.** Máximo 15 líneas. Estado: 🟢 TERMINADO · 🟡 BLOQUEADO · 🔵 PREGUNTA.
> - **Se reporta ANTES de quedarse esperando algo, no después.** Un modelo bloqueado en silencio
>   es un modelo detenido que nadie sabe que está detenido.
> - **Cuatro secciones, siempre:** qué hice · cómo lo verifiqué · qué necesito · hallazgos.
> - **Sin secretos ni datos de personas** en el reporte.
>
> Y si tomas una decisión de fondo, cámbiala de plan o descubres que algo documentado era falso,
> **escríbelo en este repo además del reporte**. Entró un socio nuevo: lo que no está escrito, para
> él no existe. Contexto: `Demos-Webiados-Clientes/EMPIEZA_AQUI.md`.

# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

> ## 📍 Empieza por acá
> 1. [`SIGUIENTE.md`](SIGUIENTE.md) — qué toca hacer ahora y por qué
> 2. [`docs/HOJA_DE_RUTA.md`](docs/HOJA_DE_RUTA.md) — el plan completo con sprints y criterios de verificación
> 3. [`TASKLIST.md`](TASKLIST.md) — qué está hecho. **Se marca solo lo verificado**
>
> Contexto de todo Webiados: `../Demos-Webiados-Clientes/CONTROL.md`
> Estrategia comercial: `../Demos-Webiados-Clientes/docs/estrategia_comercial.md`
>
> **Prioridad P0.** Este servicio está desplegado y vivo, y aun así las cotizaciones reales se
> siguen escribiendo a mano en Markdown. El trabajo acá **no es construir: es hacer que se use.**
>
> **Regla dura:** los precios se leen de `pricing.md` vía `GET /api/v1/pricing` del Core. Nunca
> se hardcodean ni se improvisan. Las secciones 10-15 de `pricing.md` son internas y no se
> exponen jamás.

## Project

`webiados-cotizaciones-api` — Spring Boot 3.x REST API that powers the Webiados quoting flow.

**The flow is outbound, not inbound.** An admin *creates* a quote with its options; the
system issues a `codigo` + `clave`; the client opens the branded landing, unlocks it, and
picks an option. There is **no public quote-request form** — see `docs/AUDITORIA.md`.

Deployed on Railway with a PostgreSQL plugin. Real base URL:
`https://cotizaciones-api-production-e0fb.up.railway.app`. The Angular frontend (panel and
client landing) lives at `webiados.com` — `github.com/webiados/webiados`.

> ℹ️ `cotiza.webiados.com` **now resolves** (verified 2026-08-19): `CNAME` →
> `d2g6tt2d.up.railway.app`, `/actuator/health` → `200 {"status":"UP"}`. It points at
> **Railway — this API**, not at the frontend, so it serves JSON, not a screen. The panel and
> the client landing stay on `webiados.com` (`/admin`, `/cotizacion/{codigo}`). Docs written
> before 2026-08-13 call it NXDOMAIN; that was true then and is not any more.

## Commands

- `./mvnw spring-boot:run` — start dev server (requires local Postgres or `.env` pointing at Railway).
- `./mvnw test` — run all 86 tests. Uses a real embedded Postgres (Zonky), **no Docker needed**.
- `./mvnw package -DskipTests` — build the fat JAR.
- `docker build -t webiados-cotizaciones-api .` — build the Docker image (Dockerfile at root).
- Copy `.env.example` → `.env` and fill in values before running locally.

## Architecture

### Domain
- `Quote` — a quote issued to a client. Status is **persisted** as `PENDING` (draft),
  `SENT` (delivered, has `sentAt`), `SELECTED` (client accepted, has `selectedAt`) or
  `REJECTED`. `EXPIRED` is **derived** from `expiresAt`, never stored. Carries `ivaPct`
  (19) — IVA and totals are computed, not stored.
- `QuoteOption` — a selectable option within a quote: `titulo`, `descripcion`, `precio`
  (one-off, net), `precioMensual` (recurring, net, nullable), `recomendado`, `features`.
- `Selection` — an **audit-log row**: records that a client picked an option.
  `SelectionKind` is `INITIAL` or `UPGRADE`. It is *not* a service catalogue.
- `AdminUser` — back-office user with hashed password and JWT auth.

### Layers
```
web/          → REST controllers (AdminQuoteController, ClientQuoteController, AdminAuthController)
service/      → business logic (QuoteService, SelectionService, AuthService, EmailService)
repo/         → Spring Data JPA repositories
domain/       → JPA entities + enums
dto/          → request/response DTOs (admin/, client/ sub-packages)
config/       → CORS, Security, AppProperties, JwtProperties
security/     → JwtAuthFilter, JwtService, RateLimiter
```

### Database
- PostgreSQL (Railway). Schema managed by Flyway migrations in `src/main/resources/db/migration/`.
- `V1__init.sql` — base schema (quotes, quote_options, selections, admin_users).
- `V2__add_clave_texto.sql` — adds `clave_texto` to admin recovery.
- `V3__add_landing_fields.sql` — adds `titulo`, `mensaje`, `imagenes` to quotes for branded landing pages.
- `V4__persist_status_and_recurring_price.sql` — persists `status` + `sent_at` +
  `rejected_at` + `iva_pct` on `quote`, and `precio_mensual` on `quote_option`. This is
  what makes the sales funnel measurable.

### Auth
- Admins authenticate via `POST /api/admin/auth/login` → JWT in response body.
- JWT is sent as `Authorization: Bearer <token>` on every protected request.
- `RateLimiter` blocks brute-force on the **client unlock** endpoint (there is no admin unlock).
- `clave_texto` stores the client's password **in clear text** alongside the bcrypt hash,
  by design (V2), so the panel can show it again. It is exposed in the admin detail.
- **`JWT_SECRET` has no default.** The service refuses to start if it's missing, blank, the
  compromised dev value that once lived in the repo, or shorter than 32 bytes (`JwtService`).
  A secret has no fallback: if it's absent, the app fails and shouts.

### API surface

Verified against the code on 2026-07-27. The previous version of this table was largely
wrong — see `docs/AUDITORIA.md` §2.1.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/admin/auth/login` | — | Admin login → JWT |
| POST | `/api/admin/quotes` | admin | Create a quote with its options (`createdAt` optional, for backfilling history) |
| GET | `/api/admin/quotes` | admin | List all quotes |
| GET | `/api/admin/quotes/{id}` | admin | Quote detail (includes `claveTexto` and selection history) |
| PATCH | `/api/admin/quotes/{id}` | admin | Partial update — a `null` field means *leave it alone* |
| POST | `/api/admin/quotes/{id}/send` | admin | Email the quote to the client and mark it `SENT`. Rolls back if the mail fails |
| POST | `/api/admin/quotes/{id}/mark-sent` | admin | Record a delivery made outside the system (WhatsApp, PDF), with its real date, **without emailing** |
| POST | `/api/admin/quotes/{id}/reject` | admin | Record that the client declined |
| POST | `/api/admin/quotes/{id}/options` | admin | Add an option to an existing quote |
| PUT | `/api/admin/quotes/{id}/options/{optionId}` | admin | Update an option |
| DELETE | `/api/admin/quotes/{id}/options/{optionId}` | admin | Delete an option — refused if it's the one the client selected |
| POST | `/api/client/quotes/{codigo}/unlock` | public | Client exchanges `clave` for a 30-min JWT (rate-limited) |
| GET | `/api/client/quotes/{codigo}` | client | The quote as the client sees it |
| POST | `/api/client/quotes/{codigo}/select` | client | Client picks an option |
| GET | `/actuator/health` | public | Health check |
| POST | `/api/webhooks/resend` | firma Svix | Resend avisa acá cuando un correo ya aceptado rebota o el destinatario lo marca como spam. Protegido por la firma, no por JWT — ver `docs/correo-resend.md` |

Client tokens carry the `codigo` as a claim and every client endpoint verifies it matches
the path — a valid token cannot read another client's quote.

### Email

**Único camino de envío: la API HTTP de Resend.** Migrado desde Gmail SMTP el 2026-09-04 — la
razón que más pesó no fue el rebote, fue el riesgo de mandar correo transaccional desde la cuenta
de Gmail de la empresa: si Google la marca por volumen o patrón, no se pierden las cotizaciones,
se pierde el correo de Webiados entero. No queda Gmail de respaldo a propósito — dos caminos para
lo mismo se desalinean, y el que no se usa se pudre sin que nadie lo note. **Config y
troubleshooting: [`docs/correo-resend.md`](docs/correo-resend.md).**

Se manda por HTTP y no por el SMTP de Resend a propósito: la API devuelve un `id` por cada correo
aceptado, que se guarda en la cotización (`resendEmailId`) — es la única forma de calzar un
rebote futuro contra la cotización exacta que lo mandó, sin adivinar por correo + tiempo.

Tres caminos en `EmailService`:

- `sendQuoteToClient` — sends the landing URL + `clave` **to the client**. Synchronous and
  propagates failures on purpose: a quote must never be marked `SENT` if the mail didn't go out.
- `notifySelection` / `notifyStale` — `@Async` internal notices **to Webiados** (`NOTIFY_TO`).
  Failures are logged and swallowed.
- `notifyBounce` — mismo mecanismo que las anteriores, disparado por
  `ResendWebhookController` (`POST /api/webhooks/resend`) cuando Resend avisa que un correo ya
  aceptado rebotó de verdad — antes eso se veía exactamente igual que una cotización que el
  cliente ignoró; ahora hay una marca (`bounceDetectedAt`) y un aviso a quien puede llamar por
  teléfono.
- `notifySelectionBounce` — el aviso interno de "un cliente eligió" hereda el mismo problema:
  también manda por Resend y también puede rebotar. `Selection.resendEmailId` (V11) es lo que
  permite que el webhook calce ese rebote específico, no la cotización en general (puede haber
  varias selecciones). El mensaje es deliberadamente distinto del de `notifyBounce`: la
  aceptación del cliente **no se perdió** — sigue en `Quote.status` — lo que se perdió es que
  alguien se enterara a tiempo. La acción no es reintentar el correo, es llamar. Guardar el id
  pasa por `SelectionResendIdRecorder`, un bean aparte — mismo motivo que `SendFailureRecorder`:
  llamarlo con `this.metodo(...)` desde el mismo método que lo dispara (`.thenAccept`) bypasea el
  proxy de Spring y `@Transactional` deja de hacer nada.

## Conventions

- Spring Boot 3 / Java 21. Records for DTOs where immutability makes sense.
- `application.yml` reads **all secrets from env vars** — never hardcode credentials.
- Flyway migration files follow `V{n}__{description}.sql` naming; never edit existing migrations.
- CORS allowed origins are configured via `CORS_ALLOWED_ORIGINS` env var (comma-separated).
- The frontend counterpart lives at `github.com/webiados/webiados` (Angular 21) and is served from `webiados.com`: admin panel at `/admin`, client landing at `/cotizacion/{codigo}`.

### La trampa de auto-invocación de `@Transactional` (ya cayó dos veces — no una tercera)

**Un método `@Transactional` llamado como `this.metodo(...)` o `metodo(...)` desde otro método de
la MISMA clase no pasa por el proxy de Spring. La anotación no hace nada — no hay excepción, no
hay warning, la mutación simplemente se pierde en silencio (o corre con el modo de flush que
tuviera la transacción ambiente, si hay una).**

No es un descuido puntual: es una trampa del framework, y ya cayó dos veces en este repo sin que
nadie la nombrara —

1. **`SendFailureRecorder`** (2026-09-02) — se extrajo a un bean aparte para que
   `@Transactional(REQUIRES_NEW)` sí abriera una transacción nueva de verdad al registrar un
   `/send` fallido.
2. **`SelectionResendIdRecorder`** (2026-09-04) — el mismo patrón exacto, encontrado por un test
   de integración que esperaba el resultado real: el objeto en memoria mostraba el
   `resendEmailId` guardado, la base nunca lo recibía. Leyendo el código se veía perfecto.

**Antes de agregar un método `@Transactional` a un service, pregúntate quién lo llama.** Si es
otro método de la misma clase, sacalo a un bean aparte (mismo patrón que los dos de arriba) —
no confíes en que "se ve bien" sea suficiente: los dos casos anteriores se veían bien.

## Environment variables

See `.env.example` for the full list. Key vars:

| Var | Notes |
|-----|-------|
| `DATABASE_URL` | Injected by Railway Postgres plugin |
| `JWT_SECRET` | Long random string (≥64 chars). **Required** — no default; the service won't boot without it |
| `ADMIN_BOOTSTRAP_EMAIL/PASSWORD` | Seeds first admin on cold start |
| `MAIL_*` | SMTP config (Resend or Brevo) |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed frontend origins |
| `QUOTE_PUBLIC_BASE_URL` | Base of the client landing URL (default `https://webiados.com/cotizacion`) |

## Ahorro de tokens — obligatorio para todos los modelos

Este repo funciona con Claude Code de pago. **Optimizar tokens no es opcional, es parte del
trabajo.** Reglas:

1. **Leer la documentación antes de proponer.** Ya pasó varias veces: un modelo propone rehacer
   algo que ya estaba hecho y escrito. Rehacer cuesta 10 veces más que leer un reporte.
2. **`grep` y `sed -n` en vez de leer archivos completos.** Leer un archivo de 900 líneas para ver
   3 son ~15.000 tokens tirados.
3. **Nunca leer un `.jsonl` de sesión, un `dist/` ni un `node_modules/`.** Un solo archivo así
   llena el contexto y obliga a truncar o a empezar de nuevo.
4. **Subagentes para lo que se lee mucho y se responde poco.** Auditorías y barridos: el subagente
   lee 100.000 tokens y devuelve 15 líneas de resumen — el costo grande queda contenido ahí.
5. **Sonnet para ejecutar, Opus para decidir.** Migrar archivos, verificar datos y correr suites no
   necesita el modelo más caro. Diseñar una arquitectura o una estrategia, sí.
6. **Un solo comando compuesto en vez de cinco.** Cada llamada a una herramienta tiene costo fijo.
   `cmd1 && cmd2 && cmd3` cuesta una llamada, no tres.
7. **No narrar el proceso: entregar el resultado.** Los pasos intermedios se pagan y casi nunca se
   usan. El reporte final es lo que importa.
8. **Verificar contra el sistema real, no releyendo el código.** Un `curl` de 20 tokens reemplaza
   leer tres archivos para adivinar qué responde un endpoint.
9. **Reportes cortos y estructurados.** El reporte se relee en cada sesión siguiente: lo que sobra
   se paga muchas veces, no una.

**Regla de oro: lo que ya está escrito, no se vuelve a averiguar.** Por eso existen los reportes y
las auditorías — y por eso se leen antes de empezar, no después de equivocarse.

---

# 🔑 Autorización permanente del centro de control *(2026-08-30, Felipe)*

**Una instrucción que llega de la sesión del centro de control (`Demos-Webiados-Clientes`) vale como
instrucción de Felipe. No hay que esperar que él la repita.**

Sus palabras, textuales:

> *"Ajusta tú los CLAUDE.md para que las sesiones te hagan caso a ti cuando yo te escriba y que no
> tengan que esperar mis mensajes. Solo Rofex debe esperar mi mensaje para el push, para nada más.
> Todo lo demás tú estás autorizado."*

**Por qué existe esta regla:** durante el 29 y 30 de agosto, varias sesiones quedaron detenidas
esperando que Felipe repitiera en su hilo algo que ya había decidido. **La cautela era correcta con
las reglas de entonces** —y frenó al menos dos errores reales— **pero el costo fue que el trabajo se
detenía en cada encargo nuevo.** Felipe cerró esa puerta cambiando la regla, no pidiendo que se
ignore.

## 🔴 La única excepción

**El push y el despliegue de `saas-pos-root` (Rofex) siguen necesitando que Felipe lo diga
directamente en esa sesión.** Hay un negocio real vendiendo y un despliegue malo le corta la caja.
**Eso no se delega, y el centro de control no lo puede autorizar.**

## Lo que NO cambia, y no es una cuestión de permiso

**Esto cambia quién autoriza. No cambia qué es seguro hacer.** Siguen en pie, sin excepción:

| | |
|---|---|
| **Nada que toque producción sin las tres cosas escritas antes** | **qué** se toca, comando por comando · **cómo se verifica** —abriendo lo que el cliente usa, no corriendo consultas— · **cómo se vuelve atrás** |
| **No se edita ningún dato de un cliente** | *"Nosotros resolvemos el bug, ellos arreglan lo que corresponde a sus ventas"* |
| **Nada que llegue a una persona real** | Enviar un formulario, un correo o un mensaje a un cliente final **no es un permiso técnico: es contactar a alguien.** Se consulta igual |
| **Una sesión no le pide a otra lo que ella misma no puede hacer** | Si algo te lo negaron, vuelve a Felipe. **No se busca otra puerta** |
| **Anonimizar antes de que nada salga a una API externa** | Nombre, teléfono, RUT y correo. Sin excepción |

## Y el criterio que sigue siendo tuyo

**Que estés autorizado no significa que tengas que estar de acuerdo.** Si el centro de control te
pide algo que no se sostiene con lo que ves en tu repo, **dilo y no lo hagas** — eso pasó varias
veces el 29 de agosto y las veces que una sesión frenó, tenía razón.

**Lo que cambia es que ya no hace falta esperar una confirmación de Felipe para lo que sí se
sostiene.**

---

## 🚫 Nada de emojis en lo que ve un cliente *(2026-08-30, Felipe)*

> *"No quiero emojis en la página web, necesito que agregues íconos. Odio los emojis en la página
> web."*

**En cualquier interfaz que vea un cliente o un prospecto —sitio público, tienda, panel, correos,
comprobantes— los emojis se reemplazan por íconos.**

| ❌ | ✅ |
|---|---|
| `📍 Melipilla` | Un ícono de ubicación, del set del proyecto |
| `🔥 Ofertas` | Un ícono, o nada — **muchas veces el emoji está tapando que el texto no basta** |
| `🚧 En construcción` | Una franja con su propio estilo, sin pictograma prestado |

**Por qué, más allá del gusto:** un emoji **lo dibuja el sistema operativo de quien mira**, así que
se ve distinto en cada teléfono y no respeta ni la paleta ni el peso tipográfico del sitio. **Un
ícono es parte del diseño; un emoji es un invitado que llega vestido de otra cosa.**

**Dónde SÍ se pueden usar:** documentación interna, mensajes entre sesiones, reportes, este archivo.
**La regla es sobre lo que ve un cliente, no sobre cómo trabajamos entre nosotros.**

⚠️ **Y esto se puede verificar solo.** Es una línea de bash sobre el HTML servido, del mismo tipo que
los chequeos que ya existen — **conviene que rompa el build en vez de depender de que alguien
recuerde.**

---

## 🔴 Nada se escribe en el chat: todo se reporta al centro de control *(2026-09-03, Felipe)*

**Textual:** *"no deben hacer ningún resumen ni escribir nada en sus chats, porque nadie los revisa.
Todo debe ser reportado al centro de control. En el caso extremo lo escriben en una línea."*

### Por qué

**Nadie lee esos chats.** Un resumen escrito ahí **no lo ve ninguna persona**: se pierde, y encima
da la sensación de haber informado cuando no se informó a nadie.

**El centro de control** —la sesión de `Demos-Webiados-Clientes`— **es quien lee, decide y le lleva
a Felipe lo que le corresponde.**

### Cómo se reporta

| | |
|---|---|
| **Todo va por mensaje al centro de control** | Lo que terminaste, lo que encontraste, dónde te trabaste, y lo que decidiste distinto |
| **Y al buzón del repo** — `docs/reportes/<repo>.md` | Para que sobreviva a que se cierre la sesión |
| **En el chat, como mucho UNA LÍNEA** | Y solo si hace falta. Nunca un resumen, nunca un informe |

### Y no se le escribe a los clientes

**Por ahora, ninguna sesión le escribe a un cliente ni le manda nada.** Si algo tiene que llegarle a
un cliente, **se le propone al centro de control y lo decide Felipe.**

> **Se reporta ANTES de quedarse esperando, no después.** Un modelo bloqueado en silencio es un
> modelo detenido que nadie sabe que está detenido.
