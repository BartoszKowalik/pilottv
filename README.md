# Pilot TV

Pilot do telewizorów Samsung Tizen (2016+) przez Wi-Fi. Bez reklam, bez trackerów, bez dostępu do internetu poza siecią lokalną.

Plan rozwoju: [PLAN.md](PLAN.md).

## Funkcje (Etap 1)
- Wyszukiwanie TV w sieci lub ręczne IP.
- Parowanie przez okno „Zezwól” na TV. Token i certyfikat TV są zapisane w telefonie.
- Krzyżak, VOL, CH, Home, Wstecz, Menu, Źródło, Mute, przyciski mediów, cyfry, przyciski kolorowe.
- Przytrzymanie VOL / CH / strzałek powtarza komendę.
- Przyciski głośności telefonu sterują głośnością TV.
- Włączanie TV przez Wake-on-LAN.
- Wpisywanie tekstu z telefonu w pole tekstowe na TV (klawiatura otwiera się sama, gdy TV pokaże pole tekstowe).
- Touchpad zamiast krzyżaka (przesunięcie = strzałka, stuknięcie = OK).
- Układ minimalny albo pełny pilot. Motyw systemowy, jasny lub ciemny. Regulowana siła wibracji.

## Build
APK buduje GitHub Actions ([.github/workflows/build.yml](.github/workflows/build.yml)):
- push na `main` → APK w zakładce **Actions → build → Artifacts**,
- tag `v*` (np. `v0.1.0`) → APK w **Releases**.

### Klucz podpisu (jednorazowo)
Bez stałego klucza każdy build ma inny podpis i Android nie zainstaluje aktualizacji.

1. Utwórz klucz (wystarczy JDK, np. `keytool` z JDK 11):
   ```
   keytool -genkeypair -v -keystore pilot-release.jks -alias pilot -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Zamień plik na Base64 (PowerShell):
   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("pilot-release.jks")) | Set-Clipboard
   ```
3. W repozytorium: **Settings → Secrets and variables → Actions** dodaj:
   - `KEYSTORE_BASE64` – zawartość ze schowka,
   - `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD` – hasła z kroku 1,
   - `SIGNING_KEY_ALIAS` – `pilot`.
4. Plik `.jks` i hasła przechowuj w bezpiecznym miejscu poza repozytorium. Utrata klucza = brak aktualizacji.

## Instalacja na telefonie
- Pobierz APK z Releases i zainstaluj (zgoda na instalację z nieznanych źródeł).
- Wygodniej: aplikacja **Obtainium** + adres repozytorium. Obtainium sam pobiera nowe wersje.

## Ustawienia TV przed pierwszym użyciem
- Ogólne → Menedżer urządzeń zewnętrznych → Menedżer połączeń urządzeń → Powiadomienie o dostępie: „Tylko pierwsze połączenie”. Usuń stare blokady z listy urządzeń.
- Ogólne → Sieć → Ustawienia eksperckie → Włącz za pomocą urządzenia mobilnego (dla Wake-on-LAN).
- W routerze ustaw stałe IP dla TV (rezerwacja DHCP).
