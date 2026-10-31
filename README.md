# Courier Control 2.0

Android aplikace pro kurýrní live stream (Kick) – ovládání domácího OBS z telefonu
(Sony Xperia 1 VII, Android 15).

## Funkce
- Velká tlačítka **JÍZDA** (kamera aktivní) a **PRIVACY** (kamera skrytá, mikrofon vypnutý)
- Nouzové PRIVACY i z notifikace a z dlaždice v rychlém nastavení
- Automatické **GPS privacy zóny** (vjezd → PRIVACY, výjezd → JÍZDA)
- Automatické **ztlumení mikrofonu při doručení** (stání déle než N sekund) + ruční tlačítko
- Stav spojení s domácím **OBS** (obs-websocket v5), indikace LIVE
- Stav přenosu **DJI kamery** (RTMP zdroj v OBS) s upozorněním při výpadku
- Upozornění při **výpadku internetu** a ztrátě spojení s OBS (vibrace + notifikace)
- Volitelně spouštění akcí ve **Streamer.botu**

## Instalace do telefonu
1. Na telefonu otevři <https://github.com/Tomas69970/courier-control/releases/latest>.
2. Stáhni soubor `CourierControl-2.0.x.apk` a otevři ho.
3. Povol instalaci z tohoto zdroje (prohlížeč / Soubory) a nainstaluj.
4. Při spuštění povol **polohu** („Při používání aplikace“) a **oznámení**.
5. V nastavení aplikace klepni na *Vypnout optimalizaci baterie*.

Nové verze se instalují přes starou (všechny buildy jsou podepsané stejným klíčem
`app/sideload.keystore` – není tajný, slouží jen pro instalaci mimo Google Play).

## Nastavení domácího PC
1. **OBS Studio** 28+ → *Nástroje → Nastavení WebSocket serveru* → zapnout, port 4455, heslo.
2. V OBS vytvoř scény (výchozí názvy `JÍZDA` a `PRIVACY`) – názvy lze změnit v aplikaci.
3. **Tailscale** na PC i na telefonu (stejný účet). V aplikaci jako adresu PC zadej Tailscale IP (100.x.x.x).
4. (Volitelně) **Streamer.bot** → *Servers/Clients → WebSocket Server* → Auto start, adresa `0.0.0.0`, port 8080.

## Sestavení
APK se sestavuje automaticky přes GitHub Actions při každém pushi do `main`
(viz záložka *Actions* a *Releases*). Lokálně: `./gradlew assembleRelease` (Android SDK 35, JDK 17).
