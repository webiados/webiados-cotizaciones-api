-- Primera vez que el cliente puso la clave correcta. NULL = nunca la abrió. Sin esto, "el
-- cliente entró y no eligió nada" era invisible: solo quedaba rastro si aceptaba o rechazaba.
ALTER TABLE quote ADD COLUMN unlocked_at TIMESTAMPTZ;
