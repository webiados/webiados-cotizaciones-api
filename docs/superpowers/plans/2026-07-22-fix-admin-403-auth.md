# Fix Admin 403 — Authentication & Health Check Implementation Plan

> **✅ YA IMPLEMENTADO Y EN PRODUCCIÓN — se guarda como registro histórico** (commiteado
> 2026-08-19). Las casillas quedaron sin marcar porque el plan se ejecutó en el worktree
> `fix-admin-403-auth` y nadie volvió a tildarlas; **el código sí está vivo**: `pom.xml` trae
> `spring-boot-starter-actuator` y `SecurityConfig` configura el `exceptionHandling` que
> devuelve **401** en vez de 403. Verificado en prod: `/actuator/health` → `200 {"status":"UP"}`.
> No lo ejecutes de nuevo: se conserva porque explica **por qué** el 401 es 401, que es la
> decisión que el código solo no cuenta.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 403 Forbidden error on authenticated admin endpoints (`GET /api/admin/quotes`, etc.) and resolve the broken `/actuator/health` health check that returns 500.

**Architecture:** The API is a Spring Boot 3.3.5 + Spring Security 6.3 stateless JWT REST API. The root cause is two-fold: (1) the backend returns 403 instead of 401 for unauthenticated/expired-token requests because no `AuthenticationEntryPoint` is configured, making it impossible for the frontend to detect token expiry and trigger re-login; (2) the frontend's `isAdminAuthed()` only checks for a non-null token in localStorage without validating expiry, so a stale/expired token causes the user to land on the dashboard where all API calls silently fail with 403. A secondary issue is the missing `spring-boot-starter-actuator` dependency — the `/actuator/health` endpoint is referenced in `SecurityConfig` but the dependency doesn't exist, so the health check returns 500, potentially causing Railway to flag the service as unhealthy.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Security 6.3, jjwt 0.12.6, Angular 21 (frontend), Railway (deploy), PostgreSQL

## Global Constraints

- All secrets come from environment variables — never hardcode credentials
- Flyway migrations follow `V{n}__{description}.sql` naming; never edit existing migrations
- Frontend content is in Spanish
- Angular components use `OnPush` change detection, signals, and standalone components
- The API project root is `/Users/felipenavarretenavarrete/Desktop/Developer/webiados-cotizaciones-api`
- The frontend project root is `/Users/felipenavarretenavarrete/Desktop/Developer/Webiados`

## Root Cause Analysis

When an admin user's JWT expires (8-hour TTL) or the JWT_SECRET changes between Railway deploys:

1. User visits `/admin` → Angular reads `localStorage('webiados_admin_token')` → finds a token string → `isAdminAuthed()` returns `true` → renders dashboard
2. Dashboard calls `GET /api/admin/quotes` with the expired/invalid Bearer token
3. `JwtAuthFilter` catches `JwtException` during parse → silently continues without setting `SecurityContext`
4. Spring Security's `authorizeHttpRequests` sees unauthenticated request on `.anyRequest().authenticated()` endpoint
5. **No `AuthenticationEntryPoint` is configured** → Spring Security defaults to `Http403ForbiddenEntryPoint` → returns **403** (not 401)
6. Frontend's error handler shows generic error "No se pudo cargar la lista de cotizaciones" but doesn't redirect to login because it can't distinguish 403 (token expired) from 403 (permission denied)

---

### Task 1: Backend — Add AuthenticationEntryPoint returning 401 + Actuator health check

**Files:**
- Modify: `src/main/java/com/webiados/cotizaciones/config/SecurityConfig.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: Unauthenticated requests now return `401` with JSON body `{"type":"about:blank","title":"No autenticado","status":401,"detail":"Token ausente o inválido"}`. `/actuator/health` returns `200 {"status":"UP"}` when DB is reachable.

- [ ] **Step 1: Add `spring-boot-starter-actuator` to `pom.xml`**

Open `pom.xml` and add after the `spring-boot-starter-mail` dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: Configure `AuthenticationEntryPoint` in `SecurityConfig.java`**

Replace the `filterChain` method in `SecurityConfig.java` with:

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/problem+json");
                        response.getWriter().write(
                                "{\"type\":\"about:blank\",\"title\":\"No autenticado\",\"status\":401,\"detail\":\"Token ausente o inválido\"}");
                    })
            )
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/admin/auth/login").permitAll()
                    .requestMatchers("/api/client/quotes/*/unlock").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
}
```

Add this import at the top of the file:

```java
import jakarta.servlet.http.HttpServletResponse;
```

- [ ] **Step 3: Restrict actuator endpoints in `application.yml`**

Add to the end of `application.yml` (before the `logging:` section):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

This exposes only the health endpoint (not info, beans, env, etc.) to avoid leaking internals.

- [ ] **Step 4: Test locally**

```bash
cd /Users/felipenavarretenavarrete/Desktop/Developer/webiados-cotizaciones-api
./mvnw spring-boot:run
```

In another terminal:

```bash
# Health check should return 200
curl -s http://localhost:8080/actuator/health
# Expected: {"status":"UP"} (or {"status":"DOWN"} if no local DB)

# Unauthenticated request should return 401 (not 403)
curl -sv http://localhost:8080/api/admin/quotes 2>&1 | grep "< HTTP"
# Expected: < HTTP/1.1 401

# Request with expired/invalid token should return 401
curl -sv -H "Authorization: Bearer invalid.token.here" http://localhost:8080/api/admin/quotes 2>&1 | grep "< HTTP"
# Expected: < HTTP/1.1 401
```

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/webiados/cotizaciones/config/SecurityConfig.java src/main/resources/application.yml
git commit -m "fix(security): return 401 for unauthenticated requests and add actuator health check

Without an AuthenticationEntryPoint, Spring Security defaulted to 403 for
expired/missing tokens, preventing the frontend from detecting token expiry.
Also adds spring-boot-starter-actuator so /actuator/health works for Railway."
```

---

### Task 2: Frontend — Handle 401 by clearing stale token and redirecting to login

**Files:**
- Modify: `src/app/pages/admin/sections/dashboard/dashboard.ts` (in the Webiados frontend project)
- Modify: `src/app/pages/admin/admin.ts`
- Modify: `src/app/shared/cotizaciones/auth-store.ts`
- Modify: `src/app/pages/admin/sections/detalle/detalle.ts`
- Modify: `src/app/pages/admin/sections/nueva/nueva.ts`

**Interfaces:**
- Consumes: Backend now returns `401` for expired/invalid tokens (Task 1)
- Produces: On any 401 response from admin API calls, the frontend clears the stale token from localStorage and switches the view to `login`

- [ ] **Step 1: Add a `handleAuthError` method to `CotizacionAuthStore`**

In `src/app/shared/cotizaciones/auth-store.ts`, add a method to decode and validate the JWT expiry client-side:

```typescript
isAdminTokenValid(): boolean {
  const token = this._adminToken();
  if (!token) return false;
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.exp * 1000 > Date.now();
  } catch {
    return false;
  }
}
```

- [ ] **Step 2: Use token validation on Admin component init**

In `src/app/pages/admin/admin.ts`, modify the `afterNextRender` callback to validate the token, not just check non-null:

Replace:
```typescript
afterNextRender(() => {
  if (this.authStore.isAdminAuthed()) {
    this.view.set('dashboard');
    return;
  }
  const params = new URLSearchParams(window.location.search);
  if (params.get('login') === '1') {
    this.view.set('login');
  }
});
```

With:
```typescript
afterNextRender(() => {
  if (this.authStore.isAdminTokenValid()) {
    this.view.set('dashboard');
    return;
  }
  this.authStore.clearAdminToken();
  const params = new URLSearchParams(window.location.search);
  if (params.get('login') === '1') {
    this.view.set('login');
  }
});
```

- [ ] **Step 3: Handle 401 in dashboard by redirecting to login**

In `src/app/pages/admin/sections/dashboard/dashboard.ts`, update `loadQuotes` to detect 401 and emit a logout event:

Add a new output:
```typescript
@Output() authExpired = new EventEmitter<void>();
```

Update the error handler in `loadQuotes`:
```typescript
error: (err) => {
  if (err.status === 401) {
    this.authStore.clearAdminToken();
    this.authExpired.emit();
    return;
  }
  this.errorMsg.set('No se pudo cargar la lista de cotizaciones.');
  this.loading.set(false);
},
```

Add the import for `HttpErrorResponse`:
```typescript
import { HttpErrorResponse } from '@angular/common/http';
```

- [ ] **Step 4: Wire `authExpired` event in Admin component**

In `src/app/pages/admin/admin.html`, find the `<app-admin-dashboard>` tag and add the event binding:

```html
<app-admin-dashboard
  (viewDetail)="onViewDetail($event)"
  (createNew)="onCreateNew()"
  (authExpired)="cerrarSesion()"
/>
```

- [ ] **Step 5: Apply same 401 handling to detalle and nueva sections**

In `src/app/pages/admin/sections/detalle/detalle.ts`, add:
```typescript
@Output() authExpired = new EventEmitter<void>();
```

And in every API error handler, add the 401 check before the generic error:
```typescript
if (err.status === 401) {
  this.authStore.clearAdminToken();
  this.authExpired.emit();
  return;
}
```

Do the same for `src/app/pages/admin/sections/nueva/nueva.ts`.

Wire both in `admin.html`:
```html
<app-admin-detalle
  ...existing bindings...
  (authExpired)="cerrarSesion()"
/>
<app-admin-nueva
  ...existing bindings...
  (authExpired)="cerrarSesion()"
/>
```

- [ ] **Step 6: Test the flow**

```bash
cd /Users/felipenavarretenavarrete/Desktop/Developer/Webiados
npm start
```

1. Open `http://localhost:4200/admin`
2. Open DevTools → Application → Local Storage
3. Manually set `webiados_admin_token` to `expired.token.value`
4. Refresh the page → should show login form (not dashboard)
5. Login with valid credentials → should show dashboard with quotes list
6. In localStorage, clear the token → refresh → should show login

- [ ] **Step 7: Commit**

```bash
cd /Users/felipenavarretenavarrete/Desktop/Developer/Webiados
git add src/app/shared/cotizaciones/auth-store.ts src/app/pages/admin/admin.ts src/app/pages/admin/admin.html src/app/pages/admin/sections/dashboard/dashboard.ts src/app/pages/admin/sections/detalle/detalle.ts src/app/pages/admin/sections/nueva/nueva.ts
git commit -m "fix(admin): detect expired JWT and redirect to login on 401

Frontend was showing the dashboard for stale tokens because isAdminAuthed
only checked non-null. Now validates JWT expiry client-side on init and
handles 401 responses by clearing the token and showing login."
```

---

### Task 3: Backend — Deploy to Railway and verify

**Files:**
- No file changes — deployment and verification only

**Interfaces:**
- Consumes: Task 1 changes (AuthenticationEntryPoint + Actuator)

- [ ] **Step 1: Push backend changes and trigger Railway deploy**

```bash
cd /Users/felipenavarretenavarrete/Desktop/Developer/webiados-cotizaciones-api
git push origin main
```

Wait for Railway to build and deploy (check Railway dashboard or CLI).

- [ ] **Step 2: Verify health check returns 200**

```bash
curl -s https://cotizaciones-api-production-e0fb.up.railway.app/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 3: Verify unauthenticated requests return 401 (not 403)**

```bash
curl -sv https://cotizaciones-api-production-e0fb.up.railway.app/api/admin/quotes 2>&1 | grep "< HTTP"
# Expected: < HTTP/2 401

curl -s https://cotizaciones-api-production-e0fb.up.railway.app/api/admin/quotes
# Expected: {"type":"about:blank","title":"No autenticado","status":401,"detail":"Token ausente o inválido"}
```

- [ ] **Step 4: Verify login + list flow works end-to-end**

```bash
# Login (replace with actual admin credentials)
TOKEN=$(curl -s -X POST https://cotizaciones-api-production-e0fb.up.railway.app/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"ADMIN_EMAIL","password":"ADMIN_PASSWORD"}' | jq -r '.token')

# List quotes with fresh token
curl -sv -H "Authorization: Bearer $TOKEN" https://cotizaciones-api-production-e0fb.up.railway.app/api/admin/quotes 2>&1 | grep "< HTTP"
# Expected: < HTTP/2 200
```

---

### Task 4: Frontend — Deploy to Vercel and verify end-to-end

**Files:**
- No file changes — deployment and verification only

- [ ] **Step 1: Push frontend changes**

```bash
cd /Users/felipenavarretenavarrete/Desktop/Developer/Webiados
git push origin main
```

Wait for Vercel to build and deploy.

- [ ] **Step 2: Verify full flow on production**

1. Navigate to `https://webiados.com/admin`
2. Should see login form (not stale dashboard)
3. Login with valid credentials
4. Dashboard should load with quotes list (or empty list if no quotes)
5. "Nueva cotización" should work
6. Close tab, reopen → should show dashboard (token valid for 8 hours)
7. After 8 hours (or manually clear localStorage) → should show login on next visit
