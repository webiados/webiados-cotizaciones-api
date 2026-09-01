-- Plazo del plan sin pie de una opción, como dato — NULL si la opción no es un plan sin pie.
-- Sin esto, "dura los meses que se indican en esta cotización" no indicaba nada en ninguna
-- parte: el número vivía solo dentro de un párrafo de texto libre, sin poder contarse, filtrarse
-- ni avisarse cuando se acerca el vencimiento.
ALTER TABLE quote_option ADD COLUMN plan_sin_pie_meses INTEGER;
