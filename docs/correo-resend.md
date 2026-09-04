# Correo saliente (Resend)

> **Decisión (2026-09-04):** se migró de Gmail SMTP a la **API HTTP de Resend**, único camino de
> envío. La razón que más pesó no fue el rebote (ver abajo), fue el riesgo de mandar correo
> transaccional desde la cuenta de Gmail de la empresa: si Google la marca por volumen o patrón,
> no se pierden las cotizaciones — se pierde el correo de Webiados entero. Hoy el volumen es bajo
> y por eso no había pasado; era un riesgo silencioso que crecía solo.
>
> Segunda razón, que ya valía por sí sola: Gmail no tiene forma estructurada de avisar un rebote
> — el rebote existe (una notificación de entrega fallida real, del protocolo SMTP), pero llega
> como un correo más a la misma bandeja que mandó el original, y nada lo leía. Con eso, una
> cotización que nunca llegó se veía exactamente igual que una que el cliente ignoró — y en la
> primera, el negocio pierde una venta por un problema propio sin saberlo.
>
> El repo ya tenía Resend documentado como "Plan B" desde el 31-jul (ver historial de este
> archivo, antes `docs/correo-smtp.md`) — no se inventó un camino nuevo, se adelantó el que ya
> estaba pensado.

## Por qué HTTP y no SMTP

Resend ofrece las dos formas de enviar. Se eligió la API HTTP a propósito: cada envío aceptado
devuelve un `id` (`re_...`), que se guarda en la cotización (`Quote.resendEmailId`). Ese id es la
**única** forma de calzar un rebote futuro contra la cotización exacta que lo mandó —
emparejar por el correo del cliente + una ventana de tiempo es un supuesto (dos cotizaciones al
mismo cliente en la misma semana lo rompen); emparejar por este id no lo es.

## Para qué se usa

- `sendQuoteToClient` — le manda al **cliente** el enlace de la landing + su clave. Es síncrono
  y propaga el error a propósito: si el correo no sale, la cotización **no** queda marcada como
  `SENT` (no miente).
- `notifySelection` / `notifyStale` — avisos internos a `NOTIFY_TO` cuando un cliente elige o
  cuando una cotización lleva mucho sin respuesta. `@Async`, fallas se registran y se tragan.
- `notifyBounce` — mismo mecanismo, disparado desde `ResendWebhookController` cuando Resend
  avisa (por webhook) que un correo YA aceptado rebotó de verdad.

## Configuración vigente — variables de Railway

| Variable | Valor | Notas |
|---|---|---|
| `RESEND_API_KEY` | llave de Resend | **Dedicada a este servicio.** No la de otro flujo del ecosistema (agenda-plus/Booklia ya usa una) — revocar una por un problema no debe romper dos cosas. Sin default: el servicio arranca igual, pero cualquier intento de enviar falla con un mensaje claro pidiendo que se configure. |
| `RESEND_WEBHOOK_SECRET` | `whsec_...` | La firma Svix del webhook. Sin esto, `POST /api/webhooks/resend` rechaza **todo** (503) — fail closed, no "por si acaso". Se obtiene al configurar el endpoint del webhook en el dashboard de Resend. |
| `RESEND_API_URL` | opcional | Por si hiciera falta apuntar a otro endpoint. Default: `https://api.resend.com/emails`. |
| `MAIL_FROM` | remitente | Tiene que ser una dirección del dominio verificado en Resend (`webiados.com` — ya verificado, ver abajo). |
| `NOTIFY_TO` | buzón interno | Selección, sin respuesta, rebote — los tres avisos van al mismo lugar. |

**Las credenciales las carga Felipe en Railway. El modelo no las recibe ni las escribe** — mismo
criterio que con Gmail.

## Dominio

`webiados.com` **ya estaba verificado en Resend** al momento de esta migración — confirmado por
DNS público (`dig TXT resend._domainkey.webiados.com`, un registro DKIM real), no reconfigurado
de cero. Probablemente de cuando se creó la `RESEND_API_KEY` que ya usa otro flujo del
ecosistema. No hizo falta ningún trabajo de DNS para esta migración.

## Antes de prender el interruptor

Una vez que `RESEND_API_KEY` esté en Railway: mandar una cotización de prueba real a una
dirección propia por el camino nuevo, y comprobar tres cosas — que llega, que se ve bien
(HTML, logo, botón), y que el remitente es el correcto (`MAIL_FROM`, no reescrito a otra
dirección). Ese último importa: si el correo pasa a venir de otra dirección, un cliente que
responda puede escribirle a nadie.

## El webhook (`POST /api/webhooks/resend`)

Endpoint **público** a nivel de Spring Security (no hay sesión de admin ni de cliente que lo
llame — Resend sí), pero protegido por la firma Svix que Resend agrega a cada webhook. Sin
`RESEND_WEBHOOK_SECRET` configurado, rechaza todo con 503 en vez de procesar sin verificar quién
manda — esto marca cotizaciones como no entregadas, y un endpoint así no puede aceptar avisos de
cualquiera.

**Configurar en el dashboard de Resend:** Webhooks → Add endpoint → URL:
`https://cotiza.webiados.com/api/webhooks/resend` → eventos `email.bounced` y `email.complained`.
Resend da el `whsec_...` en ese momento; eso es lo que va en `RESEND_WEBHOOK_SECRET`.

**El latido de esto es distinto al de un cron.** No hay "corrida sin candidatas" que loguear
porque no corre por horario, espera tráfico. La forma de distinguir "no hubo rebotes" de "el
webhook dejó de recibir tráfico" es que **toda** petición con firma válida se loguea
(`ResendWebhookController`), calce o no con una cotización — el volumen de esas líneas en Railway
a lo largo de un mes es la señal de que el endpoint sigue vivo, no solo los rebotes que sí
calzaron con una cotización real.

## Cómo probar

Crear una cotización de prueba **con tu propio correo** como cliente y usar «Enviar por correo».
Debe llegar el mail con el enlace + la clave, y la cotización pasar a «Enviada». Para probar el
rebote sin esperar uno real: Resend documenta direcciones de prueba que rebotan a propósito en
modo test (revisar `resend.com/docs` al momento de probar, no confiar en una dirección anotada
acá que puede haber cambiado) — mandar a una de esas y revisar en Railway que el webhook llegó y
la cotización quedó con `bounceDetectedAt`.
