-- Cuándo se avisó (o se sembró en silencio) que una cotización quedó sin respuesta. NULL =
-- todavía no corresponde, o nunca se revisó. Evita que el aviso suene todos los días una vez
-- que ya sonó una vez, y permite sembrar en silencio el backlog viejo al activar la alerta
-- por primera vez, sin mandar veinte correos de golpe.
ALTER TABLE quote ADD COLUMN stale_alerted_at TIMESTAMPTZ;
