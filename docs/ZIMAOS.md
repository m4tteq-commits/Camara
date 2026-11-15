# Instalare Cămara pe ZimaOS

## 1. Copiază proiectul

Păstrează fișierele `compose.yaml`, `.env.example` și directorul `backend` în aceeași structură, de exemplu în `/DATA/AppData/camara/source`. Nu instala pachete Python direct în sistemul ZimaOS: serverul aplicației se construiește în container.

Deschide un terminal pe ZimaOS și intră în directorul proiectului:

```sh
cd /DATA/AppData/camara/source
cp -n .env.example .env
```

## 2. Generează o cheie

Dacă ai `openssl`, folosește:

```sh
openssl rand -hex 32
```

Alternativ, generează cheia pe PC cu Python: `python -c "import secrets; print(secrets.token_urlsafe(32))"`.

Editează `.env` și completează `CAMARA_API_KEY` cu rezultatul. Nu trimite cheia în chat și nu o comite în Git. `.env` este exclus prin `.gitignore`.

La actualizare, păstrează cheia existentă și volumele. Fă o copie SQLite înainte de migrarea la API 0.9.9, apoi actualizează întâi serverul și verifică sincronizarea înainte să instalezi Android 1.2.10. Schema 7 se aplică automat. Pentru revenirea integrală la API 0.9.8 este necesară copia făcută înainte de migrare. Păstrează separat baza cu schema 7, care poate conține modificări ulterioare.

Instalarea care a migrat pe ZimaOS la API 0.9.10 folosește un volum nou pentru schema 7 și păstrează volumul anterior. Fișierul `compose.override.yaml` selectează volumul activ. Păstrează acest fișier la actualizările următoare. Volumul cu schema 6 conține numai datele până la migrare.

API 0.9.12 folosește schema 8. În ZimaOS, baza a fost copiată într-un volum separat, cu păstrarea datelor și a identificatorului de sincronizare. Volumele cu schemele 6 și 7 rămân copii anterioare migrărilor. Pentru revenire, salvează întâi datele acceptate de versiunea curentă și ține cont de compatibilitatea schemelor.

Pentru rotația cheii, pune valoarea actuală în `CAMARA_PREVIOUS_API_KEY`, configurează cheia nouă în `CAMARA_API_KEY`, apoi repornește API-ul. Mută fiecare dispozitiv la cheia nouă după ce a terminat sincronizarea. După verificare, elimină cheia anterioară și repornește din nou API-ul.

Pentru fișierele de chei, păstrează un director privat în afara sursei și setează `CAMARA_SECRETS_DIR` în `.env`. Creează câte un fișier pentru `camara_api_key`, `camara_previous_api_key`, `openrouter_api_key` și `groq_api_key`. Lasă goale fișierele pentru opțiunile nefolosite. Containerul rulează cu UID 10001 și trebuie să poată citi fișierele. Folosește permisiuni 0600 și proprietarul 10001 pe ZimaOS. Pornește Compose cu `-f compose.yaml -f compose.secrets.yaml` și include override-ul local existent dacă selectează volumul de date. Variabilele din container indică fișierele montate.

Actualizarea 0.5 folosește schema SQLite 5, cu migrare aditivă pentru loturi gătite și cumpărături confirmate. Ghidul noilor funcții este în `docs/FEATURES-0.5.md`. Copiile mai vechi pot fi restaurate cu noua versiune; noile tabele sunt incluse în backupurile făcute ulterior.

API-ul 0.6 migrează baza la schema 6 și include pragurile de stoc în copiile de siguranță. Fă o copie din aplicație înainte de actualizarea serverului, apoi instalează Android 0.9 pentru cele trei funcții noi. Copiile cu schema 5 rămân restaurabile pe API-ul 0.6.

## 3. Construiește și pornește

```sh
docker compose config --quiet
docker compose build api
docker compose run --rm --no-deps --user 0 --entrypoint python api -c "import os; os.chown('/backups',10001,10001); os.chmod('/backups',0o700)"
docker compose up -d
docker compose exec ollama ollama pull qwen3.5:2b
docker compose ps
```

Pe instalarea ZimaOS verificată aici, pluginul Compose se poate apela direct:

```sh
export DOCKER_CONFIG=/DATA/AppData/camara/.docker
cd /DATA/AppData/camara/source
/usr/lib/docker/cli-plugins/docker-compose -p camara ps
```

Folosește această formă în loc de `docker compose` dacă subcomanda nu este găsită. Scriptul `start_zima.sh` din pachet folosește deja această cale.

Serverul nu descarcă automat un model la fiecare pornire. Modelul rămâne în volumul `ollama-data` după descărcarea inițială. AI este accesibil doar prin API-ul aplicației; portul Ollama nu este publicat pe host.

Din versiunea 0.4.1, modelul se încarcă în RAM când aplicația este vizibilă cu motorul „Pe server”, iar după ieșirea de pe ultimul dispozitiv este eliberat în aproximativ 15–20 de secunde. Sesiunile expiră și la pierderea conexiunii. Containerul Ollama rămâne disponibil cu un consum mic de bază. Detalii în `docs/AI-MEMORY.md`; păstrează `OLLAMA_KEEP_ALIVE=90s` din Compose la actualizare.

Fișierul Compose folosește `build: ./backend`; trebuie executat din directorul proiectului, cu sursele disponibile. Simplul import al acestui YAML în interfața de aplicații personalizate ZimaOS poate să nu aibă contextul necesar construirii imaginii. Pentru prima instalare, folosește terminalul. Un pachet cu imagine preconstruită poate fi pregătit ulterior.

## 4. Conectează dispozitivele

În **⋮ → Serverul meu**, introdu adresa HTTPS de la Caddy și aceeași cheie de acces pe ambele dispozitive. Android păstrează coșul și jurnalul offline la schimbarea adresei când cheia și identificatorul bazei coincid. Dacă routerul nu poate întoarce conexiunea la IP-ul public, completează explicit câmpul **IP LAN**.

Compose publică implicit portul 8088 doar pe loopback. Dacă proxy-ul Caddy existent contactează API-ul prin IP-ul gazdei, adaugă în `.env` valoarea `CAMARA_BIND_ADDRESS` cu IP-ul privat pe care îl folosește Caddy și restricționează accesul la acel port la nivelul gazdei. Verifică traseul din Caddy înainte să schimbi bind-ul. Publică numai HTTPS pe portul 443 și verifică ruta prin domeniul DuckDNS.

În **Serverul meu → Testează conexiunea**, verifici ruta folosită și versiunea API. Câmpul **IP LAN** poate fi schimbat sau golit. Când este completat, telefonul folosește adresa IP pentru conexiunea locală și verifică certificatul pe numele din adresa HTTPS configurată. Salvează setarea după ce ai verificat ruta.

`GET /v1/status` necesită cheia API și afișează starea SQLite, schema și data ultimei copii de siguranță. Docker folosește `/livez` pe loopback pentru a verifica procesul. Pentru diagnosticarea bazei folosește `/health` sau `/v1/status` cu autentificare. Păstrează un singur worker Uvicorn, deoarece programarea copiilor, sesiunile PDF și limitele de cereri au stare în proces.

## 5. Model opțional pentru AI pe Android

Motorul implicit este cel de pe server. Pentru opțiunea „Pe dispozitiv”, API-ul distribuie un fișier verificat din directorul `CAMARA_MODELS_DIR`, implicit `/DATA/AppData/camara/models`, montat numai pentru citire. Pe instalarea curentă fișierul există deja, iar ambele dispozitive l-au descărcat.

Pentru o instalare nouă, pe PC, din rădăcina proiectului sau a pachetului dezarhivat:

```powershell
python scripts/fetch_local_ai.py
```

Scriptul descarcă versiunea fixată a modelului public Qwen3 0.6B și verifică SHA-256. Copiază `.tools/models/qwen3-0.6b.litertlm` în `/DATA/AppData/camara/models/qwen3-0.6b.litertlm` pe server, de exemplu prin SCP. Directorul trebuie să poată fi traversat de container, iar fișierul să poată fi citit (moduri uzuale 755 și 644). Dacă schimbi calea din `.env`, repornește serviciul API cu Compose pentru aplicarea montării.

În aplicație: **Gătește → Descarcă AI pe dispozitiv → Pe dispozitiv**. Sunt necesari aproximativ 400 MB liberi pentru descărcare; modelul final are 344 MB. Transferul folosește cheia API și hashul este verificat și pe Android. Modelul nu este inclus în APK sau în pachetul server. [Sursa modelului și licența](https://huggingface.co/litert-community/Qwen3-0.6B); revizia și măsurătorile sunt documentate în `docs/AI-BENCHMARK.md` din proiectul complet.

## Furnizori AI online opționali

Configurează GroqCloud ca furnizor principal și OpenRouter ca rezervă în fișierul privat `.env` de pe ZimaOS.

```dotenv
OPENROUTER_API_KEY=
OPENROUTER_MODEL=nvidia/nemotron-3.5-super-vl:free
GROQ_API_KEY=
GROQ_MODEL=openai/gpt-oss-120b
```

Modelul de rezervă implicit este Nemotron 3.5 Super VL gratuit. Poți seta și `openrouter/free` pentru selecția automată a unui model gratuit. Modelele folosite trebuie să accepte răspunsuri cu schemă JSON.

GroqCloud folosește implicit `openai/gpt-oss-120b` cu nivelul de raționament `high`. Alege un model compatibil cu schema JSON strictă dacă îl schimbi. [Chei GroqCloud](https://console.groq.com/keys) · [Modele cu schemă JSON strictă](https://console.groq.com/docs/structured-outputs)

După actualizarea surselor și editarea fișierului, aplică setările cu `docker compose up -d --no-deps --build api`, folosind comanda Compose documentată mai sus. În **Gătește → Sugestii → Motorul AI**, GroqCloud este opțiunea online. ZimaOS încearcă OpenRouter la limitarea cererilor, eroare de serviciu, răspuns invalid sau depășirea timpului de răspuns. Dacă și OpenRouter este indisponibil, aplicația afișează sugestiile filtrate din catalog.

În **Filtre și echipamente**, completează ingredientele obligatorii sau de evitat, separate prin virgule. ZimaOS aplică și cererile explicite din text, cum ar fi „neapărat cu linte, fără ciuperci”. Cererile ambigue primesc instrucțiuni de confirmare. Filtrarea verifică ingredientele declarate în rețetă. Furnizorul online primește preferința și, pentru fiecare dintre cele cel mult cinci opțiuni, titlul, timpul, ingredientele, ce lipsește și ce expiră în următoarele trei zile. Explicația din aplicație folosește datele calculate când a fost propusă rețeta. Planificarea automată folosește setarea proprie de AI local.

## Notificări zilnice

Din pictograma clopoțel poți activa rezumatul, schimba ora implicită **19:00** și intervalul de termene (implicit 3 zile) sau trimite o notificare de test. Android 13+ cere permisiunea pentru notificări. Programarea este făcută pe fiecare dispozitiv prin WorkManager și se reface după execuție; economisirea bateriei poate întârzia rezumatul. Fără server se consultă copia locală, cu mențiunea că datele pot fi vechi.

## Date și backup

### Copie separată pe PC

Pe PC, din proiect și cu Tailscale conectat, rulează comanda cu cheia SSH folosită deja pentru server.

```powershell
$key = Join-Path $env:USERPROFILE '.ssh\codex_zima_ed25519'
.\.venv\Scripts\python.exe .\scripts\export_zima_backup.py --host m4tteq@100.98.52.79 --host-key-alias 192.168.1.134 --identity $key
```

Scriptul cere un backup consecvent API-ului de pe ZimaOS. După transfer, verifică SHA-256 și testează restaurarea într-o bază temporară pe PC. Copia SQLite și metadatele sunt publicate în `%LOCALAPPDATA%\Camara\Backups\<id>` după ce trec verificările. Pentru altă destinație, folosește `--output` cu un folder privat din afara proiectului.

Versiunea 0.4 include OCR Tesseract local (`ron` și `eng`) în container și montează directorul `CAMARA_BACKUPS_DIR`, implicit `/DATA/AppData/camara/backups`. Pasul de pregătire de mai sus acordă acces UID-ului 10001 al aplicației; scriptul `start_zima.sh` îl face automat. Păstrează acest director la actualizări. Modelele AI, baza activă și fotografiile temporare nu sunt incluse în arhiva sursă.

Din **Mai multe → Backup și restaurare**, programarea implicită este zilnic la 03:00, cu 14 copii automate păstrate. Sunt disponibile copii manuale, previzualizarea restaurării și o copie automată înainte de fiecare restaurare. Restaurarea schimbă identificatorul stării comune, astfel încât modificările offline vechi să nu fie reaplicate automat. Ghidul complet este în `docs/FEATURES-0.4.md`. Copiile de pe același server trebuie duplicate separat pe alt suport pentru protecție la defectarea discului.

Versiunea 0.3 adaugă planificare, consum cu anulare, buget, risipă și jurnal offline. La actualizare, construiește și repornește serviciul API cu sursele noi, păstrând `.env` și volumele. Migrarea SQLite este aditivă. Acțiunile offline ajung pe server numai după sincronizarea dispozitivelor; backupul serverului nu poate include schimbările încă aflate exclusiv pe telefon sau tabletă.

- `camara-data`: baza de date SQLite.
- `ollama-data`: modelele AI, descărcabile din nou.
- `/DATA/AppData/camara/models`: modelul opțional distribuit dispozitivelor, descărcabil din nou.
- `.env`: configurația și cheia de acces; păstrează-l separat, cu acces restrâns.

`docker compose down` păstrează volumele. Nu folosi opțiunea `--volumes` dacă vrei să păstrezi datele.

Pentru un backup consecvent al bazei, din directorul proiectului, cu serviciul pornit:

```sh
mkdir -p backups
docker compose exec -T api python -c "import sqlite3; s=sqlite3.connect('/data/camara.db'); d=sqlite3.connect('/data/camara-backup.db'); s.backup(d); d.close(); s.close()"
docker compose cp api:/data/camara-backup.db ./backups/camara-backup.db
```

Acest exemplu suprascrie copia de backup anterioară, nu baza activă. Mută sau redenumește copiile pentru istoric. Nu copia doar `camara.db` în timpul scrierilor: SQLite folosește WAL și trebuie un backup consecvent.

## Diagnosticare

```sh
docker compose logs --tail=80 api
docker compose logs --tail=80 ollama
docker compose exec ollama ollama list
```

Dacă API-ul refuză pornirea, verifică cheia (minimum 32 de caractere). Dacă modelul nu este pregătit, verifică descărcarea și numele din `.env`. Dacă serverul depășește 4 minute, poți încerca `qwen2.5:0.5b`; calitatea în română trebuie reevaluată. La schimbarea modelului:

```sh
docker compose exec ollama ollama pull qwen2.5:0.5b
```

Actualizează `OLLAMA_MODEL` în `.env`, apoi rulează `docker compose up -d`. Cu Qwen3.5 2B și promptul compact, probele pe i3-7020U au durat aproximativ 1,2 secunde după încărcarea modelului. O cerere repetată poate veni din cache și nu reprezintă timpul de inferență.
