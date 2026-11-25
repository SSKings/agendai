#!/usr/bin/env python3
"""Teste ponta a ponta da pilha de produção do Agendaí (nginx TLS → API → PostgreSQL/Redis), só com a biblioteca padrão.

Cria dois donos de estabelecimento e confere sessão (cookie de refresh, rotação, reuso, logout), isolamento entre
estabelecimentos e cabeçalhos de segurança. Os dados ficam no banco (use num ambiente de teste).

    python3 deploy/e2e-smoke.py https://localhost:18443           # certificado autoassinado: não confere o TLS
    python3 deploy/e2e-smoke.py https://agendai.seudominio.com.br --verify-tls

O nginx limita entrada/cadastro a 10/min por IP: rodar duas vezes seguidas pode dar 429; espere 1 minuto.
"""
import json
import ssl
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "https://localhost:18443"
CTX = ssl.create_default_context() if "--verify-tls" in sys.argv else ssl._create_unverified_context()
PASSWORD = "Vagalume4821"
COOKIE = "agendai_refresh"
results = []


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(urllib.request.HTTPSHandler(context=CTX), NoRedirect)


def call(method, path, token=None, body=None, cookie=None, headers=None, base=BASE):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    if cookie:
        h["Cookie"] = f"{COOKIE}={cookie}"
    req = urllib.request.Request(base + path, method=method, headers=h,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with OPENER.open(req, timeout=20) as r:
            status, raw, hdrs = r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        status, raw, hdrs = e.code, e.read(), e.headers
    try:
        data = json.loads(raw) if raw else None
    except ValueError:
        data = raw.decode(errors="replace")
    return status, data, hdrs


def set_cookie(hdrs):
    """Cabeçalho Set-Cookie do refresh (texto inteiro) e o valor."""
    for c in hdrs.get_all("Set-Cookie") or []:
        if c.startswith(COOKIE + "="):
            return c, c.split(";", 1)[0].split("=", 1)[1]
    return "", None


def refresh(cookie, csrf=True):
    return call("POST", "/api/auth/refresh", cookie=cookie, headers={"X-Requested-With": "agendai"} if csrf else None)


def check(name, cond, detail=""):
    results.append(bool(cond))
    print(("OK    " if cond else "FALHA ") + name + (f"  [{detail}]" if detail and not cond else ""))


def register(name, business):
    s, b, h = call("POST", "/api/auth/register", body={"name": name, "email": f"e2e-{uuid.uuid4().hex[:10]}@teste.com",
                                                        "password": PASSWORD, "businessName": business})
    if s != 201:
        sys.exit(f"cadastro falhou ({s}): {b} — se for 429, espere 1 minuto")
    return b, h


def main():
    # ---- cadastro: dono + estabelecimento, sessão com cookie ----
    ana, h = register("Ana Souza", "Barbearia da Ana")
    raw_cookie, cookie = set_cookie(h)
    attrs = {a.strip().lower() for a in raw_cookie.split(";")[1:]}
    check("cadastro devolve access token e cookie de refresh", ana["accessToken"] and cookie)
    check("cookie HttpOnly, Secure, SameSite=Strict, só em /api/auth",
          {"httponly", "secure", "samesite=strict", "path=/api/auth"} <= attrs, raw_cookie)
    s, me, _ = call("GET", "/api/me", ana["accessToken"])
    tenant = me["tenants"][0]
    check("Ana é dona do estabelecimento novo (endereço a partir do nome)",
          s == 200 and tenant["role"] == "OWNER" and tenant["slug"].startswith("barbearia-da-ana"), me)
    check("estabelecimento visível para a dona", call("GET", f"/api/tenants/{tenant['id']}", ana["accessToken"])[0] == 200)

    # ---- isolamento ----
    eve, _ = register("Eve Intrusa", "Studio Eve")
    s, _, _ = call("GET", f"/api/tenants/{tenant['id']}", eve["accessToken"])
    check("outro dono recebe 404 no estabelecimento da Ana (nem sabe que existe)", s == 404, s)
    check("sem token: 401", call("GET", "/api/me")[0] == 401)

    # ---- catálogo (fase 1): dono cadastra; outro estabelecimento não vê nem usa ----
    base = f"/api/tenants/{tenant['id']}"
    s, svc, _ = call("POST", base + "/services", ana["accessToken"],
                     {"name": "Corte", "durationMinutes": 30, "bufferMinutes": 5, "priceCents": 4500, "active": True})
    check("dono cadastra serviço", s == 201 and svc["priceCents"] == 4500, svc)
    s, pro, _ = call("POST", base + "/professionals", ana["accessToken"], {"name": "João", "active": True, "serviceIds": [svc["id"]]})
    check("dono cadastra profissional com o serviço", s == 201 and pro["serviceIds"] == [svc["id"]], pro)
    check("outro dono não lista os serviços da Ana", call("GET", base + "/services", eve["accessToken"])[0] == 404)
    s, _, _ = call("POST", f"/api/tenants/{call('GET', '/api/me', eve['accessToken'])[1]['tenants'][0]['id']}/professionals",
                   eve["accessToken"], {"name": "Intruso", "active": True, "serviceIds": [svc["id"]]})
    check("serviço da Ana não pode ser ligado a profissional da Eve", s == 400, s)

    # ---- agenda (fase 1b): expediente, bloqueio e horários livres, no fuso do estabelecimento ----
    day = (datetime.now(ZoneInfo(tenant["timeZone"])) + timedelta(days=7)).date()
    s, _, _ = call("PUT", f"{base}/professionals/{pro['id']}/working-hours", ana["accessToken"],
                   {"intervals": [{"weekday": day.isoweekday(), "start": "09:00", "end": "11:00"}]})
    check("dono define o expediente da semana", s == 200, s)
    s, off, _ = call("POST", base + "/time-off", ana["accessToken"],
                     {"professionalId": pro["id"], "start": f"{day}T09:00", "end": f"{day}T09:30", "reason": "Médico"})
    check("dono bloqueia meia hora", s == 201, off)
    s, av, _ = call("GET", f"{base}/availability?professionalId={pro['id']}&serviceId={svc['id']}&date={day}", ana["accessToken"])
    check("horários livres: expediente, bloqueio e atendimento + preparo (35 min) na hora local",
          s == 200 and [x[11:16] for x in av["slots"]] == ["09:30", "09:45", "10:00", "10:15"]
          and all(x.startswith(f"{day}T") for x in av["slots"]), av)
    check("bloqueios da Ana: 404 para outro dono", call("GET", f"{base}/time-off?from={day}&to={day}", eve["accessToken"])[0] == 404)
    eve_base = f"/api/tenants/{call('GET', '/api/me', eve['accessToken'])[1]['tenants'][0]['id']}"
    s, theirs, _ = call("GET", f"{eve_base}/time-off?from={day}&to={day}", eve["accessToken"])
    check("bloqueios da Ana não aparecem no estabelecimento da Eve", s == 200 and theirs == [], theirs)

    # ---- refresh: anti-CSRF, rotação e reuso ----
    check("refresh sem o cabeçalho anti-CSRF: 403", refresh(cookie, csrf=False)[0] == 403)
    s, b, h = refresh(cookie)
    _, cookie2 = set_cookie(h)
    check("refresh troca o cookie e devolve token novo", s == 200 and cookie2 and cookie2 != cookie)
    check("token renovado funciona", call("GET", "/api/me", b["accessToken"])[0] == 200)
    check("cookie antigo reusado: 401", refresh(cookie)[0] == 401)
    check("reuso derruba a família inteira (o cookie novo também)", refresh(cookie2)[0] == 401)

    # ---- login e logout ----
    s, b, h = call("POST", "/api/auth/login", body={"email": me["email"], "password": PASSWORD})
    _, cookie3 = set_cookie(h)
    check("login", s == 200 and cookie3)
    check("senha errada: 401 genérico", call("POST", "/api/auth/login", body={"email": me["email"], "password": "Errada12345"})[0] == 401)
    s, _, h = call("POST", "/api/auth/logout", b["accessToken"], cookie=cookie3)
    check("logout: 204 e cookie apagado", s == 204 and "max-age=0" in set_cookie(h)[0].lower(), set_cookie(h)[0])
    check("depois do logout: access token recusado", call("GET", "/api/me", b["accessToken"])[0] == 401)
    check("depois do logout: refresh recusado", refresh(cookie3)[0] == 401)

    # ---- entrada e borda ----
    s, _, _ = call("POST", "/api/auth/login", body={"email": me["email"], "password": PASSWORD, "role": "ADMIN"})
    check("campo desconhecido no JSON é recusado", s == 400, s)
    check("actuator não sai para a internet", call("GET", "/actuator/health")[0] == 404)
    s, _, h = call("GET", "/")
    check("front-end servido", s == 200)
    csp = h.get("Content-Security-Policy", "")
    check("cabeçalhos de segurança", "script-src 'self'" in csp and "frame-ancestors 'none'" in csp
          and h.get("Strict-Transport-Security", "").startswith("max-age=") and h.get("X-Content-Type-Options") == "nosniff", csp)
    check("API sem cabeçalho duplicado", len(call("GET", "/api/me")[2].get_all("X-Frame-Options") or []) == 1)
    s, _, h = call("GET", "/", base=BASE.replace("https://", "http://").replace(":18443", ":18080"))
    check("http redireciona para https", s == 301 and h.get("Location", "").startswith("https://"), s)

    print(f"\n{sum(results)}/{len(results)} verificações passaram")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
