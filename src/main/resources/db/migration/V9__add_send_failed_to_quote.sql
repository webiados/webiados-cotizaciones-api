-- Marca visible de un envío real que se intentó y falló. Sin esto, una cotización con el
-- correo rechazado por el SMTP se ve idéntica en el panel a una que nunca se intentó enviar —
-- las dos quedan en PENDING, y quien la mira no puede distinguir "espera al cliente" de
-- "el cliente nunca la recibió". NULL = nunca falló, o el último intento sí funcionó.
ALTER TABLE quote ADD COLUMN send_failed_at TIMESTAMPTZ;
ALTER TABLE quote ADD COLUMN send_failure_reason TEXT;
