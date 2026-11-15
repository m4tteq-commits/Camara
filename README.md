# Cămara

Aplicație **Android nativă**, în Kotlin și Jetpack Compose, pentru inventarul alimentelor, recomandări cu AI local și lista de cumpărături. Serverul și baza de date rulează în rețeaua proprie, pe ZimaOS.

Android **1.0.0** și API **0.7.0** adaugă diagnosticul conexiunii, interfața pe două coloane, importul rețetelor din text și sugestiile rapide fără Ollama. Schema SQLite rămâne la versiunea 6. Android păstrează jurnalul local și folosește operațiile existente pentru sincronizare și reîncercări.

Android **1.2.1** și API **0.9.1** folosesc GroqCloud cu GPT-OSS 120B pentru recomandările online din **Gătește**, cu trecere automată pe OpenRouter la limită sau indisponibilitate. Cheile și modelele se configurează în fișierul privat `.env` de pe ZimaOS. [Configurare ZimaOS](docs/ZIMAOS.md#furnizori-ai-online-opționali)

Android **1.2.2** și API **0.9.2** filtrează ingredientele obligatorii și cele de evitat înainte ca AI-ul să aleagă rețeta. Recomandarea arată timpul, ingredientele lipsă și alimentele apropiate de expirare pe care le poate folosi.

Android **1.2.3** și API **0.9.3** adaugă extragerea ingredientelor și pașilor din text liber. În **Gătește → Rețetele mele**, folosește **Lipește o rețetă → Extrage cu AI**, apoi verifică și salvează. Rețeta salvată poate fi deschisă în **Mod de gătit** sau analizată prin **Sfaturi de la AI**.

Android **1.2.4** și API **0.9.4** corectează sincronizarea în fundal și reutilizarea recomandărilor AI, reduc citirile bazei pentru rețete și îmbunătățesc interpretarea cantităților la importul local.

Android **1.2.5** și API **0.9.5** adaugă **Gătește → Rețetele mele → Importă PDF**. Poți alege până la patru pagini consecutive dintr-un PDF de cel mult 20 MiB. ZimaOS extrage textul sau îl citește cu OCR pentru paginile scanate, iar AI-ul online propune fișe de rețetă pe care le poți verifica și salva individual.

Android **1.2.6** și API **0.9.6** păstrează măsurile casnice ca text și permit salvarea rețetelor cu cantități nespecificate. La importul cu AI, o durată omisă este estimată din pași și marcată în fișă.

Android **1.2.7** și API **0.9.7** încarcă PDF-ul o dată pentru mai multe intervale și reutilizează textul paginilor citite. ZimaOS păstrează sesiunea temporar, până la 30 de minute de la ultima folosire, iar aplicația o reface dacă expiră. Rezultatele devin rețete în bibliotecă după confirmarea salvării.

Android **1.2.9** și API **0.9.8** adaugă conversații cu AI în rețetele personale. Variantele propuse se validează în editor și se salvează ca fișe separate. Favoritele, colecțiile, notițele și timpul real se sincronizează cu ZimaOS. În **Rețetele mele → Link sau fotografie** poți importa o fotografie prin OCR local pe ZimaOS sau o pagină HTTPS care publică o fișă de rețetă. În **Listă** poți corecta raionul oricărui produs, iar în **În magazin** poți salva ordinea raioanelor pentru magazinul curent pe dispozitiv.

Android **0.10.0** adaugă **Scanare în serie** din Astăzi sau Cămară. Verifici ambalajele, depozitarea și termenele înainte de o singură salvare, inclusiv offline. **Listă → Istoric de prețuri** compară bonurile și coșurile confirmate pe kg, litru sau bucată. Pe ecranele late, **Mod de gătit** ține ingredientele în stânga, lângă pasul curent și cronometru. Funcțiile folosesc API-ul existent.

Android **0.9.0** folosește API-ul **0.6.0** pentru pragurile comune din **Cămară → Stoc minim**. În Cămară, **Am folosit** scade cantitatea și o înregistrează în istoricul de consum. Din alerta de termene din **Astăzi**, **Gătește cu ce expiră** deschide rețete potrivite loturilor încă valabile.

Versiunea Android **0.8.0** adaugă cinci palete, mod luminos, întunecat sau automat și opțiunea de a opri animațiile. Deschide **Mai mult → Teme și aspect** pentru a schimba aspectul pe dispozitivul curent. Secțiunea **Mai mult** grupează mesele, cumpărăturile și setările și oferă acces direct la plan și coș.

Versiunea Android **0.7.0** poate folosi `https://camara-m4tteq.duckdns.org` cu cheia existentă. În **Listă → Cumpăr din nou** poți selecta produse din cumpărăturile și bonurile anterioare. În coș, editorul produsului arată prețul comparabil pe unitate și ultima achiziție compatibilă. Din detaliile rețetei, **Mod de gătit** deschide pașii pe rând, cu un cronometru și ecranul aprins.

Versiunea Android **0.6.0** aduce o [interfață reorganizată](docs/INTERFACE-0.6.md): sumar Astăzi, căutare și filtre în cămară, meniuri grupate, plan pe zile și butoane principale vizibile în planul automat și coș. Include teme luminoasă și întunecată și păstrează datele, setările AI și compatibilitatea cu serverul 0.5.0.

Versiunea **0.5** a adăugat [plan automat cu buget, modul În magazin și porții gătite pentru alte zile](docs/FEATURES-0.5.md). Planurile cu prețuri necunoscute cer completare și recalculare; confirmarea cumpărăturilor actualizează împreună inventarul, lista și bugetul; porțiile pregătite se rezervă în plan fără a consuma ingredientele de două ori.

Actualizarea **0.4.1** încarcă modelul de pe server numai cât timp Cămara este vizibilă pe un dispozitiv cu opțiunea „Pe server”. După ieșirea de pe ultimul dispozitiv, modelul este eliberat din RAM după aproximativ 15–20 de secunde. Sesiunile expiră și la pierderea conexiunii sau închiderea forțată. Sincronizarea și notificările din fundal nu încarcă modelul. [Detalii despre memoria AI](docs/AI-MEMORY.md).

## Ce există în versiunea 0.4

- **Astăzi**: termene apropiate sau depășite, mesele zilei, cumpărături rămase și bugetul lunii, cu acces rapid la scanare.
- **Catalog personal și coduri de bare**: EAN/UPC/GTIN, numele produsului, cantitatea unui ambalaj și denumirile de pe bon. După prima asociere, codurile cunoscute se folosesc și offline, fără un catalog comercial extern.
- **Bonuri din fotografii**: cameră sau imagine locală, OCR Tesseract în română/engleză pe ZimaOS, previzualizare editabilă. Confirmarea salvează atomic cheltuiala și alimentele bifate. Fotografiile nu sunt arhivate pe server; reimportul acelorași octeți ai imaginii este blocat.
- **Rețete personale**: editor de ingrediente, cantități, pași, timp, porții, echipamente și filtre. Disponibile pentru gătit, planificare, cumpărături și selecția AI. Pașii rămân cei salvați de utilizator.
- **Backup automat**: zilnic la 03:00, ora Bucureștiului, cu 14 copii automate păstrate implicit. Programare editabilă, copie manuală, previzualizare și confirmare a restaurării. Înainte de restaurare se creează o copie a stării curente. Schimbările offline vechi sunt blocate până la verificare.

- **Am gătit**: previzualizezi consumul unei rețete, ajustezi porțiile și cantitățile, apoi confirmi scăderea stocului. Se folosesc mai întâi loturile cu termen apropiat, cu conversii kg/g și l/ml. Anularea din istoric restabilește cantitățile printr-o acțiune distinctă.
- **Plan săptămânal**: mic dejun, prânz și cină, rețete salvate și porții editabile. Lista de cumpărături cumulează necesarul, rezervă stocul o singură dată și ține cont de termenul alimentelor la data mesei. Mesele gătite sunt excluse; lista existentă este completată fără însumarea repetată a aceluiași necesar.
- **Modificări fără conexiune**: inventar, cumpărături, plan, consum, cheltuieli și buget salvate într-un jurnal local persistent. Sincronizare la revenirea în aplicație, la actualizarea automată și prin WorkManager în fundal. Modificările concurente cer alegerea utilizatorului, fără suprascriere automată. Importul din text și recomandarea AI necesită încă serverul.
- **Buget și risipă**: cheltuieli introduse manual în lei, limite separate pe luni, total și disponibil/depășire. „Am aruncat” scade cantitatea confirmată și păstrează motivul și valoarea opțională. Istoricul arată consumul, risipa și anulările. Valoarea risipei nu este adăugată din nou la cheltuieli.

- Adăugare, editare și eliminare alimente, cu cantitate, unitate, loc de depozitare și termen opțional.
- Evidențierea termenelor apropiate; alimentele cu termen depășit sunt excluse din cererea către AI.
- Import rapid din text: de exemplu „6 ouă, 1 litru lapte, 500 g paste”. Previzualizezi și editezi numele, cantitățile, unitățile, locul și termenele, apoi confirmi salvarea întregului lot. Reîncercarea aceluiași lot nu dublează alimentele.
- AI-ul alege din **36 de rețete**, folosind inventarul, timpul, porțiile, echipamentele și preferințele. Filtre pentru 15/30/45 de minute, doar ingrediente disponibile, vegetarian/vegan și tipul mesei. Se poate selecta inclusiv echipamentul „Microunde”. Cantitățile, lipsurile și pașii provin din catalogul serverului.
- Motor AI selectabil pentru fiecare dispozitiv: **Ollama pe ZimaOS** sau **Qwen3 0.6B pe Android**, prin LiteRT-LM CPU. Modelul Android se descarcă o singură dată din server (344 MB), cu verificare SHA-256.
- Selecții AI memorate pentru cereri repetate; inventarul, filtrele și data participă la cheia rezultatului. Pe server expiră după 24 de ore. Aceeași recomandare nu este salvată repetat.
- Rezumat zilnic al termenelor, implicit la **19:00**, cu oră editabilă și interval de 1/2/3/7 zile. Include termenele depășite; fără server folosește ultima copie locală și semnalează acest lucru. Necesită permisiunea Android pentru notificări. WorkManager poate amâna executarea pentru economisirea bateriei.
- Rețete salvate pe server; ultimele 30 sunt afișate în aplicație.
- Ingrediente lipsă adăugate în lista de cumpărături fără a duplica produsele nebifate cu același nume.
- Listă comună pentru telefon și tabletă, cu produse bifabile.
- Configurarea serverului din aplicație; cheia de acces este criptată folosind Android Keystore.
- Interfață pentru telefon și tabletă: bară de navigare inferioară / laterală, grilă adaptivă, temă deschisă și întunecată.
- Consultarea datelor și modificări fără conexiune, cu numărul acțiunilor în așteptare vizibil în bara de sincronizare.

Aplicația Android comunică cu API-ul tău de pe ZimaOS. Pentru o recomandare prin motorul online selectat, ZimaOS contactează furnizorul configurat. Motoarele locale rulează pe ZimaOS sau pe dispozitiv, iar sugestiile rapide folosesc catalogul sau rețetele salvate. Prima descărcare a dependențelor, imaginilor Docker și modelelor necesită internet.

## Arhitectură

```text
Xiaomi 14 / tabletă Android
  Kotlin + Compose + copie locală pentru citire
  LiteRT-LM CPU opțional + notificări WorkManager
                 │ API cu cheie de acces
                 ▼
ZimaOS: Docker Compose
  API FastAPI ─── SQLite, volum camara-data
       │
       └──────── Ollama, volum ollama-data
```

SQLite de pe server este sursa comună. Aplicația afișează și modificările locale aflate în așteptare; bara de sus arată dacă au fost sincronizate. Jurnalul este salvat atomic și separat pentru fiecare adresă/cheie de server. O acțiune confirmată pe server nu se execută din nou la reîncercare. Consumul și istoricul său se scriu în aceeași tranzacție.

Sincronizarea se încearcă la pornire/revenire, la cererea utilizatorului și la fiecare 30 de secunde cât aplicația este vizibilă. În fundal există o cerere imediată după modificări și una periodică la minimum 15 minute; Android le poate întârzia. Serverul trebuie să fie accesibil din rețea. La conflict, deschide bara de sincronizare și alege varianta. Consumul, anularea și lista calculată se refac după actualizare, pentru a păstra cantitățile coerente. Chiar și cu AI pe dispozitiv, recomandarea cere serverul pentru opțiuni, validarea inventarului și salvare. [Ghid 0.4](docs/FEATURES-0.4.md) · [Consum, plan și buget](docs/FEATURES-0.3.md).

## Pornire pe ZimaOS

Instrucțiunile complete sunt în [docs/ZIMAOS.md](docs/ZIMAOS.md). Pe scurt, copiază **întregul proiect** pe server, creează `.env` din `.env.example`, generează o cheie și rulează din directorul proiectului:

```sh
sh scripts/start_zima.sh
```

În aplicație introduci `http://IP_PRIVAT_SERVER:8088` și cheia din `.env`, pe ambele dispozitive. IP-ul din exemple trebuie înlocuit cu cel real. Nu sunt necesare datele contului ZimaOS.

## Compilarea aplicației Android

Deschide directorul **`android`** în Android Studio. Sunt necesare JDK 17, Android SDK Platform 35 și Build Tools 35.0.0, instalate prin SDK Manager. Dacă există un `local.properties`, acesta trebuie să indice SDK-ul local. Proiectul include wrapperul oficial Gradle 8.11.1 și checksumul distribuției.

Pe Windows, din `android`:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Scriptul `scripts/build_android.ps1` folosește toolchainul local și copiază APK-ul de test în `releases/camara-1.2.7-debug.apk`. Rulează `scripts/build_android.ps1 -Release` pentru `releases/camara-1.2.7.apk`, versiunea ARM64 fără activitățile de depanare. Scriptul semnează versiunea de lansare cu cheia existentă din `.tools/android-user/debug.keystore` pentru actualizarea dispozitivelor deja instalate. Proiectul folosește AGP 8.10.1, Kotlin 2.2.21, LiteRT-LM 0.16.1 și ZXing Android Embedded 4.3.0. Camera cere permisiune la folosirea scanerului sau fotografierii.

Aplicația are `minSdk 26` (Android 8). Instalarea și pornirea au fost verificate prin USB pe Xiaomi 14 cu Android 16/API 36 și Huawei MatePad 11.5 S (SLG-W09), HarmonyOS 4.3, cu compatibilitate Android 12/API 31. Ambele dispozitive au fost configurate pentru serverul `192.168.1.134:8088`. Interfața 0.6.0 este instalată și verificată pe Xiaomi; tableta nu a fost disponibilă pentru această actualizare.

## Dezvoltare și teste server

Python 3.12, apoi din directorul proiectului:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend/requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest -c backend/pytest.ini backend/tests
```

Pentru un server de dezvoltare pe PC:

```powershell
$env:CAMARA_API_KEY = 'o-cheie-aleatoare-de-cel-putin-32-caractere'
$env:CAMARA_DB_PATH = "$PWD\.tools\dev.db"
$env:OLLAMA_BASE_URL = 'http://127.0.0.1:11434'
$env:OLLAMA_MODEL = 'qwen3.5:2b'
.\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --app-dir backend --host 127.0.0.1 --port 8088
```

Pentru conectarea unui dispozitiv real la PC este necesară ascultarea pe IP-ul LAN și o regulă de firewall adecvată. Serverul final este cel ZimaOS.

## AI și hardware

Configurația serverului: **Intel i3-7020U, 12 GB RAM**. Folosește CPU și `qwen3.5:2b`, cu o singură inferență simultană, prompt compact, context de 2048 tokenuri și timeout de 4 minute. Modelul poate fi schimbat în `.env` după descărcare.

AI-ul selectează un identificator de rețetă prin JSON; selecțiile necunoscute sunt respinse. Colecția din `backend/app/catalog.py` și `catalog_extra.py` este completată cu rețetele personale care respectă filtrele. Ingredientele sunt recunoscute prin denumiri și sinonime; conversiile kg/g și l/ml sunt automate. Dimensiunea ambalajelor se preia din catalogul personal; în lipsa ei, cantitățile incompatibile sunt marcate pentru verificare. Importul din text și OCR-ul nu pornesc modelul conversațional. Apa potabilă de bucătărie este presupusă disponibilă. Timpii de preparare sunt estimări. Limita de ieșire AI este de 80 de tokenuri pentru identificatorii rețetelor personale.

Măsurători pe dispozitivele reale, fără cache: serverul aproximativ **1,2 s** după încărcare, Xiaomi 14 **2,2 s**, Huawei **3,1 s**. Prima probă a fiecărei sesiuni a durat mai mult. Cererea API repetată din cache a durat aproximativ **9 ms** în rețeaua locală. Modelele diferă (server 2B / Android 0.6B), deci comparăm configurațiile complete. Serverul rămâne implicit, iar motorul de pe dispozitiv este disponibil pe ambele. Detalii și limite în [docs/AI-BENCHMARK.md](docs/AI-BENCHMARK.md).

Selecția AI și potrivirea denumirilor pot greși. Excluderea termenelor depășite nu certifică siguranța alimentelor, respectarea alergiilor sau adecvarea nutrițională. Rețetele sunt sugestii de verificat, iar adăugarea pe lista de cumpărături este o acțiune explicită.

## Utilizare

Navigarea principală are Astăzi, Cămară, Gătește, Listă și Mai mult. În Mai mult găsești planul meselor, planul automat, porțiile gătite, bugetul, catalogul, bonurile și copiile de siguranță. În Gătește, fila „Rețetele mele” deschide biblioteca și importul din text. „Sugestii rapide” cere catalogului până la cinci idei. Offline, aplicația consultă rețetele salvate. Din detalii poți deschide modul de gătit, confirma consumul sau adăuga rețeta în plan. Bara de stare deschide acțiunile în așteptare și conflictele. Notificările sunt în clopoțel, iar diagnosticul conexiunii este în meniul „Serverul meu”.

## Verificări efectuate în acest mediu

Au trecut **81 de teste server și 49 de teste Android**. Testele acoperă inclusiv catalogul fără Ollama, expirarea sesiunilor AI, importul din text, scrierile cu răspuns pierdut și păstrarea editărilor offline după restaurare. Buildul release a trecut cu zero erori Android Lint.

Un bon de probă generat pentru test a fost citit prin OCR real pe ZimaOS în aproximativ 0,6 s, apoi ales din telefon, corectat și confirmat în aplicație: cheltuială de 9 lei și 1 kg de orez. Acesta nu este un studiu al tuturor formatelor de bon; fotografiile neclare, reducerile și denumirile prescurtate cer verificare. Codul EAN a fost decodat pe Android, iar camera scanerului a fost deschisă pe Xiaomi. Rețeta personală a fost selectată real de ambele motoare AI și planificată din interfață. Copia automată și previzualizarea restaurării au fost inspectate pe Huawei; restaurarea completă a fost verificată într-o bază izolată în containerul serverului.

Pe Xiaomi, opt acțiuni offline au supraviețuit opririi și repornirii aplicației, apoi s-au sincronizat. Un conflict controlat a păstrat valoarea serverului până la rezolvare explicită; Huawei a citit același stoc. Consumul și anularea din interfața telefonului, precum și o cheltuială de 12,34 lei din interfața tabletei au fost verificate pe server. Actualizarea are backup consecvent și păstrează datele și cheia. Modelele AI, importul și notificările au fost verificate pe ambele dispozitive în 0.2. Cheia nu este inclusă în surse sau în APK.

## Referințe tehnice

- [Arhitectură Android cu date locale](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Compatibilitate AGP 8.10 / Gradle / JDK](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [LiteRT-LM pentru Kotlin](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [Qwen3 0.6B pentru LiteRT](https://huggingface.co/litert-community/Qwen3-0.6B)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
- [Tesseract OCR](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)
- [ZimaOS și Docker Compose](https://www.zimaspace.com/docs/zimaos/features)
- [Ollama: generare și schemă JSON](https://docs.ollama.com/api/generate)
- [Modelul inițial](https://ollama.com/library/qwen3.5:2b)
