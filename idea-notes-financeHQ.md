# FinanceHQ

### Główny problem
Zarządzenie budżetem finansowym w poziomu Excela jest średnio wydajne na dzisiejsze możliwości.
Ponadto, aby zmaksymalizować stopę zwrotu, nie trzymam środków na rachunku bieżącym.
Tym samym nie mogę zautomatyzować płatności stałych bezpośrednio z poziomu aplikacji banku poprzez zlecenia stałe.
Manualne pilnowanie terminów jest nieefektywne, przez co zmniejsza efektywność całego przedsięwzięcia.

### Najmniejszy zestaw funkcjonalności. 
- Wprowadzenie zobowiązań tj. kwota, dzień spłaty, długość trwania zobowiązania /harmonogram (Czas określony, czas nieokreślony. ) - CRUD
- System kont użytkowników
- Dodawanie wydatków - import csv oraz manualne
- Dodawanie wpływów - manualnie
- Klasyfikacja kategorii wydatków
- Sugestie AI dotyczące miejsc, w których można zoptymalizować koszt.
  W przypadku nadwyżek finansowych — propozycja inwestycyjna na podstawie analizy sytuacji
  użytkownika (kilka pytań profilujących) oraz analizy aktualnej sytuacji makroekonomicznej
  w momencie ewaluacji.

    Podstawą rekomendacji jest wynik benchmarku za okres 13 miesięcy kalendarzowych
    z przesunięciem o 2 miesiące wstecz względem miesiąca ewaluacji.
    Jako najlepiej performujący benchmark wskazywany jest ten o najwyższej stopie zwrotu
    ceny w tym okresie.
    
    Obserwowane benchmarki:
    - ETF MSCI World       (IWDA.L, LSE)
      - ETF MSCI EM          (EIMI.L, LSE)
      - ETF NASDAQ 100       (CNDX.L, LSE)
      - Polskie obligacje 10-letnie EDO (aktualna oferta MF — oprocentowanie roku 1)
      - ETF US Bonds 7–10Y   (CBU0.L, LSE)
    
    Logika wyznaczania okresu badanego:
    - Data ewaluacji: miesiąc M
      - Koniec okresu:  ostatni dzień miesiąca M-2
      - Start okresu:   pierwszy dzień miesiąca M-14
        Przykład: ewaluacja lipiec 2026 → okres 01.05.2025 – 31.05.2026

- Dashboard

### Co nie wchodzi w zakres MVP. 
Wymyślanie nowego algorytmu decyzyjnego odnośnie sugestii inwestycyjnych
Import wiele formatów 
Łączenie wielu budżetów różnych użytkowników. (Członkowie rodziny. )
integracje z innymi platformami, bankami, etc. 
Aplikacja mobilna. 

### Kryteria sukcesu:
powiadomienia o zbliżającym się terminie płatności na dzień przed datą płatności. Jeden dzień roboczy. 
Użytkownicy wprowadzają wydatki, wpływy oraz zobowiązania oraz mogą podglądać ich status w aplikacji webowej. 
