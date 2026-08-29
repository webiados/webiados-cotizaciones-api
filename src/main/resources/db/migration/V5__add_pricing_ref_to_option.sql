-- Slug/nombre del ítem del catálogo del Core del que salió la opción, para el aviso (no bloqueo)
-- cuando el precio guardado ya no calza con lo que el Core publica hoy. NULL = opción armada a
-- mano (combinada, negociada, a medida) — no se compara contra nada, a propósito.
ALTER TABLE quote_option ADD COLUMN pricing_ref VARCHAR(200);
