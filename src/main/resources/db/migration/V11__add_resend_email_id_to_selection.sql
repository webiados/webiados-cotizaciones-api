-- El aviso interno de "un cliente eligió" (notifySelection) manda por el mismo Resend que la
-- cotización al cliente, pero antes no guardaba ningún identificador: si ese correo fallaba o
-- rebotaba, la única marca era una línea de log, y el webhook de rebote (V10) no tenía con qué
-- calzarlo. Una cotización puede tener varias selecciones (INITIAL + UPGRADE), así que el
-- identificador va en la selección, no en la cotización — no hay ambigüedad de a cuál aviso
-- corresponde un rebote.
ALTER TABLE selection ADD COLUMN resend_email_id VARCHAR(64);

-- Mismo patrón que quote.bounce_detected_at (V10), pero acá el mensaje al avisar no puede ser
-- "algo falló": la aceptación del cliente no se perdió — sigue en quote.status — lo que se
-- perdió es que alguien se enterara a tiempo. La acción es llamar, no reintentar el correo.
ALTER TABLE selection ADD COLUMN bounce_detected_at TIMESTAMPTZ;
ALTER TABLE selection ADD COLUMN bounce_reason TEXT;

CREATE INDEX idx_selection_resend_email_id ON selection (resend_email_id)
    WHERE resend_email_id IS NOT NULL;
