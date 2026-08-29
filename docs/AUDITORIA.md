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
