# Cotizaciones — crear una y enviársela al cliente

## Qué hace
Creas una cotización en el panel y el sistema se la manda al cliente por correo, con un enlace y
una clave. El cliente entra, ve las opciones con su precio (IVA incluido) y elige una. Es lo que
reemplaza escribir la cotización a mano en un documento.

## Dónde se ve
- **Tú (admin):** `webiados.com/admin` → inicias sesión → **Dashboard** (la lista) y **Nueva cotización**.
- **El cliente:** recibe un correo y abre `webiados.com/cotizacion/{código}`, donde desbloquea con su clave.

## Cómo se prende / apaga
Es parte del panel, siempre está disponible. Dos condiciones para el botón **"Enviar por correo"**:
1. La cotización tiene que tener el **correo del cliente** (si no, el botón queda apagado).
2. El servidor tiene que tener el **correo configurado** (Resend — ver
   [`correo-resend.md`](../correo-resend.md)).

Si no tienes el correo del cliente, usa **"Marcar como enviada"** y mándale el enlace + la clave
por WhatsApp: la cotización igual queda registrada como "Enviada".

## Cómo se demuestra (60 segundos)
1. Entra a `webiados.com/admin` e inicia sesión.
2. **Nueva cotización** → nombre y correo del cliente, un título, y **1 opción** (nombre + precio neto). **Crear**.
3. **Ver detalle** → en **Acciones**, **"Enviar por correo"**.
4. Muestra el correo que llega: encabezado Webiados, botón lima, la clave, y **"vigente hasta el lunes 18 de agosto"**.
5. Abre el enlace del correo, desbloquea con la clave, elige una opción → en el panel queda **"Seleccionada"**.

## Qué NO hace
- **No cobra ni factura.** Registra que el cliente eligió; el pago va por fuera.
- **No convierte monedas** ni pone el dólar/UF; muestra pesos con IVA 19%.
- **El precio es una foto:** cambiar los precios después **no** cambia una cotización ya enviada.
- **No hay un formulario público** donde el cliente pida una cotización solo. Siempre la crea el admin
  (para recibir leads del outbound, ver [`cotizaciones-desde-lead.md`](cotizaciones-desde-lead.md)).

## Aviso de "sin respuesta" (prendido desde 2026-09-04)
Si una cotización se envía (o se abre) y pasan 7 días sin que el cliente elija ni rechace, llega
un aviso interno a `NOTIFY_TO` — no al cliente. La primera vez que se prendió, las cotizaciones
viejas que ya calificaban se marcaron en silencio, sin avisar de golpe por todo el backlog.

## Si algo sale mal
- **"No se pudo enviar el correo (500)":** es configuración de correo, no la cotización. Mira
  [`correo-resend.md`](../correo-resend.md) y los logs de Railway (busca `Resend` o
  `RESEND_API_KEY`). Por diseño, si el correo falla la cotización **no** queda como "Enviada"
  (no miente) — y si el fallo fue después de aceptado (rebote real, avisado por webhook), queda
  la marca `bounceDetectedAt` en vez de verse igual que una cotización ignorada.
- **La cotización aparece sin estado (badge en blanco) en el panel:** el panel desplegado quedó viejo;
  hay que desplegar la última versión del frontend (`webiados/webiados`).
- **El cliente dice que el precio no cuadra:** confirma que el neto es correcto; el total lo calcula
  el sistema con IVA 19% (neto × 1,19).
