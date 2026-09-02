# Auditoría real del código — Cotizaciones API

> Hecha el 2026-07-27, antes de tocar nada. Hasta ahora solo se había revisado la
> documentación. Esto es lo que dice **el código**.
>
> Método: lectura completa de los 42 archivos Java, las 3 migraciones, `application.yml`
> y `pom.xml`, más sondeo en vivo de producción y DNS. Todo lo afirmado acá está
> verificado; lo que no pude verificar está marcado como tal.

## Resumen en una línea

El servicio está **vivo, bien construido y correctamente asegurado** — pero está
desplegado en **otra URL** de la que dice la documentación, **no tiene tests**, y su
modelo de datos **no puede representar una cotización de Webiados**: no existe el estado
"enviada", no existe el IVA, no existe el precio mensual, y **nunca le manda nada al
cliente**. Por eso las cotizaciones reales se siguen escribiendo en Markdown.

---

## 1. Estado del despliegue (tarea 1.1)

| Qué | Resultado |
|---|---|
| `cotiza.webiados.com` | ❌ **NXDOMAIN — el dominio no existe** · ⏩ *arreglado: ver la nota al pie de esta sección* |
| `cotizaciones-api-production-e0fb.up.railway.app/actuator/health` | ✅ `{"status":"UP"}` (200) |
| `GET /api/admin/quotes` sin token | ✅ 401 `{"title":"No autenticado"}` |
| `POST /api/admin/auth/login` con body vacío | ✅ 400 con validación en español |
| Panel admin `webiados.com/admin/login` | ✅ 200 |
| Landing cliente `webiados.com/cotizacion/{codigo}` | ✅ 200 (Angular, SSR/CSR) |
| `mvn compile` | ✅ BUILD SUCCESS |

**La URL real del backend es `https://cotizaciones-api-production-e0fb.up.railway.app`.**
No la deduje: está declarada en el `content-security-policy` del frontend en producción
(`connect-src ... https://cotizaciones-api-production-e0fb.up.railway.app`).

`cotiza.webiados.com` aparece como URL del servicio en `CLAUDE.md:22`, `CLAUDE.md:84`,
`SIGUIENTE.md:11`, `docs/HOJA_DE_RUTA.md:25` y en el default de `CORS_ALLOWED_ORIGINS`
(`application.yml:48`). **Ese subdominio no está en DNS.** El sistema real vive todo bajo
`webiados.com`: panel en `/admin`, landing del cliente en `/cotizacion/{codigo}`.

> **Actualización 2026-08-19 — esto ya no es cierto.** El DNS se creó: `cotiza.webiados.com`
> resuelve por `CNAME` a `d2g6tt2d.up.railway.app` y `/actuator/health` responde
> `200 {"status":"UP"}`. Se deja el hallazgo original arriba porque una auditoría es una foto
> con fecha, no un documento vivo. **Ojo con el matiz:** el subdominio quedó apuntando a
> **Railway (esta API)**, no al frontend en Vercel — abrirlo en el navegador devuelve JSON. El
> panel y la landing siguen en `webiados.com`. Y sigue sin existir formulario público (§2.1).

> ✅ **1.1 se puede dar por verificada** en cuanto a que el servicio responde y el panel
> abre. Lo que **no** pude verificar es el login efectivo: no tengo credenciales de admin
> (no hay `.env` en el repo). Ver "Qué necesito de Felipe".

---

## 2. La documentación describe un sistema distinto al que existe

Esto importa porque los sprints 2, 3 y 4 están planificados sobre la descripción, no
sobre el código.

### 2.1 La tabla de endpoints de `CLAUDE.md` es casi toda incorrecta

| `CLAUDE.md` dice | Realidad en el código |
|---|---|
| `POST /api/quotes` — cliente envía solicitud | **No existe.** Ningún endpoint público crea cotizaciones |
| `GET /api/quotes/{code}` — público | Es `GET /api/client/quotes/{codigo}` y **exige JWT de cliente** |
| `GET /api/admin/quotes/{id}` | ✅ existe |
| `PUT /api/admin/quotes/{id}` | Es **`PATCH`**, no `PUT` |
| `POST /api/admin/auth/unlock` | **No existe.** El unlock es del *cliente*: `POST /api/client/quotes/{codigo}/unlock` |
| `GET /api/selections` — lista servicios | **No existe.** Ningún endpoint expone un catálogo |

Endpoints que existen y no están documentados: `POST /api/admin/quotes`,
`PUT /api/admin/quotes/{id}/options/{optionId}`,
`DELETE /api/admin/quotes/{id}/options/{optionId}`,
`POST /api/client/quotes/{codigo}/select`.

### 2.2 El flujo real es el inverso del documentado

La documentación describe *inbound*: el cliente llena un formulario público y el admin
lo revisa. **El código hace lo contrario**, y hace bien:

1. El **admin crea** la cotización (`POST /api/admin/quotes`) con sus opciones.
2. El sistema genera un `codigo` (10 chars) y una `clave` (10 chars). La clave se
   devuelve **en texto plano una sola vez** en la respuesta.
3. Felipe le pasa al cliente la URL + la clave, **por su cuenta**.
4. El cliente entra a `/cotizacion/{codigo}`, ingresa la clave (`/unlock`), obtiene un
   JWT de 30 min y **elige una opción** (`/select`).
5. Esa elección le manda un correo **a Webiados** (`NOTIFY_TO`), no al cliente.

Esto es un modelo de **propuesta con selección del cliente**, que es exactamente lo que
Webiados necesita. El problema no es el diseño: es que le faltan piezas para reemplazar
al Markdown.

### 2.3 `Selection` no es lo que dice la documentación

`CLAUDE.md` dice: *"`Selection` — a pre-defined service card the client picks from the
public form (`SelectionKind`: WEB, SOFTWARE, ECOMMERCE, etc.)"*.

En el código, `Selection` es una **fila de bitácora**: registra que un cliente eligió una
opción, con `quote`, `option`, `kind` y `createdAt`. Y `SelectionKind` tiene exactamente
dos valores: **`INITIAL` y `UPGRADE`**. No hay catálogo de servicios en ninguna parte.

**Consecuencia directa sobre el Sprint 2:** la tarea 2.2 dice "sincronizar `Selection`
contra el endpoint de precios del Core". Eso **no se puede hacer**, porque `Selection` no
es un catálogo — es un log de auditoría. Sincronizarlo no significa nada. El Sprint 2
tiene que replantearse (ver §5).

### 2.4 `QuoteStatus` no tiene los estados que todos los documentos asumen

Documentado en `CLAUDE.md`, `SIGUIENTE.md`, `HOJA_DE_RUTA.md` y `TASKLIST.md`:
`PENDING → REVIEWED → SENT → ACCEPTED / REJECTED`.

En el código (`QuoteStatus.java`, 10 líneas):

```java
public enum QuoteStatus { PENDING, SELECTED, EXPIRED }
```

Y además **no se persiste**. Es un valor **derivado** que se calcula en cada lectura a
partir de `selectedOptionId` y `expiresAt`:

```java
public QuoteStatus statusAt(Instant now) {
    if (selectedOptionId != null) return QuoteStatus.SELECTED;
    return now.isAfter(expiresAt) ? QuoteStatus.EXPIRED : QuoteStatus.PENDING;
}
```

Esto es el impedimento #1. Está desarrollado abajo.

### 2.5 Otras cosas que la documentación afirma y no son ciertas

- **`./mvnw` no existe.** No hay wrapper ni carpeta `.mvn`. Los tres comandos de la
  sección "Commands" de `CLAUDE.md` fallan tal como están escritos. (`mvn` global sí
  funciona: probado, compila.)
- **No hay un solo test.** `src/test/` no existe. `pom.xml` declara
  `spring-boot-starter-test`, `spring-security-test` y `h2` — dependencias listas, sin
  usar. `./mvnw test` no puede pasar ni fallar: no hay nada que correr.
- **`V2__add_clave_texto.sql` no es "admin recovery"** como dice `CLAUDE.md:56`. Guarda
  la clave **del cliente en texto plano** (`clave_texto`), en paralelo al hash bcrypt,
  para que Felipe pueda releerla en el panel. Es una decisión deliberada y razonable para
  el caso de uso, pero la documentación la describe mal y conviene que quede explícito
  que esas claves están en claro en la base.
- **El correo no se manda "on new quote submission and on status change to SENT"**
  (`CLAUDE.md:78`). `EmailService` tiene **un solo método**, `notifySelection`, que se
  dispara cuando el cliente elige. Ver impedimento #2.

---

## 3. Impedimentos para que la próxima cotización salga de acá (tarea 1.4)

Ordenados por cuánto bloquean. Los tres primeros son la razón por la que el Markdown
ganó.

### 🔴 #1 — No existe el estado "enviada". Literalmente no se puede registrar.

`QuoteStatus` solo tiene `PENDING`, `SELECTED` y `EXPIRED`, y se **deriva**, no se
guarda. No hay forma de decir "esta cotización se la mandé al cliente el martes".

Efecto concreto sobre lo que me pediste:

- **Macarena → `ACCEPTED`**: alcanzable *de facto*. Si el cliente elige la Opción C, la
  cotización queda `SELECTED`, que es semánticamente lo mismo. Se puede lograr por API
  (crear → unlock con la clave → select). Lo dejé automatizado.
- **Vientos del Sur → `SENT`**: **imposible hoy.** Queda `PENDING`, indistinguible de una
  cotización recién creada que nadie mandó.

Y esto es exactamente lo que rompe la meta del ecosistema: el embudo del Sprint 4
("cuántas envié y cuántas cerré") **no se puede calcular** si "enviada" no existe como
dato. `PENDING` mezcla *borrador* con *enviada y esperando*.

También se pierde `REJECTED`: si el cliente dice que no, no hay dónde anotarlo. Y la
cotización queda `PENDING` hasta que expira sola.

### 🔴 #2 — El sistema nunca le manda nada al cliente. Felipe sigue enviando a mano.

`EmailService` tiene un único método, `notifySelection`, y su destinatario es fijo:

```java
message.setTo(props.mail().notifyTo());   // contacto@webiados.com
```

O sea: el correo se manda **a Webiados cuando el cliente ya eligió**. En ningún momento
del flujo el sistema le escribe al cliente.

Peor: `clientEmail` es **opcional** en la creación (`CreateQuoteRequest` no lo valida) y
solo se usa para *mostrarlo* en el panel y meterlo en el cuerpo del correo interno.

**Esto es el corazón del problema de adopción.** El flujo real hoy obliga a Felipe a:

1. crear la cotización en el panel,
2. copiar a mano el `codigo` y la `clave`,
3. armar el mensaje,
4. mandarlo por WhatsApp o correo por su cuenta.

Comparado con "escribo el Markdown y exporto a PDF", el sistema **agrega** trabajo en vez
de quitarlo. Mientras eso siga así, el Markdown va a seguir ganando, y con razón.

### 🔴 #3 — No hay IVA, ni total, ni precio mensual. El modelo no representa una cotización de Webiados.

`QuoteOption` tiene: `titulo`, `descripcion`, `precio`, `currency`, `recomendado`,
`features`. Eso es todo. No existe:

- **IVA ni total.** Las dos cotizaciones reales muestran "TOTAL LÍQUIDO" y "TOTAL +IVA
  (19%)" en cada opción. La regla del repo dice "IVA 19%" — pero no hay ni un campo ni un
  cálculo de IVA en todo el código. Verificado con
  `grep -rniE "\biva\b|impuesto|\btax\b|total" src/`: cero coincidencias reales.
- **Precio mensual / recurrente.** Vientos del Sur cotiza $49.000 y $74.000 al mes;
  Macarena, $25.000 y $50.000. `precio` es **un solo número**. No hay `mensual`.
  Y el Core ya modela esto: su endpoint devuelve `{setup, mensual}` por add-on.
- **Modalidad alternativa.** Vientos del Sur ofrece "sin mensualidad (compra total)" con
  otro precio por cada opción ($2.200.000 / $2.600.000 / $3.450.000). No hay dónde.
- **Agregados opcionales no cotizados ahora** (el canal B2B de Vientos del Sur:
  $350.000 + $15.000/mes, "no se cobra ahora").

Al cargar las dos cotizaciones tuve que **meter todo eso como texto libre** dentro de
`mensaje`, `descripcion` y `features`. Funciona para que se vea, pero significa que
**esos montos no son datos**: no se pueden sumar, ni comparar con `pricing.md`, ni
alimentar el pipeline del Sprint 4. Es Markdown adentro de una base de datos.

### 🟠 #4 — No hay PDF, y el PDF es lo que Felipe entrega hoy

Lo que hoy se manda es un PDF con la marca Webiados (el de Macarena tiene portada,
tipografía propia e ilustración). Lo que el sistema ofrece es un **link con contraseña**.

No es solo estética: son dos productos distintos. El PDF se reenvía, se imprime, se
adjunta y se guarda; el link exige que el cliente entre y tipee una clave de 10
caracteres. Para una pastelería es fricción real.

`V3__add_landing_fields.sql` (`titulo`, `mensaje`, `imagenes`) apunta a resolver esto por
el lado de la landing, y es el camino correcto. Pero **la landing no la genera este
servicio** — la renderiza el Angular en `webiados.com/cotizacion/{codigo}`, que vive en
otro repo (`github.com/webiados/webiados`). No pude auditar cómo se ve. **Esa evaluación
es el siguiente paso obligatorio de 1.5** y no se puede hacer desde este repo.

### 🟠 #5 — Los precios no salen de `pricing.md`, y ya se están improvisando

Verificado contra `../Demos-Webiados-Clientes/docs/pricing.md`:

| | `pricing.md` | Lo cotizado a Vientos del Sur |
|---|---|---|
| Kit Tienda — instalación | **$890.000** | **$1.040.000** (armado a mano: 640k + 250k + 150k) |
| Kit Tienda — mensual | **$49.000** | $49.000 ✅ |

El mensual coincide; **la instalación no**. La cotización se armó por líneas sueltas
fuera del catálogo. Eso es exactamente lo que la regla dura busca evitar, y ya pasó — dos
veces, porque la de Macarena (WordPress: $150.000 / $260.000 / $380.000, mantención
$25.000 / $50.000) tampoco corresponde a ningún kit ni add-on de `pricing.md`.

**El servicio no consume `GET /api/v1/pricing` en ninguna parte.** No hay cliente HTTP, no
hay `RestClient`, no hay `WebClient`. Los precios se escriben a mano en el panel, uno por
uno. Nada impide teclear cualquier cifra.

Estado del lado del Core (verificado): `core/src/endpoints/pricing.ts` **existe y
funciona**, parsea `pricing.md` y devuelve `{addons, moneda:'CLP', incluyeIva:false}`.
También hay `parsearKit()` para los kits. O sea: **la fuente ya está lista**; falta el
consumidor.

> 🔒 **Sobre las secciones 10-15 (internas):** verifiqué que el parser del Core
> (`parsearAddons`) solo reconoce filas con slug en backticks, y que **no hay ninguna
> tabla con ese formato debajo de la línea 338** de `pricing.md` (donde empieza
> `# 🔒 PARTE INTERNA — NO PUBLICAR`). Confirmado: hoy el endpoint **no filtra por
> sección**, pero **de hecho no expone nada interno**. Es una garantía frágil —depende de
> que nadie agregue una tabla con slugs en la parte interna—, pero hoy no hay fuga.
> Cuando se implemente el Sprint 2 conviene cortar el markdown en la línea 338 antes de
> parsear, para que la garantía sea estructural y no accidental.

### 🟡 #6 — No se puede cargar el histórico con sus fechas reales

`createdAt` se fija a `Instant.now()` dentro de `QuoteService.create()` y no hay forma de
sobrescribirlo: `UpdateQuoteRequest` solo permite tocar `expiresAt`.

Las dos cotizaciones reales son del **24 y 25 de julio**. Al cargarlas van a quedar
fechadas **hoy**. Para un histórico cuyo propósito es medir el embudo, la fecha es el
dato. Sin backdating, el "histórico" nace falseado.

### 🟡 #7 — Bugs concretos en el camino de edición del admin

Estos son defectos del código, no limitaciones de diseño:

1. **`PATCH` borra los campos que no le mandas.** `Quote.updateMeta()` asigna sin
   comprobar nulos:

   ```java
   this.titulo = titulo;   // si viene null, borra el título
   this.mensaje = mensaje; // idem
   this.notes = notes;     // idem
   ```

   Un `PATCH {"notes":"llamé al cliente"}` **vacía `titulo` y `mensaje`**. En un endpoint
   `PATCH` eso es semánticamente incorrecto y destruye datos en silencio. (`expiresAt` sí
   se comprueba — el patrón correcto ya está ahí, dos líneas más abajo.)

2. **`imagenes` no se puede editar nunca.** `UpdateQuoteRequest` no tiene el campo. Se
   puede escribir solo al crear. La migración V3 lo agregó justamente para las landings
   de marca, y el camino de edición lo dejó afuera.

3. **No hay forma de agregar una opción a una cotización existente.** Hay `PUT` y
   `DELETE` por opción, pero el único `POST` crea la cotización entera. Si Felipe quiere
   sumar una Opción D, tiene que rehacer la cotización — y con eso cambia el `codigo` y la
   `clave` que ya le mandó al cliente.

4. **Borrar la opción elegida borra la historia sin avisar.** `deleteOption` no comprueba
   si la opción es la `selectedOptionId`. En base de datos, `selection` tiene
   `ON DELETE CASCADE` sobre `option_id` (`V1__init.sql`), así que **se borran también las
   filas de bitácora** de esa elección; y la FK `fk_quote_selected_option` es
   `ON DELETE SET NULL`, de modo que la cotización vuelve a `PENDING`. Resultado: una
   cotización aceptada puede volver a "pendiente" y perder el registro de que fue
   aceptada, con un solo `DELETE`.

### 🟡 #8 — Cosas menores pero que conviene anotar

- **`unlock` traga cualquier excepción.** `ClientQuoteController` envuelve todo en
  `catch (Exception ex)` y responde 401. Está pensado para no filtrar si el código existe
  —correcto— pero también convierte una caída de base de datos en "clave incorrecta", y
  no deja rastro en el log. Un cliente legítimo vería "clave incorrecta" durante un
  incidente.
- **El rate limit no distingue clientes detrás del proxy.** La clave es
  `codigo + ":" + httpReq.getRemoteAddr()`, y no está configurado
  `server.forward-headers-strategy`. En Railway, `getRemoteAddr()` devuelve la IP del
  proxy, no la del cliente. Como el `codigo` va en la clave, la protección contra fuerza
  bruta **sigue siendo efectiva** (5 intentos por cotización cada 10 min); el efecto
  secundario es que un atacante puede dejar fuera al cliente legítimo de *esa* cotización.
  Riesgo bajo, arreglo de una línea.
- **El rate limit es en memoria** (Caffeine). Con una sola instancia en Railway está
  bien; se pierde en cada redeploy y no serviría con réplicas.
- ~~**`JWT_SECRET` tiene un default en `application.yml`.**~~ **Verificado y resuelto el
  2026-07-27.** Ver la caja de abajo.

> ### 🔓 `JWT_SECRET`: verificado en producción, y corregido
>
> **La verificación (read-only):** forjé un token de admin firmado con el default que
> estaba en `application.yml` (`change-me-in-prod-…`), confirmé que es un token válido
> —se parsea solo, `scope=admin`— y lo mandé contra `GET /api/admin/quotes` en
> producción. **Producción devolvió 401**, igual que sin token. Conclusión: **producción
> NO usa el default**; tiene su propio `JWT_SECRET`. No hay forma de firmar tokens de
> admin con el secreto público. No hay incidente activo.
>
> **El riesgo latente, igual:** mientras el default viviera en el código, el próximo
> deploy que olvidara la variable arrancaría en silencio con un secreto público, y
> cualquiera podría firmar un token de admin y entrar al panel.
>
> **La corrección:** un secreto no tiene default. Se quitó de `application.yml`
> (`secret: ${JWT_SECRET}`, sin fallback) y `JwtService` valida al arrancar: si
> `JWT_SECRET` falta, está en blanco, es el valor comprometido que quedó en git, o es más
> corto que 32 bytes, **el contexto de Spring no levanta y el servicio no arranca**.
> Probado de dos formas: unit test del validador, y arrancando el JAR real sin la
> variable (falla con "JWT_SECRET no está definido"). El default comprometido, además,
> queda en una denylist explícita por si alguien lo reusa a mano.

## 4. Lo que está bien y no hay que tocar

Para que quede dicho, porque es la mayoría del código:

- **La seguridad está bien hecha.** JWT con scopes separados (`admin` / `client`), TTL
  corto para el cliente (30 min), bcrypt fuerza 12, sesiones stateless, CORS por variable
  de entorno, y —lo más importante— el token de cliente lleva el `codigo` como claim y
  cada endpoint **verifica que coincida con el del path**. No se puede leer la cotización
  de otro con un token válido. Está bien pensado.
- **El 401 vs 403 está resuelto** (fue el trabajo de los últimos tres commits) y lo
  confirmé en producción.
- **El unlock no filtra existencia**: misma respuesta para "no existe" y "clave mala".
- **`ddl-auto: validate`** — el esquema lo manda Flyway, no Hibernate. Correcto.
- **`open-in-view: false`** — bien; y las consultas usan `findWithOptionsById` para evitar
  N+1.
- **El dinero es `BigDecimal`/`NUMERIC(14,2)`**, nunca `float`. Cumple la regla.
- **El manejo de errores es coherente** (`ProblemDetail`, RFC 7807, mensajes en español).
- **El actuator expone solo `health`**, con `show-details: never` y el health de mail
  excluido del agregado.

Coincido con la hoja de ruta: **no hay que reescribir esto.** Hay que completarlo.

---

## 5. Qué significa esto para los sprints que vienen

- **Sprint 1 (1.5)** — el trabajo real es, en orden: (a) estado `SENT`/`REJECTED`
  persistido, (b) que el sistema le mande el correo al cliente, (c) IVA y total como
  datos. Sin (a) y (b) la próxima cotización va a volver al Markdown.
- **Sprint 2** — la tarea 2.2 tal como está escrita ("sincronizar `Selection`") **no es
  ejecutable**: `Selection` es una bitácora, no un catálogo. Lo que corresponde es un
  cliente HTTP contra `GET /api/v1/pricing` del Core que alimente el panel al momento de
  armar la cotización, con los montos precargados desde `pricing.md` y no tecleados.
  Además hay que agregar `mensual` al modelo: el Core ya devuelve `{setup, mensual}` y
  acá no hay dónde ponerlo.
- **Sprint 3 (3.3)** — "el formulario público de webiados.com termina acá" está redactado
  como una integración, pero **el endpoint público de captura no existe**. Es construcción
  desde cero, no cableado.
- **Sprint 4** — depende por completo del impedimento #1. Sin `SENT` persistido con su
  fecha, la tasa de cierre no es calculable.

---

## 5.bis Qué se resolvió después de esta auditoría (2026-07-27)

Trabajo hecho tras la auditoría, todo verificado con tests (`mvn test` → **53 en verde**,
Postgres real embebido, sin Docker).

| Impedimento | Estado |
|---|---|
| #1 estado `SENT`/`REJECTED` persistido, con fecha de envío | ✅ resuelto (V4) |
| #2 el sistema le manda la cotización al cliente | ✅ resuelto (`POST /{id}/send`) |
| #3 IVA y total como datos | ✅ resuelto (calculados, desglosados en la API) |
| #3 precio mensual / recurrente | ✅ resuelto (`precio_mensual` por opción) |
| #3 modalidad "sin mensualidad" y agregados B2B | ❌ **sigue abierto** (texto libre) |
| #4 PDF / evaluación de la landing | ❌ **sigue abierto** (vive en otro repo) |
| #5 precios desde `pricing.md` | ❌ **sigue abierto** (es el Sprint 2) |
| #6 backdating del histórico | ✅ resuelto (`createdAt` al crear, `mark-sent` con fecha) |
| #7.1 PATCH que borraba campos | ✅ resuelto (null = "no tocar") |
| #7.2 `imagenes` no editable | ✅ resuelto |
| #7.3 no se podía agregar una opción | ✅ resuelto (`POST /{id}/options`) |
| #7.4 borrar la opción elegida perdía la aceptación | ✅ resuelto (se rechaza) |
| #8 `unlock` traga excepciones · rate limit tras proxy · `JWT_SECRET` con default | ❌ **siguen abiertos** |
| Sin tests · sin `mvnw` | ✅ resuelto (53 tests · wrapper agregado) |

### Lo nuevo, en concreto

**`V4__persist_status_and_recurring_price.sql`.** `quote.status` pasa a persistirse con
`PENDING | SENT | SELECTED | REJECTED`, más `sent_at`, `rejected_at` e `iva_pct`;
`quote_option` gana `precio_mensual`. `EXPIRED` se sigue derivando —es función del reloj,
no una decisión— y por eso no se guarda. `PENDING`, `SELECTED` y `EXPIRED` conservan
nombre y significado, así que **el frontend Angular no se rompe**: `SENT` y `REJECTED` son
agregados.

El backfill marca `SELECTED` solo donde hay opción elegida y deja el resto en `PENDING`,
sin inventar fechas de envío: subcontar es preferible a falsear la tasa de cierre. Probado
sobre base vacía y sobre base con datos, incluida la idempotencia.

**Endpoints nuevos** (todos admin):

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/admin/quotes/{id}/send` | Manda la cotización al cliente por correo y la deja `SENT`. Si el correo falla, **la transacción se revierte** y no queda marcada como enviada |
| `POST` | `/api/admin/quotes/{id}/mark-sent` | Registra una entrega hecha fuera del sistema (WhatsApp, reunión, PDF) con su fecha real, **sin mandar correo** |
| `POST` | `/api/admin/quotes/{id}/reject` | Registra que el cliente dijo que no |
| `POST` | `/api/admin/quotes/{id}/options` | Agrega una opción sin rehacer la cotización |

`mark-sent` es lo que permite cargar el histórico sin volver a escribirle a un cliente que
recibió su cotización hace semanas, y sirve igual para el caso normal de entregarla por
WhatsApp.

**El IVA se calcula, no se guarda.** Se guarda el porcentaje aplicado (`iva_pct`, 19 por
defecto) para que una cotización histórica siga mostrando el IVA que tenía cuando se
emitió. La API devuelve `precio`, `precioIva`, `precioTotal` y los tres equivalentes de la
mensualidad. Hay un test parametrizado que compara contra **los 10 montos reales** del PDF
de Macarena y del Markdown de la pastelería: si alguien cambia esa cuenta, el test cae.

### Lo que sigue abierto, dicho claro

1. **La landing (#4).** Es el impedimento más grande que queda y **no se puede cerrar
   desde este repo**: la renderiza el Angular de `github.com/webiados/webiados`. Hasta
   verla al lado del PDF, no se sabe si el cliente va a recibir algo tan presentable.
2. **Los precios siguen sin salir de `pricing.md` (#5).** Es el Sprint 2. Nada impide
   todavía teclear un monto inventado.
3. **La modalidad "sin mensualidad" y los agregados B2B** siguen como texto libre. Son un
   tercer eje de precio (pago único alternativo) y modelarlos sin un caso real más sería
   adivinar.
4. **El plan de mantención de Macarena** ($25.000 / $50.000) no quedó como dato: no es la
   mensualidad de una opción, es una decisión aparte del proyecto. Modelarlo bien pide
   decidir si una cotización puede llevar "planes" además de "opciones".
5. **Dos puntos menores del #8** (el `catch (Exception)` del unlock, el rate limit detrás
   del proxy). Ninguno bloquea la adopción. El tercero, el default de `JWT_SECRET`, **ya
   se resolvió** — ver la caja en §8.

---

## 5.ter Qué se resolvió el 2026-08-25 (primera cotización real salida del sistema)

La cotización de Cubillos Soza fue la primera que salió del sistema en vez de escribirse a
mano. Aparecieron dos bugs al probarla, y el segundo era peor que el primero.

**Causa raíz encontrada (no solo el síntoma):** `ApiExceptionHandler.handleGeneric`
(`@ExceptionHandler(Exception.class)`) vive en un `@RestControllerAdvice`, que Spring
resuelve *antes* que su `DefaultHandlerExceptionResolver`. Por eso interceptaba también
excepciones propias de Spring que ya traían su status correcto —como
`HttpRequestMethodNotSupportedException` (405)— y las forzaba a 500 "Error interno". No
era solo el bug del DELETE: cualquier excepción de Spring no listada explícitamente pierde
su causa real, en cualquier endpoint. **Resuelto:** handler explícito para
`HttpRequestMethodNotSupportedException` antes del genérico. Test TDD que falla con 500
antes del fix y pasa con 405 después (`ApiExceptionHandlerTest`). 87/87 tests en verde.

**`DELETE /api/admin/quotes/{id}` no existe — decisión: no implementarlo.** Revisado el
frontend (`github.com/webiados/webiados`, `quotes-api.ts`): no hay ningún botón ni llamada
que borre una cotización completa, solo `deleteOption` sobre una opción puntual, que sí
funciona. El 500 original salió de una prueba manual contra una ruta que el panel nunca usa.
No hay caso de uso pedido (una cotización se rechaza o se le quitan opciones, no se borra
entera) y borrar la cotización completa abriría una pregunta sin resolver: qué pasa con las
`Selection` (auditoría) que le pertenecen. Con el fix de arriba, ahora responde 405
correctamente en vez de 500.

**Worktree `.worktrees/fix-admin-403-auth` eliminado.** Sus 32 commits ya estaban
completamente fusionados a `master` (cero commits propios sin mergear, diff vacío contra
`ApiExceptionHandler.java`). No había código divergente que reintrodujera este bug — era un
worktree olvidado, no trabajo vivo.

**Los tres huecos para cotizaciones complejas** (desglose de partidas con precio dentro de
una opción, descuento como línea, dos modalidades de pago) siguen abiertos, estimados en
16-24h — no se tocó código de eso en esta sesión, la estimación no se revalidó.

## 5.quater Un cuarto hueco, encontrado con un cliente real (Navautos, 2026-08-27)

`QuoteOption.precio` es `NUMERIC(14,2) NOT NULL` (`V1__init.sql`). El modelo solo tiene dos
montos por opción: **`precio`** (pago único) y **`precioMensual`** (recurrente, todos los
meses). No existe una tercera forma: **un monto real, pagadero una vez, en una fecha futura
distinta de "al firmar"**.

Apareció con Navautos: el setup está **diferido 12 meses**, no exento — hay un monto real que
se va a cobrar, solo que después. Poner `precio=0` no representa eso: dice que el setup
**cuesta cero**, que es falso, y en 12 meses el cliente lee un número, no el texto al lado.
Un número puesto por obligación del schema se lee igual que uno decidido — es exactamente la
ambigüedad que la cotización existía para cerrar.

**Lo que el sistema permite hoy, sin tocar el modelo:** poner el **monto real** en `precio`
(el valor que se va a cobrar, no cero) y describir el diferimiento como condición de pago en
`descripcion`/`features` — "se cobra a los 12 meses, no al firmar". Es honesto: el número es
real, la condición de pago es texto, que es lo que la condición de pago siempre es en estas
cotizaciones (ver "50% al firmar, 50% a la entrega" en las demás). **Lo que no existe es una
línea con su propia fecha** — no hay forma de decir "$X el [fecha]" como dato estructurado
independiente del "pago único al firmar" que `precio` representa implícitamente.

**No se construye ahora** — hay un cliente esperando y esto es de fondo. Se anota como el
cuarto hueco de la misma familia que los otros tres (desglose de partidas, descuento como
línea, dos modalidades de pago): todos son variantes de lo mismo — **el modelo de precios
solo sabe representar "una vez, ahora" y "cada mes, siempre"**. Un pago único diferido, un
pago fraccionado con fechas, o un descuento como línea aparte son la misma clase de problema
y probablemente se resuelven juntos.

**Resuelto en la cotización de Navautos (2026-08-27, mismo día):** Felipe decidió los montos.
Se aplicó exactamente el camino descrito arriba — `precio=890000` (precio de lista del Kit
Tienda, `pricing.md`), sin tocar el schema, con la fecha de cobro (27-ago-2027) y las
consecuencias (qué pasa si cierra antes, si deja de pagar, de quién es el sitio hasta que se
pague) como texto en `descripcion`/`features`. El hueco de fondo (no hay línea con fecha
propia) sigue sin resolverse — esto confirma que el rodeo funciona para un caso, no que el
hueco se cerró.

---

## 6. Qué necesito de Felipe para cerrar 1.2 y 1.3

Las dos cotizaciones están **listas para cargar** en
[`docs/carga-inicial/`](carga-inicial/), ahora **con su estado real**:

| Cliente | Estado final | Fecha de envío |
|---|---|---|
| Macarena Larraín | `SELECTED` (aceptó la Opción C) | 2026-07-24 |
| Pastelería Vientos del Sur | `SENT` | 2026-07-27 |

El script (`cargar.sh`) hace login, crea las dos con su fecha de emisión real, las marca
como entregadas **sin mandarle correo a nadie** (`mark-sent`), y registra la elección de
Macarena a través del flujo del cliente para que quede también en la bitácora.

Está ensayado: `CargaHistoricaIT` corre **esos mismos archivos JSON** contra un Postgres
real y verifica los totales contra el PDF y el Markdown originales. Si ese test pasa, el
script funciona. También tiene `DRY_RUN=1` para ver qué haría sin escribir nada.

**Yo no lo corro.** Falta solo que lo ejecutes tú:

```bash
export ADMIN_EMAIL='felipe@webiados.com'
export ADMIN_PASSWORD='...'          # ADMIN_BOOTSTRAP_PASSWORD de Railway
docs/carga-inicial/cargar.sh
```

Dos cosas que conviene saber antes:

1. **Requiere que V4 esté desplegada.** Los endpoints `mark-sent` y los campos nuevos no
   existen en la versión que hoy corre en Railway. Desplegar necesita tu autorización.
2. **Registrar la elección de Macarena dispara el aviso interno** a `NOTIFY_TO`
   (`contacto@webiados.com`). Al cliente no le llega nada.
3. La fecha de emisión de Vientos del Sur (2026-07-27) es una **suposición**: el Markdown
   original no lleva fecha impresa, a diferencia del PDF de Macarena. Si fue otra,
   corrígela antes de medir la tasa de cierre.

---

## 7. Auditoría de documentación (2026-08-28) — dos decisiones que se tomaron y no se escribieron

Encargo de Felipe: revisar todos los `.md` del repo contra el sistema real, sin corregir nada
sin que se pida. Reporte completo entregado al centro de control por mensaje. Lo que sí
correspondía anotar acá, porque es la fuente de verdad:

**1. `PriceItem` se decidió simplificar, y no quedó escrito.** `docs/SPRINT2_PRECIOS.md`
diseñó una entidad JPA persistida (`slug`/`tipo`/`setup`/`mensual`) con TTL para el catálogo
de precios. Lo que existe en `PricingClient.java` es más simple: un caché en memoria
(`volatile PricingCatalog cache` + `Instant cachedAt`), sin entidad ni TTL explícito — sirve
el DTO del Core tal cual. Cumple el objetivo (nunca inventa un precio si el Core no
responde), pero es una decisión de diseño distinta a la planificada y nadie dejó dicho por
qué se simplificó. **Se anota ahora, sin fecha exacta de cuándo se decidió:** el caché en
memoria alcanza porque el Core ya calcula y cachea el catálogo; una segunda capa persistida
en este servicio habría sido redundante sin agregar nada que el `DoD` pidiera.

**2. El contrato de leads se invirtió, y `HOJA_DE_RUTA.md`/`TASKLIST.md` no se actualizaron.**
Lo planificado originalmente: el formulario público de `webiados.com` postea a este
servicio. Lo que se construyó: el formulario postea al **Core**, y este servicio solo **lee**
leads (`LeadClient`, `GET /api/admin/leads`) para convertirlos en cotización
(`docs/SPRINT3_LEADS.md`). Es el diseño correcto — el Core ya es dueño del CRM de leads en
todo el ecosistema, duplicar el punto de entrada habría sido el error — pero el giro no
quedó registrado donde alguien planificando Sprint 3 lo vería primero.

**Por qué se anota como bug de documentación y no se corrige silenciosamente:** el objetivo
de este ejercicio, según Felipe, no es que cada modelo reescriba sus propios documentos —
es que quede un registro de qué se decidió y por qué, para que nadie construya de nuevo lo
que el plan original pedía creyendo que sigue faltando. `TASKLIST.md` y `SIGUIENTE.md` ya
tienen banners nuevos apuntando acá.

---

## 8. `pricingRef` + aviso de precio (2026-08-29) — y la condición que cierra el hueco de §7

`OptionRequest.precio` acepta cualquier número: no hay validación server-side contra el
catálogo del Core (`docs/guias/cotizaciones-precios-del-core.md`). Se agregó
`QuoteOption.pricingRef` (nullable, `V5__add_pricing_ref_to_option.sql`) — el slug/nombre
del ítem del catálogo del que salió la opción, lo llena el panel al elegir del catálogo,
nunca a mano — y `PricingWarningService`: si `pricingRef` viene, compara `precio`/
`precioMensual` contra el catálogo **fresco** en cada lectura y avisa en
`QuoteAdminDetail.warnings` (`List<OptionWarning>`, con `optionId` — el título de una
opción no es único dentro de una cotización, así que el destino del aviso viaja como dato,
no se deduce del texto). **No bloquea**: sin `pricingRef` (opción armada a mano, negociada,
combinada) no se compara nada — es la mitad de los casos y es para eso que existe una
cotización con un humano armándola.

**La condición que cierra el hueco del plan sin pie (Navautos, §5.quater, y la propuesta
del Kit Agenda que se está construyendo esta semana):** hoy la disciplina de no publicar
un monto del plan sin pie antes de que el Core lo exponga (`EXPONER_PLAN_SIN_PIE`) depende
de que alguien se acuerde — nada del sistema lo impide. **El día que ese ítem entre al
catálogo del Core con su propio slug**, cualquier opción que use `pricingRef` para ese
ítem queda cubierta por el mismo mecanismo: si alguien la crea antes de tiempo con un
monto que no calza (porque el catálogo real todavía no lo trae, o lo trae distinto), el
aviso lo va a mostrar. **No es un candado — sigue sin bloquear — pero dejó de ser silencio
total.** Una opción armada sin `pricingRef` (a mano, adelantándose al catálogo) sigue sin
ninguna protección: eso sigue siendo disciplina, no mecanismo.

**Actualización (2026-08-30/31):** `EXPONER_PLAN_SIN_PIE` ya está en `true` en el Core,
verificado en vivo contra `GET /api/v1/pricing` (los 5 kits, 12 meses, montos definitivos
de Felipe). `ItemPrecio` no tenía campo para `planSinPie` — el dato llegaba y Jackson lo
descartaba en silencio (`@JsonIgnoreProperties(ignoreUnknown = true)`), así que este
servicio no podía representarlo. Se agregó `PlanSinPie` + convención `pricingRef` con
sufijo `:sin-pie` (ej. `"Agenda:sin-pie"`), que compara la mensualidad contra el plan sin
pie del catálogo y la instalación contra cero, en vez de contra los valores normales del
kit — sin esto, la primera cotización real con las dos formas de pago habría disparado un
aviso falso ("el precio no calza") sobre un precio que estaba perfectamente bien.
Verificado en producción con una cotización de prueba real (`4fa78vdc8h`): las dos
opciones del mismo kit, `warnings: []`.

**Término pendiente de anotar en la cotización real cuando exista:** Felipe confirmó que
**la cuota del plan sin pie queda congelada los 12 meses** — no sube aunque el precio de
lista del kit suba mientras el cliente está pagando. Este repo no tiene un lugar propio
donde guardar cláusulas de propuestas antes de que exista una cotización real; el texto
vive hoy con quien arma el contenido de la propuesta (sesión del sitio / centro de
control). Cuando se cree la `QuoteOption` real del plan sin pie, esta condición va como
una línea más en `features`, igual que el resto de las cláusulas ya decididas
(permanencia, dominio/hosting, propiedad del sitio).

🔴 **Límite, 2026-08-31 — anotar junto a esa cláusula, no aparte:**
"congelada 12 meses" promete implícitamente que **después** del mes 12 el precio se
reajusta. **Verificado en este servicio:** no existe ningún mecanismo que reajuste la
mensualidad de un cliente ya firmado. `QuoteOption.precioMensual` es un campo manual:
alguien lo edita a mano (`PUT /api/admin/quotes/{id}/options/{optionId}`) o se queda tal
cual quedó al firmar, para siempre — no hay una fecha guardada de "cuándo termina el plan
sin pie" ni un job que la revise. **Sobre el Core, sin verificar directo:** el centro de
control lo reportó de segunda mano (otra sesión lo está midiendo); si aparece un gancho
real ahí, esta línea se corrige. Si la cláusula real dice "12 meses y después sube al
mensual normal", la parte de "y después sube" **depende hoy, al menos de este lado, de
que alguien se acuerde en 12 meses**, el mismo patrón de falla que el resto de este
documento lleva señalando. **No se construye acá** — es del Core, según el centro de
control — pero el
texto de la cláusula no debería prometer un reajuste que ningún sistema va a disparar
solo.

---

## 9. Los 12 meses del plan sin pie ya son dato, no solo texto (2026-09-01)

Se agregó `QuoteOption.planSinPieMeses` (`Integer`, nullable, `V6__add_plan_sin_pie_meses_to_option.sql`)
y `OptionClientView.planSinPieMeses` — a propósito, **antes** de los campos de precio en el
record, para que quien construya la pantalla no lo ponga después del número que decide. Antes,
la única forma de decir "dura 12 meses" era un párrafo de `features`; se comprobó (cotización de
prueba `xk2skc56nu`) que el número **12 no aparecía en ningún lugar** de la respuesta —
"los meses que se indican en esta cotización" no indicaba nada en ninguna parte.

`PricingWarningService` ahora también compara `planSinPieMeses` contra `item.planSinPie().meses()`
del catálogo, con el mismo aviso no bloqueante: si falta, avisa "no indica los meses"; si no
calza, avisa los dos números. 102/102 tests.

**Esto no reescribe el texto de la cláusula** — ese texto lo redacta quien arma la propuesta.
Lo que hace es dejar un lugar donde el número vive como dato, no solo dentro de un párrafo.

---

## 10. Dos preguntas para Felipe, encontradas comparando la cotización contra la estrategia — sin tocar nada

Verificado 2026-09-01, generando cotizaciones de prueba reales y comparando texto contra texto,
no contra memoria. **No se corrige nada de esto acá — las decide Felipe.**

### 10.1 · De quién es el sitio en el plan sin pie — tres documentos, tres respuestas

- **`docs/pricing.md` §9**, textual: *"Tu sitio web: Tuyo. Diseño, contenido, código, dominio."*
  — sin excepción para el plan sin pie.
- **`docs/estrategia/2026-08-29-setup-en-la-mensualidad.md` §3c**, textual: *"El dominio y el
  hosting quedan en nuestra cuenta hasta el mes 12."* — solo dominio y hosting, no el sitio ni
  el código.
- **El texto de cláusula ya redactado** (sesión del sitio, usado en la cotización de prueba
  `xk2skc56nu`), textual: *"El sitio y su código son de Webiados hasta que la instalación esté
  paga completa."* — más amplio que los dos anteriores: dice que el trabajo entero es de
  Webiados, no solo dominio y hosting.

Tres documentos, tres alcances distintos del mismo término. No es que uno esté mal escrito —
es que ninguno se escribió mirando a los otros dos. **Pendiente de que Felipe decida una
versión única**, y de que se corrija en los tres lugares a la vez cuando la decida.

**Esta decisión se aplica en tres archivos, y dos no son de este repo:** el texto de cláusula
(acá), `docs/estrategia/2026-08-29-setup-en-la-mensualidad.md` y `docs/pricing.md` (ambos en
`Demos-Webiados-Clientes`). Si se corrige solo el texto de la cláusula, `pricing.md` sigue
diciendo *"tuyo desde el primer día"* sin excepción — y es lo que el cliente lee primero, en
el sitio, antes de llegar a una cotización. Coordinación entre repos: centro de control.

### 10.2 · "Deja de pagar y sigue operando" — el caso más común, sin cubrir en ningún lado

El texto de cláusula y `docs/estrategia/...md` cubren *"se cambia de proveedor"* (paga las
cuotas que faltan) y *"el negocio cierra"* (no se cobra el saldo) — pero ninguno de los dos
dice qué pasa si el cliente simplemente deja de pagar mientras el negocio sigue abierto, que es
el caso más probable de los tres. En una decisión anterior, para Navautos, sí se había definido
*"si deja de pagar la mensualidad, se da de baja el sitio"* — pero esa decisión no llegó al
texto de cláusula general del plan sin pie. **Pendiente de que Felipe confirme si aplica igual
acá**, y de escribirlo donde corresponda cuando lo confirme.

### 10.3 · Fecha y estado de un cobro diferido (Navautos, plan sin pie) — cerrado sin construir nada

Se evaluó agregar acá `fechaCobroDiferido` + `estadoPago` a `Quote`/`QuoteOption` (mismo patrón
que `planSinPieMeses` en §9). **No se construye:** verificado en el código del Core
(`Demos-Webiados-Clientes/core/src/collections/Tenants.ts`) que ya existen `fechaProximoAjuste`
(date) y `estadoPago` (`al_dia`/`por_cobrar`/...) en `Tenants` — el comentario del código cita a
Navautos por nombre como el caso de origen. Confirmado que `plan` incluye `vitrina`: ningún
cliente de Webiados, ni el kit más simple, queda sin tenant. Cardinalidad revisada: una
cotización puede tener varias opciones con términos hipotéticos distintos antes de elegir, pero
al seleccionarse colapsa a un único cobro real — misma cardinalidad que un tenant.

**Los campos existen en el Core; se llenan cuando el cliente entra en servicio, no antes.**
Cargar una fecha antes de que el servicio arranque dejaría un cobro agendado para algo que
todavía no existe — peor que no cargarlo. Navautos hoy no puede publicarse: falta el descuento
del proveedor. Cuando se publique, el campo se llena allá, no acá.

---

## 11. El camino del cliente, recorrido de verdad por primera vez (2026-09-01)

**Ninguna cotización se había recorrido nunca como cliente real.** Verificado sobre las 16
cotizaciones que existen: los dos únicos `SELECTED` son un backfill histórico (Macarena Larraín,
`notes` dice *"Cotización original del 2026-07-24 (PDF)"*, reconstruida por script) y una prueba
interna vieja (`nicoGay@webiados.com`, ya borrada — ver abajo). Los `SENT` reales se entregaron a
mano y se marcaron con `mark-sent`. `/send` solo se usó dos veces, las dos a un correo de prueba.

Recorriendo el camino en el navegador contra producción (con la cotización real de Estética
Duval, solo lectura, sin elegir nada por ella) aparecieron tres cosas:

**1 · 🟢 Corregido — el mismo bug del `DELETE`, en dos lugares más del mismo archivo.**
`ApiExceptionHandler.handleGeneric` seguía atrapando excepciones que ya traían su propio status:
un `ResponseStatusException` lanzado a mano en `ClientQuoteController` cuando el código del token
no coincide con el de la ruta (navegar entre dos cotizaciones en la misma pestaña sin volver a
poner la clave), y un `AccessDeniedException` cuando un token de cliente pega a un endpoint
`@PreAuthorize` de admin — los dos salían como 500 en vez de 403. Se agregó un handler por
**tipo** de excepción, no por caso puntual, para no dejar una tercera. Los dos, confirmados en
producción real antes y después del fix, con `curl`. 106/106 tests.
No se tocó el frontend, y no hizo falta: **✅ verificado, 2026-09-01 — cerrado de punta a punta.**
La sesión del sitio reprodujo el 403 en vivo contra `webiados.com` con los códigos de prueba de
acá, capturando la red: la pantalla ya vuelve a mostrar el formulario de clave, no un error
genérico. No se tocó nada del lado de ellos porque ya funcionaba bien — la nota anterior
("necesario y no suficiente") era correcta con lo que se sabía en el momento, quedó vencida en
horas. Se deja este historial a propósito, para que quede visible cómo se cerró, no solo que
está cerrado.

**2 · 🟢 Corregido — la notificación interna solo se registraba cuando fallaba.**
`EmailService.notifySelection` únicamente logueaba en el `catch`. "Sin errores en el log" no es
prueba de que se mandó — un envío que falla sin lanzar excepción se vería idéntico. Ahora también
registra el éxito, con el código de la cotización (`EmailServiceNotifySelectionTest`, con un
`ListAppender` de Logback capturando el log real).

**✅ Verificado, 2026-09-01 — llegó de verdad.** Felipe confirmó en su bandeja (`contacto@webiados.com`)
los dos correos reales de la selección de prueba (`xk2skc56nu`, 9:29): *"✅ Cotización xk2skc56nu"*
(selección inicial) y *"⬆️ Upgrade — Cotización xk2skc56nu"* (cambio a plan superior). No es solo
"consistente con que funcionó" — es la confirmación en la bandeja, con fecha. **Y que llegaran los
dos, no uno, importa:** el segundo correo distingue "eligió por primera vez" de "cambió su
elección" — es el que le avisa a Felipe que un cliente subió de plan, no solo que eligió algo.

**3 · 🟢 Borrada — una cotización de prueba interna con una broma sobre un socio real, en producción.**
`856skn73j5` / "NicoGAY", `nicoGay@webiados.com`, notas *"el es gaysh"*. No existe endpoint
`DELETE /api/admin/quotes/{id}` (decisión documentada en §5.ter: no se construyó por falta de
consumidor) — se borró con una sentencia SQL directa, en transacción, verificando el conteo antes
de confirmar (`DELETE 1`, cascada limpia por FK `ON DELETE CASCADE` desde `V1__init.sql`),
confirmado después con un `GET` real → `404`.

---

## 12. Recorrido completo de punta a punta, con cronómetro (2026-09-01)

**Primera vez que el ciclo entero corrió de una vez, no en piezas sueltas.** Login real en
`webiados.com/admin` → cotización nueva desde el formulario del panel → `/send` real (correo a
mi propio correo, con autorización) → abierta como cliente → desbloqueada → elegida una opción.
Cotización de prueba `j4eqs2qnft`, ya borrada (mismo método que NicoGAY: SQL directo en
transacción, `DELETE 1`, confirmado con `GET` real → `404`).

**Cronómetro:** ~2 min para crear (nombre, correo, 2 opciones completas) + 36 s para enviar =
**~2 min 36 s crear + enviar**, con datos escritos por script (más rápido que tipeo humano letra
por letra, pero comparable a copiar/pegar). El tiempo real está en redactar el texto de las
opciones, no en pelear con el sistema — cotizar no es caro hoy.

**🔴 Hallazgo real: el formulario "Nueva cotización" del panel es texto libre puro.** Sin
selector de catálogo, sin `pricingRef`, sin el campo de meses del plan sin pie. Todo lo
construido esta semana (`PricingWarningService`, `planSinPieMeses`) está listo en esta API y
**nadie lo alcanza** — Felipe cotiza escribiendo números a mano y ninguna de esas protecciones
se activa.

**Dimensionado, no construido:**
- **De qué repo es:** del panel (`webiados/webiados`, Angular), no de este servicio ni del
  Core. Esta API ya expone todo lo necesario (`GET /api/admin/pricing` con `planSinPie`
  incluido, `OptionRequest.pricingRef`/`planSinPieMeses`) — no falta nada de este lado.
- **Qué haría falta:** un selector en el formulario que, al elegir un kit/addon del catálogo,
  prellene título/precio/mensual y guarde el `pricingRef` (con el sufijo `:sin-pie` si aplica) y
  los meses — en vez de los campos de texto libre actuales. Trabajo de UI, no de API.
- **Qué error concreto se pierde hoy:** si Felipe escribe a mano un precio con un dígito de
  menos, un monto de una lista de precios vieja, o el mensual del plan sin pie con el redondeo
  equivocado (como pasó esta semana: $110.000 vs. $111.000 corregido), **nada lo avisa.** El
  candado suave que se construyó esta semana existe y no protege nada mientras el formulario no
  lo llene — mismo patrón que el cupón de Rofex sin llamador, el conteo de contactos de prueba,
  y la clave del bot leyendo el proyecto equivocado: la pieza existe, nadie la conectó.

**🟡 Un `422` transitorio en `GET /api/admin/pricing`, anotado con hora — no rompió nada, no se
tocó:** ocurrió durante este recorrido, **2026-09-01 ~12:04:29 hora de Chile** (calculado desde
el timestamp del navegador, no del servidor — la ventana de logs de Railway ya había rotado al
momento de buscarlo). Verificado con `curl` segundos después: `200` normal. Un `422` acá sale de
`PricingClient` cuando el Core no responde y **tampoco hay nada cacheado todavía** — se
autocorrigió solo. Si vuelve a aparecer, esta es la primera vez registrada.

---

## 13. Ciclo de vida de una cotización — `unlockedAt` y la alerta de sin respuesta (2026-09-02)

Salió de §12: si un cliente entra, pone la clave y no elige nada, el sistema no lo sabía — el
único rastro era `selectedAt`/`rejectedAt`. Se midió el costo real esa semana: de tres
oportunidades de venta, las tres murieron después de dar el precio sin que nadie supiera si las
habían mirado.

**`Quote.unlockedAt`** (nullable, `V7`): primera vez que `POST /unlock` funciona con la clave
correcta — mismo patrón que `sentAt`, no se pisa en desbloqueos siguientes. Es intención real,
no un contador de vistas. Con esto, "vista, sin elegir" deja de ser invisible: es un estado
**derivado** (`unlockedAt != null AND selectedOptionId == null AND status != REJECTED`), no un
campo aparte — un dato que se puede calcular no se guarda.

**Deliberadamente no se agregó "se abrió el enlace"** (antes de poner la clave): necesitaría un
endpoint público nuevo, sin autenticar — más expuesto que `/unlock` y con menos señal (alguien
que abre un link por curiosidad no es un interesado; alguien que escribe la clave sí).

**`StaleQuoteAlertJob`** (`@Scheduled`, 09:00 diario) avisa por correo interno cuando una
cotización lleva `QUOTE_STALE_ALERT_DAYS` (7 por defecto — la mitad de `QUOTE_VALIDITY_DAYS`,
deja una semana para actuar antes de que expire sola) sin selección ni rechazo desde que se
envió (o se abrió, si nunca se marcó enviada). **Apagada por defecto** (`QUOTE_STALE_ALERT_ENABLED=false`,
regla 13). `Quote.staleAlertedAt` (`V8`) evita que el mismo aviso suene todos los días.

**La primera vez que se activa, siembra en silencio:** si ya había cotizaciones viejas sin
respuesta antes de prender la alerta, la primera pasada las marca como revisadas sin mandar
ningún correo — se detecta comprobando si *alguna* cotización en toda la tabla ya tiene
`staleAlertedAt`; si ninguna lo tiene, esta pasada es la siembra. Una alerta que nace con veinte
avisos atrasados de golpe nace ignorada.

**Expuesto para el panel:** `unlockedAt` en `QuoteAdminSummary` y `QuoteAdminDetail`, para que la
lista pueda distinguir "Enviada, sin abrir" de "Vista, sin elegir" — dos problemas de venta
distintos que hoy se ven igual. Trabajo de UI pendiente, no de este repo.

115/115 tests.

---

## 14. `/send` que falla y no dejaba marca — el peor de los tres, arreglado (2026-09-02)

Del barrido de §13/14: si el SMTP rechazaba el correo, la cotización quedaba en `PENDING` **igual
que una que nunca se intentó enviar** — con 3 de 8 cotizaciones reales vencidas sin respuesta al
momento de medir, no había forma de saber si alguna simplemente nunca llegó. Es la peor clase de
error: uno que se ve igual que el funcionamiento normal.

**`Quote.sendFailedAt`/`sendFailureReason`** (`V9`, nullable): se marca cuando `/send` falla, sin
bloquear el reintento — sigue en `PENDING`, `send` se puede volver a llamar sobre la misma
cotización sin recrear nada. Un envío que sí funciona después **borra** la marca (`markSent`
limpia `sendFailedAt`/`sendFailureReason`) — reintentar y que funcione no debería seguir
mostrando el fallo de un intento viejo.

**El detalle que lo hace funcionar:** la marca tiene que sobrevivir a que la transacción del
intento fallido se revierta (`QuoteService.send()` sigue revirtiendo todo si el correo no sale —
eso no cambió, una `SENT` sin correo real seguiría falseando la tasa de cierre). Se resolvió con
`SendFailureRecorder`, un componente aparte con su propio `@Transactional(REQUIRES_NEW)` —
**tiene que ser una clase distinta**, no un método más de `QuoteService`: Spring no aplica
`REQUIRES_NEW` en una llamada de un método a otro dentro del mismo bean, solo a través del proxy.
Probado explícitamente: falla → la marca sobrevive → reintento con éxito → la marca desaparece.

**Expuesto en `QuoteAdminSummary` y `QuoteAdminDetail`** — visible en la lista del panel, no solo
en el log de Railway. Trabajo de UI para mostrarlo, pendiente, no de este repo.

121/121 tests.

## 15. `RateLimiter` en memoria — anotado, no arreglado

Los intentos de clave equivocada en `/unlock` solo se cuentan en memoria (Caffeine, expira solo,
se pierde en cada redeploy) — nadie puede revisar después cuántos intentos hubo ni cuándo.

**No se resuelve ahora, a propósito:** con 8 cotizaciones reales, nadie está intentando adivinar
claves — es un problema del día que haya volumen suficiente como para que valga la pena intentar
adivinar una, y ese día se resuelve distinto (probablemente con persistencia + bloqueo más
agresivo, no solo con guardar el conteo). **Condición para revisarlo:** cuando el número de
cotizaciones activas simultáneas crezca lo bastante como para que un intento de fuerza bruta deje
de ser una hipótesis teórica.

## 16. Dos ocurrencias más del mismo patrón, encontradas verificando el deploy de §14

Verificando en producción que `sendFailedAt`/`sendFailureReason` llegaban bien, until un `GET`
con un `id` mal formado por error (`.../quotes/no-es-un-uuid`) — y salió **500**, no 404 ni 400.
Con eso fresco, probé también un `POST` con JSON roto — **también 500**. Las dos, confirmadas en
producción real antes de tocar nada, con el stack trace de Railway:

- **`MethodArgumentTypeMismatchException`** — el `{id}` de la ruta espera `UUID`; cualquier cosa
  que no lo sea (un `codigo` pegado donde iba el `id`, un typo) caía en `Exception.class` antes
  de convertirse en el 400 que le correspondía.
- **`HttpMessageNotReadableException`** — un cuerpo JSON malformado, mismo destino.

**Es la quinta y sexta ocurrencia del mismo patrón en este archivo** (405 del `DELETE`, dos 403,
y ahora estos dos) — un handler por tipo de excepción, no por caso puntual, para no dejar una
séptima. Los dos, con test que reproduce el 500 real antes del fix y el 400 después.

123/123 tests.

## 17. Barrido completo de la superficie pública, no caso a caso

Con el patrón repetido seis veces, el pedido fue: cada endpoint, con entradas malas — id
inválido, JSON roto, campo obligatorio ausente, tipo equivocado, recurso que no existe, cuerpo
vacío — y **¿el código le dice al que llama qué hizo mal, o dice "error interno"?**

**Resultado, probado contra producción real, `curl`, no local:** los ~22 endpoints públicos,
con las seis variantes de entrada mala donde aplican, **todos devuelven un 4xx correcto hoy** —
0 quedan en 500. La razón: los dos handlers de §16 no son por endpoint, son por **tipo de
excepción de Spring MVC**, y esos dos tipos (`MethodArgumentTypeMismatchException` para
`@PathVariable`/`@RequestParam` mal tipados, `HttpMessageNotReadableException` para JSON roto
**o con un campo del tipo equivocado** — Jackson lanza la misma excepción para las dos) cubren
toda la forma en que Spring MVC puede fallar al leer una petición, sin importar en qué
controller. Por eso el barrido salió limpio de una vez: no son 22 arreglos, son 2.

| Excepción | Dispara con | Antes | Después | Alcance |
|---|---|---|---|---|
| `HttpRequestMethodNotSupportedException` | verbo equivocado en una ruta que existe | 500 | 405 | Todos los endpoints (arreglado antes de esta semana) |
| `ResponseStatusException` | código del token de cliente ≠ código de la ruta | 500 | 403 | `ClientQuoteController` |
| `AccessDeniedException` | token de cliente contra endpoint solo-admin | 500 | 403 | Todo lo `@PreAuthorize("hasRole('ADMIN')")` |
| `MethodArgumentTypeMismatchException` | `id`/`optionId` con formato inválido en la ruta | 500 | 400 | Todos los `@PathVariable UUID` |
| `HttpMessageNotReadableException` | JSON roto, cuerpo vacío, **o un campo con el tipo equivocado** (`precio: "texto"`, una fecha mal formada) | 500 | 400 | Todos los `@RequestBody` |
| `MethodArgumentNotValidException` | campo `@NotBlank`/`@NotNull` ausente | 400 | 400 | Ya andaba bien — sin cambios |
| `NoSuchElementException` | recurso que no existe (id válido, no está) | 404 | 404 | Ya andaba bien — sin cambios |
| `IllegalArgumentException`/`IllegalStateException` | validación de negocio (credenciales, reglas propias) | 400/422 | 400/422 | Ya andaba bien — sin cambios |

**Lo que queda cubierto por el genérico, y es correcto que quede ahí:** cualquier cosa que no
sea "el que llamó mandó algo mal formado" — una caída real de Postgres, del Core, del SMTP en
medio de una operación que no es `/send` (que ya tiene su propio manejo). Ahí sí corresponde
500, porque el que llama no puede corregir nada.

**El registro del genérico ahora nombra la excepción en el mensaje, no solo en el stack trace**
(`"Error inesperado (NombreDeLaClase): mensaje"`) — el hallazgo de Rofex fue justo este: una
falla de credenciales perfectamente normal quedaba escondida detrás de un `"error interno del
servidor"` genérico, y eso mandó a alguien a revisar la infraestructura equivocada.

123/123 tests. Nada nuevo que arreglar — el barrido confirma que los dos handlers ya cerraron
toda la clase de error, no solo los seis casos encontrados uno por uno.
