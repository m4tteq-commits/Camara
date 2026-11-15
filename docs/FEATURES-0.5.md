# Cămara 0.5 · plan cu buget, cumpărături și porții gătite

## Plan automat cu buget

Intră în **Mai multe → Plan automat cu buget** sau în **Planul meselor → Plan automat cu buget**. Alegi data de început, 1–7 zile, mesele, porțiile, timpul maxim și suma pentru cumpărăturile necesare. Implicit se propun șapte cine pentru o persoană. Din opțiuni poți modifica echipamentele, ingredientele excluse, preferința pentru alegerea AI și folosirea AI-ului local de pe server.

Planul păstrează mesele existente din interval și completează doar pozițiile libere. Folosește inventarul o singură dată, cu conversii kg/g și l/ml, și ține cont de termenul alimentelor la data mesei. Porțiile deja gătite eligibile sunt rezervate înainte de propunerea unei noi preparări. Ingredientele lor nu sunt cumpărate sau scăzute încă o dată.

Prețurile provin din bonurile confirmate și din cumpărăturile confirmate în aplicație, sau sunt completate de tine. Nu se caută prețuri pe internet. Costul este o estimare a cumpărăturilor suplimentare; alimentele deja cumpărate nu se plătesc din nou în estimare. Cantitatea de cumpărat se rotunjește la ambalajul asociat prețului. La un bon cu mai multe ambalaje, verifică ambalajul și prețul afișate și ajustează-le dacă este necesar.

**Prețurile necunoscute nu sunt tratate ca zero.** Propunerea rămâne neconfirmabilă până completezi prețurile și recalculezi. Poți edita atât cantitatea ambalajului, cât și prețul său. Dacă totalul depășește suma aleasă sau lipsesc mese eligibile, aplicația afișează problema și nu salvează automat un plan neîncadrat. În acest caz ajustezi bugetul, mesele, filtrele sau prețurile. Algoritmul încearcă și o variantă mai economică atunci când diversitatea ar depăși bugetul.

AI-ul local de pe ZimaOS alege opțional o prioritate de început din rețete eligibile. Calculele ulterioare ale cantităților, termenelor, rezervărilor și bugetului sunt determinate de aplicație. Ingredientele și pașii provin din catalogul verificat și rețetele personale. Dacă AI-ul nu răspunde, propunerea calculată rămâne disponibilă, cu această mențiune. Excluderile de ingrediente se introduc separat prin nume; verifică ingredientele propunerii înainte de preparare.

Confirmarea salvează împreună mesele noi și completarea listei de cumpărături. Datele folosite la calcul sunt verificate din nou la sincronizare. Dacă alt dispozitiv le-a schimbat, propunerea trebuie recalculată. Dintr-o masă salvată poți deschide rețeta și pașii.

## În magazin

Din **Cumpărături → În magazin · deschide coșul**, aplicația pregătește un coș pe baza produselor nebifate. Poți adăuga și alte produse. Pentru fiecare poziție verifici cantitatea totală cumpărată, unitatea, **prețul total al poziției**, locul de depozitare și termenul opțional. De exemplu, pentru două ambalaje a câte 500 g și un total plătit de 12 lei, introduci 1000 g și 12 lei. Poți debifa „Adaugă în cămară” pentru poziții care trebuie doar înregistrate ca cheltuială.

Coșul și modificările sale sunt salvate pe dispozitiv, separat pentru fiecare server. Poți ieși din aplicație și reveni. Coșul neconfirmat nu este o cheltuială și nu modifică inventarul comun. Un preț necompletat este semnalat și împiedică confirmarea plății.

La confirmare, aplicația verifică datele și înregistrează într-o singură acțiune: alimentele selectate, o cheltuială egală cu totalul, istoricul cumpărăturii și bifarea produselor corespunzătoare din listă. Acțiunea poate fi păstrată offline și sincronizată ulterior. Reîncercarea aceleiași confirmări nu dublează înregistrările. Dacă alt dispozitiv a cumpărat sau a modificat între timp aceleași poziții, acțiunea este oprită pentru verificare.

## Gătit pentru mai multe zile

În **Mai multe → Porții gătite**, alegi o rețetă și „Gătesc pentru alte zile”. Poți activa aceeași opțiune în dialogul **Am gătit**. Alegi 1–8 porții, verifici cantitățile folosite, introduci data gătirii, termenul ales de tine și locul: frigider sau congelator. Aplicația nu presupune o durată universală de păstrare.

Ingredientele confirmate se scad o singură dată, la gătire. Lotul păstrează numărul total și numărul rămas de porții. „Am mâncat o porție” și „Am aruncat porții” modifică doar lotul gătit și istoricul. Un termen depășit blochează confirmarea consumului din acest ecran. Loturile cu porții rămase sunt afișate în **Astăzi** și incluse în rezumatul termenelor de la ora configurată, implicit 19:00.

Prin „Repartizează în plan” rezervi porții pentru o dată din intervalul introdus. Aplicația verifică porțiile libere și împiedică rezervarea lor de două ori. Masa din plan se confirmă cu „Am mâncat”; ingredientele nu se scad din nou. Porțiile rezervate se consumă prin masa lor din plan sau după modificarea rezervării.

Istoricul din **Buget și risipă** permite anularea consumului porțiilor. Pentru anularea gătirii trebuie întâi anulate consumurile ulterioare și eliminate rezervările active; apoi se pot restabili ingredientele confirmate inițial.

## Actualizare și date

Versiunea Android este 0.5.1, cod 7, compatibilă cu serverul 0.5.0. Corecția 0.5.1 păstrează toate câmpurile rețetei la verificarea sincronizării, astfel încât un plan nemodificat să poată fi confirmat. Baza SQLite trece aditiv la schema 5 și adaugă loturile gătite și cumpărăturile confirmate. Backupurile includ noile date, iar copiile mai vechi rămân restaurabile. La actualizare se păstrează cheia, baza de date, modelele și directorul de backup.

Gestionarea RAM din 0.4.1 rămâne activă. Recomandările individuale păstrează motorul selectat pe fiecare dispozitiv. Planificarea folosește opțional AI-ul local de pe server, doar la cererea utilizatorului; o cerere deja pornită este lăsată să termine înaintea eliberării modelului.
