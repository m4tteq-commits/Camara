# Istoricul versiunilor

## 1.2.13 / API 0.9.12

Istoric paginat în Android, conversație AI și pentru rețetele din catalog, tranziții cu un singur ecran activ și sincronizare cu condiții compacte pe colecții. Copiile sunt validate pe disc înainte de restaurare. Serverul comprimă răspunsurile complete, verifică amprentele pachetelor și citește cheile din fișiere montate.

## 1.2.12 / API 0.9.11

Sincronizarea trimite colecțiile modificate când clientul și serverul au aceeași revizie de plecare în cache. Serverul poate furniza pagini pentru cheltuieli, bonuri, cumpărături și istoricul de consum. Planul apelează AI numai la departajarea opțiunilor egale, iar sugestiile identice sunt reutilizate până la o schimbare a datelor sau a zilei.

## 1.2.11 / API 0.9.10

Căutare în listele de rețete din plan și din porțiile gătite. Pornire Android fără decriptarea conexiunii pe firul UI. Verificări AI mai rare și calcule de buget care reutilizează indexul de stoc. Sugestiile pentru alimentele care expiră sunt refolosite în aceeași zi până la o schimbare a datelor.

## 1.2.10 / API 0.9.9

Sincronizare condiționată, protecție la editări concurente, HTTPS obligatoriu în release, optimizarea APK-ului și reducerea cererilor AI redundante.

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

Versiunea Android **0.7.0** poate folosi `https://server.example` cu cheia existentă. În **Listă → Cumpăr din nou** poți selecta produse din cumpărăturile și bonurile anterioare. În coș, editorul produsului arată prețul comparabil pe unitate și ultima achiziție compatibilă. Din detaliile rețetei, **Mod de gătit** deschide pașii pe rând, cu un cronometru și ecranul aprins.

Versiunea Android **0.6.0** aduce o [interfață reorganizată](docs/INTERFACE-0.6.md): sumar Astăzi, căutare și filtre în cămară, meniuri grupate, plan pe zile și butoane principale vizibile în planul automat și coș. Include teme luminoasă și întunecată și păstrează datele, setările AI și compatibilitatea cu serverul 0.5.0.

Versiunea **0.5** a adăugat [plan automat cu buget, modul În magazin și porții gătite pentru alte zile](docs/FEATURES-0.5.md). Planurile cu prețuri necunoscute cer completare și recalculare; confirmarea cumpărăturilor actualizează împreună inventarul, lista și bugetul; porțiile pregătite se rezervă în plan fără a consuma ingredientele de două ori.

Actualizarea **0.4.1** încarcă modelul de pe server numai cât timp Cămara este vizibilă pe un dispozitiv cu opțiunea „Pe server”. După ieșirea de pe ultimul dispozitiv, modelul este eliberat din RAM după aproximativ 15–20 de secunde. Sesiunile expiră și la pierderea conexiunii sau închiderea forțată. Sincronizarea și notificările din fundal nu încarcă modelul. [Detalii despre memoria AI](docs/AI-MEMORY.md).
