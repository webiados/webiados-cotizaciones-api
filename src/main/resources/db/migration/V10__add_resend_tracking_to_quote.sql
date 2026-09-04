-- Migración de Gmail SMTP a Resend: agrega lo necesario para saber si un correo que sí
-- se aceptó al enviar después rebotó de verdad, en vez de que esa señal se pierda en una
-- bandeja de entrada que nadie lee por código.
--
-- resend_email_id: el id que Resend devuelve al aceptar el envío. Es la llave real para
-- calzar el webhook de rebote contra la cotización exacta — emparejar por el correo del
-- cliente + una ventana de tiempo es un supuesto; emparejar por este id no lo es.
--
-- bounce_detected_at / bounce_reason: mismo patrón que send_failed_at (V9), pero es un
-- fallo distinto — send_failed_at es un fallo AL ENVIAR (síncrono, se sabe al toque);
-- esto es un fallo DESPUÉS de aceptado, que se entera minutos u horas más tarde por el
-- webhook. Las dos cosas se ven iguales para el cliente ("no llegó nada") pero necesitan
-- marcas separadas porque se enteran en momentos distintos y con evidencia distinta.
ALTER TABLE quote ADD COLUMN resend_email_id VARCHAR(64);
ALTER TABLE quote ADD COLUMN bounce_detected_at TIMESTAMPTZ;
ALTER TABLE quote ADD COLUMN bounce_reason TEXT;

CREATE INDEX idx_quote_resend_email_id ON quote (resend_email_id) WHERE resend_email_id IS NOT NULL;
