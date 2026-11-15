# Alegerea motorului AI prin măsurători

Datele comune rămân pe ZimaOS indiferent de locul inferenței. Versiunea 0.2 poate alege rețete pe server sau direct pe fiecare dispozitiv Android. Ambele variante au fost măsurate pe hardware real în 31 octombrie 2026.

## Implementat acum

- Ollama pe server, `qwen3.5:2b`, o singură inferență simultană și context 2048.
- LiteRT-LM 0.16.1 pe Android, Qwen3 0.6B int4, CPU cu 4 fire și context 1024. Gândirea extinsă este dezactivată. Probele 0.2 de mai jos au avut limita de 40 de tokenuri de ieșire; versiunea 0.4 permite 80 pentru identificatorii rețetelor personale.
- AI-ul alege un identificator valid din maximum 5 opțiuni filtrate din colecția de 36 de rețete. Ingredientele, cantitățile și pașii sunt furnizați de catalog. Android constrânge răspunsul la o schemă JSON cu identificatorii permiși.
- Selecțiile repetate folosesc cache; o schimbare a inventarului, filtrelor, datei sau catalogului produce altă cheie. Serverul păstrează cache-ul 24 de ore, separat pentru fiecare model.
- Timpul total al generării este salvat în `generation_ms` și afișat în detaliile rețetei.
- Script pentru măsurarea backendului real: `scripts/benchmark_server.py`.

## Rezultate

Cerere de maximum 20 de minute, o porție, inventar comun cu ouă, brânză, roșii și ulei, pe lângă alimentele existente. Fiecare configurație a efectuat trei selecții reale, cu cache-ul de selecție ocolit sau golit între probe.

| Configurație | Prima probă a sesiunii | A doua | A treia |
|---|---:|---:|---:|
| ZimaOS, i3-7020U / 12 GB, Qwen3.5 2B | 17,204 s | 1,169 s | 1,174 s |
| Xiaomi 14, Qwen3 0.6B CPU | 3,453 s | 2,199 s | 2,194 s |
| Huawei SLG-W09, Qwen3 0.6B CPU | 4,931 s | 3,169 s | 3,079 s |

O pereche de cereri prin API-ul real al serverului a durat **1,220 s fără cache**, apoi **0,009 s din cache**. Repetarea a reutilizat aceeași recomandare salvată. Pe Xiaomi, fluxul complet din interfață cu filtrele 15 minute + vegetarian + doar inventar a ales o omletă fără ingrediente lipsă: selecția nativă a durat **2,380 s**, iar rezultatul a fost validat și salvat pe ZimaOS.

Timpii nativi includ inițializarea motorului în prima probă și măsoară selecția locală, fără transferul opțiunilor și salvarea pe server. Probele directe pe server măsoară selecția și validarea backendului; perechea API include și transportul LAN. Prima probă nu garantează un cache rece al sistemului de operare. Promptul serverului a fost ulterior uniformizat cu antete compacte în engleză; proba API din interfață verifică și această versiune.

Sunt numai trei probe per configurație, fără studiu de baterie, temperatură sau memorie pe termen lung. Modelele, cuantizarea și runtime-urile diferă: comparația evaluează soluțiile complete, nu doar procesoarele. Selecțiile au fost valide, dar aceasta nu reprezintă o evaluare extinsă a preferințelor în limbaj natural.

**Alegere implicită: serverul**, mai rapid după încărcare în aceste probe și comun ambelor dispozitive. „Pe dispozitiv” rămâne disponibil pentru cine preferă inferența pe Android. Nu transformă telefonul într-un server pentru tabletă. Chiar și în acest mod, aplicația cere ZimaOS pentru filtrarea opțiunilor, verificarea stocului și salvare.

## Test pe server

Adaugă în aplicație câteva ingrediente de test. Pe PC, setează `CAMARA_BENCHMARK_URL` și `CAMARA_API_KEY` în mediul procesului, apoi:

```powershell
$env:CAMARA_BENCHMARK_URL = 'http://192.168.1.134:8088'
.\.venv\Scripts\python.exe scripts/benchmark_server.py
```

Scriptul trimite de patru ori aceeași cerere, prin urmare măsoară comportamentul real al API-ului, inclusiv cache-ul. Poate crea o recomandare salvată și o reutilizează la repetare. Afișează timpul, indicatorul de cache și ID-ul rezultatului; nu afișează cheia sau inventarul. Nu interpreta o cerere din cache ca viteză de inferență. Pentru măsurătorile directe de mai sus, cache-ul furnizorului a fost golit între cele trei probe, fără a șterge datele utilizatorului.

## Model Android și reproducere

În „Gătește”, descarcă modelul din server, apoi alege „Pe dispozitiv”. Pe dispozitivele configurate în această sesiune modelul este deja instalat. Fișierul are **343.490.560 octeți**, este păstrat în spațiul privat al aplicației și verificat SHA-256 înainte de utilizare.

- Sursă: [litert-community/Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B), licență Apache-2.0.
- Revizie: `2670e142a0d16a1b685be6604e5b4f2b31a826d7`.
- Fișier: `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`.
- SHA-256: `bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0`.

Buildul debug include o activitate de benchmark accesibilă numai apelanților cu permisiunea de sistem `DUMP`, de exemplu ADB. Activitatea nu există în manifestul release. Pe un dispozitiv conectat și configurat:

```sh
adb shell am start -W -n ro.camara.app/.debug.AiBenchmarkActivity --ez finish true
# După terminarea celor trei probe:
adb shell run-as ro.camara.app cat files/benchmark.json
```

Activitatea descarcă modelul dacă lipsește, ocolește cache-ul de selecție și nu salvează rețete de test. Nu dezactivează verificările de format sau lista de identificatori permiși. Pentru teste cu mai multe dispozitive, adaugă `adb -s SERIAL`.
