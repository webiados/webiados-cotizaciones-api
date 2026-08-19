# TASKLIST — Cotizaciones API

> Asociada a [`docs/HOJA_DE_RUTA.md`](docs/HOJA_DE_RUTA.md) · Prioridad P0
> **Se marca solo lo verificado.**

**Progreso: 4 / 20** · V4 y frontend desplegados (2026-07-31), histórico cargado. Falta el DoD:
usar el sistema con un prospecto real.

> 🔍 **Auditoría del código hecha el 2026-07-27:** [`docs/AUDITORIA.md`](docs/AUDITORIA.md).
> Léela antes de seguir — cambia el alcance de los sprints 2, 3 y 4.

## Sprint 1 — Adopción `P0`

- [x] 1.1 Servicio y panel verificados en producción
      · API `UP` en `cotizaciones-api-production-e0fb.up.railway.app` (401 correcto sin token)
      · Panel abre en `webiados.com/admin/login`
      · ✅ `cotiza.webiados.com` ya resuelve (2026-08-19): CNAME a Railway, `/actuator/health` 200 UP
      · ⚠️ Apunta a la **API**, no al panel: sirve JSON. El panel sigue en `webiados.com/admin`
      · ⚠️ Login efectivo **no verificado**: faltan credenciales
- [x] 1.2 Macarena Larraín cargada — Opción C, $380.000 + IVA, `SELECTED`
      · Cargada por Felipe el 2026-07-31 (V4 en producción). Estado `SELECTED`, con nota del 50% pagado
- [x] 1.3 Pastelería Vientos del Sur cargada — 3 opciones (con mensualidad), `SENT`
      · Cargada por Felipe el 2026-07-31. Estado `SENT` con su fecha de envío
- [x] 1.4 Impedimentos para usarlo, escritos → [`docs/AUDITORIA.md`](docs/AUDITORIA.md) §3
- [ ] 1.5 Impedimentos resueltos — **parcial**, ver [AUDITORIA §5.bis](docs/AUDITORIA.md)
      - [x] Estado `SENT`/`REJECTED` persistido, con fecha de envío (V4)
      - [x] El sistema le manda la cotización al cliente (`POST /{id}/send`)
      - [x] IVA y total como datos, no como texto libre
      - [x] Precio mensual/recurrente en el modelo (`precio_mensual`)
      - [x] Backdating para cargar el histórico con sus fechas reales
      - [x] Bugs del camino de edición (§3 #7): PATCH, `imagenes`, agregar y borrar opción
      - [x] Panel: botón Enviar, estado «Enviada», rechazar, precio mensual e IVA en la landing
            — **desplegado** en `webiados/webiados` (Vercel) el 2026-07-31
      - [ ] Evaluar la landing del frontend vs. el PDF actual — **otro repo**, sin cerrar
      - [ ] Modalidad "sin mensualidad" y agregados B2B — siguen como texto libre
      - [ ] Plan de mantención de Macarena como dato (no es mensualidad de una opción)
      - [x] `JWT_SECRET` sin default: el servicio se niega a arrancar sin secreto
            (verificado en prod: **NO usa el default**; corregido igual — ver AUDITORIA §8)
      - [ ] Menores §3 #8 restantes: `catch (Exception)` del unlock, rate limit tras proxy
- [ ] **DoD:** una cotización real enviada a un cliente desde el sistema

## Sprint 2 — Una sola fuente de precios `P1`

> ⚠️ **Replanteado** — la 2.2 original ("sincronizar `Selection`") no es ejecutable:
> `Selection` es una bitácora, no un catálogo. Ver [`docs/SPRINT2_PRECIOS.md`](docs/SPRINT2_PRECIOS.md).

- [ ] 2.1 Cliente HTTP contra `GET /api/v1/pricing` del Core, con timeout y fallback
- [ ] 2.2 Entidad `PriceItem` como read-through del catálogo (kits + addons), con refresh
- [ ] 2.3 El panel arma opciones eligiendo `slug`, con montos prellenados del Core
- [ ] 2.4 Test: ningún monto de negocio hardcodeado en `src/main`
- [ ] **DoD:** cambiar un precio en `pricing.md` cambia lo que se cotiza (en cotizaciones
      **nuevas**; las ya enviadas son snapshots y no cambian)

## Sprint 3 — Entrada desde el embudo `P1`

- [ ] 3.1 Endpoint para crear `Quote` desde un lead
- [ ] 3.2 Integrado con `buscadorLeads`
- [ ] 3.3 El formulario de webiados.com termina acá
- [ ] **DoD:** `lead → cotización` sin retipear datos

## Sprint 4 — Medición `P2`

- [ ] 4.1 Endpoint de métricas del embudo
- [ ] 4.2 Tasa de cierre calculada
- [ ] 4.3 Consumido por el dashboard de agencia
- [ ] **DoD:** "¿cuántas envié y cuántas cerré?" se responde sin planilla

---

## 📋 Cotizaciones reales (histórico a migrar)

| Cliente | Monto | Estado | Cargada |
|---|---|---|---|
| Macarena Larraín — Opción C | $380.000 + IVA ($452.200) | `SELECTED` | [ ] |
| Pastelería Vientos del Sur — Opción A | $1.040.000 + IVA ($1.237.600) · $49.000/mes | `SENT` | [ ] |
| Pastelería Vientos del Sur — Opción B ⭐ | $1.240.000 + IVA ($1.475.600) · $49.000/mes | `SENT` | [ ] |
| Pastelería Vientos del Sur — Opción C | $1.640.000 + IVA ($1.951.600) · $74.000/mes | `SENT` | [ ] |

*Originales en `../Demos-Webiados-Clientes/docs/Cotizaciones/`* — Macarena es un **PDF**
(`Cotización_Macarena_Larrain.pdf`, 3 opciones: A $150.000 / B $260.000 / C $380.000, más
plan de mantención mensual $25.000 / $50.000); Vientos del Sur es Markdown.

> ✅ `SENT` ya existe y se persiste con su fecha (migración V4). Los montos con IVA de
> arriba los calcula el servicio; ya no se escriben a mano.

*Payloads listos para cargar: [`docs/carga-inicial/`](docs/carga-inicial/).*
