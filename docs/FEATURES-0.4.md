# Cămara 0.4 · ghid

## Unde sunt funcțiile

**Astăzi** arată termenele urgente, mesele zilei, cumpărăturile și bugetul. **Cămara**, **Gătește** și **Cumpărături** rămân accesibile direct. În **Mai multe** găsești Planul meselor, Buget și risipă, Catalog, Bonuri și Backup. Funcțiile pentru consum, anulare, plan și buget sunt explicate și în `FEATURES-0.3.md`.

## Catalog și coduri de bare

Din Astăzi sau Catalog, **Scanează produs/codul** deschide camera. La prima scanare completezi numele, cantitatea unui ambalaj, unitatea și depozitarea. Poți adăuga opțional denumirile prescurtate de pe bon, câte una pe rând. Se acceptă coduri numerice EAN/UPC/GTIN de 8, 12, 13 sau 14 cifre; nu este consultat un serviciu comercial de produse.

La o scanare ulterioară alegi numărul de ambalaje și termenul. Două ambalaje de 500 g adaugă 1000 g în cămară. Termenul se completează pentru lotul nou și nu se ghicește din cod. Produsele cunoscute funcționează din copia locală, iar schimbările se sincronizează ulterior. Poți introduce codul manual când camera nu este disponibilă.

Eliminarea din catalog păstrează alimentele deja adăugate. Același cod nu poate fi asociat simultan cu două produse.

## Bonuri

Deschide **Bonuri → Fotografiază bonul** sau **Alege o fotografie**. Imaginea este redusă și orientată pe dispozitiv, apoi trimisă către API-ul local. Tesseract, cu limbile română și engleză, rulează pe ZimaOS. Modelul conversațional nu este folosit pentru OCR.

În **Verifică bonul** poți edita magazinul, data, totalul, denumirile, cantitățile, unitățile, depozitarea, termenele și prețurile pozițiilor. Bifează numai alimentele care trebuie să intre în cămară. Garanțiile și alte poziții recunoscute ca taxe sunt debifate implicit. Ambalajele cunoscute sunt transformate folosind catalogul; pozițiile neclare sunt marcate pentru verificare. **Arată textul citit** permite compararea cu rezultatul OCR.

**Confirmă bonul** salvează într-o singură acțiune bonul verificat, cheltuiala cu totalul confirmat și alimentele bifate. Totalul cheltuielii nu este recalculat din poziții, deoarece pot exista reduceri sau garanții. Dacă nu sunt identificate produse, poți salva doar cheltuiala și adăuga alimentele manual.

Reimportul exact al aceleiași imagini trimise este blocat, inclusiv între dispozitive. O altă fotografie a aceluiași bon poate avea alt identificator: verifică istoricul pentru a evita introducerea lui de două ori. Fotografia nu este arhivată pe server. Fișierele temporare de cameră din aplicație se curăță la o captură nouă după 7 zile; originalele alese din galerie rămân ale utilizatorului. În istoric se păstrează datele confirmate ale bonului. Citirea unei fotografii cere conexiune la ZimaOS.

## Rețete personale

**Gătește → Rețetele mele → Adaugă rețeta mea**. Completează titlul, timpul estimat, porțiile, tipul mesei, dieta, echipamentele, ingredientele și pașii. Cantitățile sunt pentru numărul de porții declarat. Unește ingredientele repetate într-o singură poziție. Se acceptă maximum 20 de ingrediente și 12 pași.

Din detalii poți confirma consumul, completa cumpărăturile sau adăuga în plan. Rețetele personale sunt disponibile și fără conexiune. În selecția AI sunt incluse când respectă ingredientele, timpul, dieta și echipamentele. Pașii sunt cei salvați, nu sunt rescriși de model. Editarea rețetei invalidează selecția AI memorată. Un plan păstrează copia rețetei aleasă la planificare; editările ulterioare nu schimbă automat mesele existente.

## Copii automate și restaurare

**Mai multe → Backup și restaurare**. Implicit se face o copie zilnică la **03:00, ora Bucureștiului**, cu 14 copii automate păstrate. Poți schimba ora, activarea și retenția sau crea manual o copie. Serverul verifică programarea la fiecare minut; dacă este pornit după ora programată și nu are o copie pentru ziua respectivă, o creează atunci. Sunt păstrate separat cel mult 30 de copii manuale și 30 făcute înaintea restaurărilor.

Copiile SQLite sunt consecvente și verificate. Include inventarul, cumpărăturile, recomandările, planul, bugetele, cheltuielile, istoricul, catalogul, rețetele personale și bonurile confirmate. Nu include modelele AI, fotografiile sau modificările încă nesincronizate pe dispozitive. Cheia de conectare și programarea backupului se păstrează separat. Copiile rămân pe același server: pentru protecție împotriva defectării discului, copiază-le și pe un alt suport.

**Verifică restaurarea** arată data și numărul de înregistrări curente/comparate. Confirmarea explicită **Restaurează datele** înlocuiește datele serverului. Înainte de înlocuire se creează automat o copie a stării curente. Dacă datele se schimbă după previzualizare, aplicația cere o verificare nouă. Reîncercarea aceleiași restaurări nu înlocuiește din nou datele.

După restaurare, celelalte dispozitive pot avea acțiuni offline bazate pe starea veche. Acestea sunt blocate și păstrate local pentru verificare. Din Sincronizare alegi **Renunță la schimbările vechi**, apoi refaci schimbările dorite folosind datele restaurate. Nu sunt reaplicate automat peste copie.
