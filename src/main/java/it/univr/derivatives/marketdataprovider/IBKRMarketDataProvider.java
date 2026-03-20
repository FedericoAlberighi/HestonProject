package it.univr.derivatives.marketdataprovider;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttrib;
import ibkr.IBWrapperImpl;

// Importazioni FINMATH
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveInterpolation;
import net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity;
import net.finmath.marketdata.model.volatilities.OptionSmileData;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention;

public class IBKRMarketDataProvider {

    // =========================================================================
    // 1. OTTIENI PREZZO SPOT (STORICO 1 MIN + FALLBACK CHIUSURA GIORNALIERA)
    // =========================================================================
    public static double getRealTimeSpotPrice(String symbol, String secType, String exchange, String currency) throws Exception {
        System.out.println(">>> RICERCA PREZZO SPOT IN CORSO (Modalità bypass storico 1 min)...");
        CompletableFuture<Double> priceFuture = new CompletableFuture<>();

        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            private double lastClose = 0.0;

            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                lastClose = bar.close(); // Prende la chiusura della candela a 1 minuto
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                if (!priceFuture.isDone() && lastClose > 0.0) priceFuture.complete(lastClose);
            }

            @Override public void error(int id, int errorCode, String errorMsg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String str) {}
            @Override public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {}
            @Override public void securityDefinitionOptionParameterEnd(int reqId) {}
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 999);

        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(1000);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType);
        contract.currency(currency);
        contract.exchange(exchange);

        // FASE 1: Chiediamo la candela a 1 minuto di "oggi"
        client.reqHistoricalData(1, contract, "", "1 D", "1 min", "TRADES", 0, 1, false, null);

        double spotPrice = 0.0;
        try {
            // Diamo 8 secondi. Se il mercato è aperto, risponde in 1 secondo.
            spotPrice = priceFuture.get(8, TimeUnit.SECONDS);
            System.out.println(">>> PREZZO SPOT RECUPERATO: " + spotPrice + " $");
            client.eDisconnect();
            return spotPrice; // Se ha funzionato, usciamo e andiamo avanti col programma!

        } catch (Exception e) {
            client.eDisconnect();
            System.err.println(">>> Mercato chiuso o dati 1-min indisponibili. Passo al fallback storico...");
        }

        // =======================================================
        // FASE 2: FALLBACK (Chiusura giornaliera precedente)
        // =======================================================
        System.out.println(">>> RECUPERO ULTIMA CHIUSURA UFFICIALE (Ricerca negli ultimi 5 giorni)...");
        CompletableFuture<Double> fallbackFuture = new CompletableFuture<>();

        IBWrapperImpl fallbackWrapper = new IBWrapperImpl() {
            private double dailyClose = 0.0;

            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                if (bar.close() > 0.0) dailyClose = bar.close(); // Si aggiorna fino all'ultima candela giornaliera valida
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                if (!fallbackFuture.isDone() && dailyClose > 0.0) fallbackFuture.complete(dailyClose);
            }

            @Override public void error(int id, int errorCode, String errorMsg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String str) {}
            @Override public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {}
            @Override public void securityDefinitionOptionParameterEnd(int reqId) {}
        };

        EReaderSignal fallbackSignal = new EJavaSignal();
        EClientSocket fallbackClient = new EClientSocket(fallbackWrapper, fallbackSignal);

        // Usiamo un client ID diverso (995) per evitare conflitti nella TWS con la connessione appena chiusa
        fallbackClient.eConnect("127.0.0.1", 7497, 995);
        final EReader fallbackReader = new EReader(fallbackClient, fallbackSignal);
        fallbackReader.start();
        new Thread(() -> {
            while (fallbackClient.isConnected()) {
                fallbackSignal.waitForSignal();
                try { fallbackReader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(1000);

        // Chiediamo gli ultimi 5 giorni con candele da 1 Giorno
        fallbackClient.reqHistoricalData(2, contract, "", "5 D", "1 day", "TRADES", 0, 1, false, null);

        try {
            spotPrice = fallbackFuture.get(10, TimeUnit.SECONDS);
            System.out.println(">>> PREZZO DI CHIUSURA STORICO RECUPERATO: " + spotPrice + " $");
        } catch (Exception e) {
            System.err.println(">>> ERRORE CRITICO: Impossibile recuperare anche il dato storico di fallback.");
            throw new RuntimeException("Dati di mercato inaccessibili.");
        } finally {
            fallbackClient.eDisconnect();
        }

        return spotPrice;
    }

    // =========================================================================
    // 2. OTTIENI SERIE STORICA
    // =========================================================================
    public static it.univr.derivatives.utils.TimeSeries getHistoricalData(String symbol, String secType, String exchange, String currency, String duration) throws Exception {
        CompletableFuture<it.univr.derivatives.utils.TimeSeries> historyFuture = new CompletableFuture<>();
        it.univr.derivatives.utils.TimeSeries timeSeries = new it.univr.derivatives.utils.TimeSeries();
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                try {
                    java.time.LocalDate date = java.time.LocalDate.parse(bar.time(), formatter);
                    timeSeries.add(date, bar.close());
                } catch (Exception e) {}
            }
            @Override public void error(int id, int errorCode, String errorMsg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String str) {}
            @Override public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) { historyFuture.complete(timeSeries); }
            @Override public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {}
            @Override public void securityDefinitionOptionParameterEnd(int reqId) {}
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 996);
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(500);

        Contract contract = new Contract();
        contract.symbol(symbol); contract.secType(secType); contract.currency(currency); contract.exchange(exchange);
        client.reqHistoricalData(3, contract, "", duration, "1 day", "TRADES", 1, 1, false, null);

        try { return historyFuture.get(20, TimeUnit.SECONDS); }
        catch (Exception e) { return null; }
        finally { client.eDisconnect(); }
    }

    // =========================================================================
    // 3. RECUPERO MINI-CHAIN CON FLESSIBILITÀ (Mesi per breve termine, Anni per LEAPS)
    // =========================================================================
    public static List<Contract> getTargetDatesChain(String symbol, String exchange, String currency, Set<String> targetFilters) throws Exception {
        long startTime = System.currentTimeMillis();
        List<Contract> optionList = new ArrayList<>();
        CountDownLatch requestsLatch = new CountDownLatch(targetFilters.size());

        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            @Override
            public void contractDetails(int reqId, com.ib.client.ContractDetails contractDetails) {
                optionList.add(contractDetails.contract());
            }
            @Override
            public void contractDetailsEnd(int reqId) {
                requestsLatch.countDown();
            }
            @Override public void error(int id, int errorCode, String errorMsg) {
                if (errorCode == 200) requestsLatch.countDown();
            }
            @Override public void error(Exception e) {}
            @Override public void error(String str) {}
            @Override public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {}
            @Override public void securityDefinitionOptionParameterEnd(int reqId) {}
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 998);
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(1000);

        System.out.println(">>> SCARICO MINI-CHAIN (Filtri usati: " + targetFilters + ")...");
        int reqId = 10;

        for(String filter : targetFilters) {
            Contract contract = new Contract();
            contract.symbol(symbol);
            contract.secType("OPT");
            contract.currency(currency);
            contract.exchange(exchange);
            contract.multiplier("100");

            // Passare "YYYYMM" cerca il mese. Passare "YYYY" cerca l'intero anno (perfetto per le LEAPS).
            contract.lastTradeDateOrContractMonth(filter);

            client.reqContractDetails(reqId++, contract);
            Thread.sleep(100);
        }

        try {
            requestsLatch.await(120, TimeUnit.SECONDS);
            System.out.println(">>> MINI-CHAIN RICEVUTA: " + optionList.size() + " contratti complessivi.");
            System.out.println(">>> [TIMING] Mini-Chain ottenuta in " + (System.currentTimeMillis() - startTime) + " ms.");
            return optionList;
        } catch (Exception e) {
            System.err.println(">>> Timeout mini-chain.");
            return new ArrayList<>();
        } finally {
            client.eDisconnect();
        }
    }


    // =========================================================================
    // 6. COSTRUTTORE SUPERFICIE FINMATH (METODO ROBUSTO - GARANTITO)
    // =========================================================================
    public static OptionSurfaceData getLiveVolatilitySurface(String symbol, String secType, String exchange, String currency) throws Exception {
        long totalStartTime = System.currentTimeMillis();

        double spotPrice = getRealTimeSpotPrice(symbol, secType, exchange, currency);
        if (spotPrice <= 0) throw new RuntimeException("Prezzo Spot non valido, impossibile procedere.");

        double[] strikeScaling = {1.1, 1.05, 1.025, 1.0, 0.975, 0.95, 0.90};
        double[] maturities = {0.083333333, 0.166666667, 0.25, 0.5, 1.0, 1.5, 2.0};

        double[][] iVols = new double[maturities.length][strikeScaling.length];
        for (double[] row : iVols) java.util.Arrays.fill(row, 0.20);

        Contract[][] matchedContracts = new Contract[maturities.length][strikeScaling.length];
        int contrattiTrovati = 0;

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmtYYYYMM = java.time.format.DateTimeFormatter.ofPattern("yyyyMM");
        java.time.format.DateTimeFormatter fmtYYYY = java.time.format.DateTimeFormatter.ofPattern("yyyy");
        java.time.format.DateTimeFormatter fmt8 = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

        long strikeStartTime = System.currentTimeMillis();

        // 1. IL TRUCCO DELLE LEAPS:
        // Per scadenze <= 1 anno, chiediamo il mese esatto (YYYYMM).
        // Per scadenze > 1 anno (LEAPS), chiediamo l'intero anno (YYYY) così troviamo le scadenze di Gennaio/Dicembre.
        Set<String> targetFilters = new HashSet<>();
        for (double maturity : maturities) {
            java.time.LocalDate targetDate = today.plusDays((long) (maturity * 365.0));
            if (maturity <= 1.0) {
                targetFilters.add(targetDate.format(fmtYYYYMM));
            } else {
                targetFilters.add(targetDate.format(fmtYYYY));
            }
        }

        // 2. SCARICHIAMO LA MINI-CHAIN USANDO IL METODO SICURO
        List<Contract> fullChain = getTargetDatesChain(symbol, exchange, currency, targetFilters);
        if (fullChain.isEmpty()) {
            throw new RuntimeException("Impossibile scaricare le opzioni. Verifica la connessione a IBKR.");
        }

        System.out.println(">>> RICERCA STRIKE/DATE REALI NELLA MINI-CHAIN...");

        Set<String> usedDates = new HashSet<>();

        List<Double> validMaturitiesList = new ArrayList<>();
        List<double[]> validSmileVolsList = new ArrayList<>();
        List<Contract[]> validSmileContractsList = new ArrayList<>();

        int rowIdx = 0;

        for (int i = 0; i < maturities.length; i++) {
            double targetDays = maturities[i] * 365.0;

            String bestDateIBKRFormat = null;
            double minDateDiff = Double.MAX_VALUE;

            // Trova la miglior data reale all'interno della chain
            for (Contract c : fullChain) {
                try {
                    String ds = c.lastTradeDateOrContractMonth();
                    if (ds == null || ds.trim().isEmpty() || usedDates.contains(ds)) continue;

                    ds = ds.trim();
                    String normalizedDateStr = (ds.length() == 6) ? ds + "15" : (ds.length() >= 8 ? ds.substring(0, 8) : ds);
                    java.time.LocalDate d = java.time.LocalDate.parse(normalizedDateStr, fmt8);
                    double diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, d) - targetDays);

                    if (diff < minDateDiff) {
                        minDateDiff = diff;
                        bestDateIBKRFormat = c.lastTradeDateOrContractMonth();
                    }
                } catch (Exception e) {}
            }

            // MODIFICA 1: Tolleranza differenziata (300 giorni per le LEAPS, 60 per il breve termine)
            double maxTolerance = (targetDays > 365) ? 300 : 60;

            if (bestDateIBKRFormat != null && minDateDiff <= maxTolerance) {
                usedDates.add(bestDateIBKRFormat);
                validMaturitiesList.add(maturities[i]);
                validSmileVolsList.add(iVols[rowIdx]); // Usiamo la riga per cui ricaveremo le IV
                validSmileContractsList.add(new Contract[strikeScaling.length]);

                List<Contract> validOptionsForDate = new ArrayList<>();
                for (Contract c : fullChain) {
                    if (bestDateIBKRFormat.equals(c.lastTradeDateOrContractMonth()) && c.getRight() != null) {
                        validOptionsForDate.add(c);
                    }
                }

                Set<Double> usedStrikes = new HashSet<>();

                for (int j = 0; j < strikeScaling.length; j++) {
                    double targetStrike = spotPrice * strikeScaling[j];
                    String desiredRight = (strikeScaling[j] >= 1.0) ? "C" : "P";

                    Contract bestStrikeMatch = null;
                    double minStrikeDiff = Double.MAX_VALUE;

                    for (Contract c : validOptionsForDate) {
                        if (!desiredRight.equalsIgnoreCase(c.getRight())) continue;
                        if (usedStrikes.contains(c.strike())) continue;

                        double strikeDiff = Math.abs(c.strike() - targetStrike);
                        if (strikeDiff < minStrikeDiff) {
                            minStrikeDiff = strikeDiff;
                            bestStrikeMatch = c;
                        }
                    }

                    if (bestStrikeMatch != null) {
                        validSmileContractsList.get(rowIdx)[j] = bestStrikeMatch;
                        usedStrikes.add(bestStrikeMatch.strike());
                        contrattiTrovati++;
                    }
                }
                rowIdx++;
            } else {
                System.out.println(">>> [WARNING] Scadenza target " + (int)targetDays + " giorni SALTATA (Nessuna opzione reale trovata entro i " + maxTolerance + " giorni di tolleranza).");
            }
        }
        System.out.println(">>> [TIMING] " + contrattiTrovati + " Bersagli Reali abbinati in " + (System.currentTimeMillis() - strikeStartTime) + " ms.");

        // Se nessuna scadenza è valida, fermiamo tutto.
        if (validMaturitiesList.isEmpty()) {
            throw new RuntimeException("Nessuna scadenza valida trovata per i requisiti. Il titolo potrebbe non avere opzioni.");
        }

        // Ricostruiamo gli array in base alle sole righe valide
        double[] finalMaturities = validMaturitiesList.stream().mapToDouble(Double::doubleValue).toArray();
        double[][] finalIVols = validSmileVolsList.toArray(new double[0][0]);
        Contract[][] finalMatchedContracts = validSmileContractsList.toArray(new Contract[0][0]);

        long volStartTime = System.currentTimeMillis();
        // --- 4. RECUPERO VOLATILITA' ---
        if (contrattiTrovati > 0) {
            CountDownLatch latch = new CountDownLatch(contrattiTrovati);

            IBWrapperImpl batchWrapper = new IBWrapperImpl() {
                @Override
                public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol,
                                                  double delta, double optPrice, double pvDividend,
                                                  double gamma, double vega, double theta, double undPrice) {
                    if ((field == 13 || field == 83 || field == 10 || field == 11 || field == 12 || field == 80 || field == 81 || field == 82)
                            && impliedVol > 0.0 && impliedVol < 2.5) {
                        int r = tickerId / 100, c = tickerId % 100;
                        if (finalIVols[r][c] == 0.20 || finalIVols[r][c] == 0.0) {
                            finalIVols[r][c] = impliedVol;
                            latch.countDown();
                        }
                    }
                }
                @Override public void error(int id, int errorCode, String errorMsg) {
                    if (id >= 0 && errorCode == 200) latch.countDown();
                }
                @Override public void error(Exception e) {}
                @Override public void error(String str) {}
                @Override public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {}
                @Override public void securityDefinitionOptionParameterEnd(int reqId) {}
            };

            EReaderSignal signal = new EJavaSignal();
            EClientSocket client = new EClientSocket(batchWrapper, signal);
            client.eConnect("127.0.0.1", 7497, 997);

            final EReader reader = new EReader(client, signal);
            reader.start();
            new Thread(() -> {
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try { reader.processMsgs(); } catch (Exception e) {}
                }
            }).start();

            Thread.sleep(1000);

            // Fondamentale: usiamo Dati Ritardati normali (3) per ricevere le OptionComputation e non finire con tutto al 20%
            client.reqMarketDataType(3);

            System.out.println(">>> RECUPERO VOLATILITA' PER " + contrattiTrovati + " OPZIONI OTM REALI (Attendere prego)...");
            for (int i = 0; i < finalMaturities.length; i++) {
                for (int j = 0; j < strikeScaling.length; j++) {
                    if (finalMatchedContracts[i][j] != null) {
                        client.reqMktData(i * 100 + j, finalMatchedContracts[i][j], "106", false, false, null);
                        Thread.sleep(30);
                    }
                }
            }

            latch.await(30, TimeUnit.SECONDS);

            for (int i = 0; i < finalMaturities.length; i++) {
                for (int j = 0; j < strikeScaling.length; j++) {
                    if (finalMatchedContracts[i][j] != null) client.cancelMktData(i * 100 + j);
                }
            }
            client.eDisconnect();
            System.out.println(">>> [TIMING] Download Volatilità (IBKR) completato in " + (System.currentTimeMillis() - volStartTime) + " ms.");
        }

        long patchStartTime = System.currentTimeMillis();
        // --- 5. POST-PROCESSING: AUTO-PATCHING (Riparazione code illiquide) ---
        for (int i = 0; i < finalMaturities.length; i++) {
            for (int j = 0; j < strikeScaling.length; j++) {
                if (finalIVols[i][j] == 0.20 || finalIVols[i][j] == 0.0) {

                    double leftVal = -1; int leftIdx = -1;
                    for (int k = j - 1; k >= 0; k--) {
                        if (finalIVols[i][k] != 0.20 && finalIVols[i][k] != 0.0) { leftVal = finalIVols[i][k]; leftIdx = k; break; }
                    }

                    double rightVal = -1; int rightIdx = -1;
                    for (int k = j + 1; k < strikeScaling.length; k++) {
                        if (finalIVols[i][k] != 0.20 && finalIVols[i][k] != 0.0) { rightVal = finalIVols[i][k]; rightIdx = k; break; }
                    }

                    if (leftVal != -1 && rightVal != -1) {
                        double x = strikeScaling[j];
                        double x1 = strikeScaling[leftIdx];
                        double x2 = strikeScaling[rightIdx];
                        finalIVols[i][j] = leftVal + (x - x1) * (rightVal - leftVal) / (x2 - x1);
                    } else if (leftVal != -1) {
                        finalIVols[i][j] = leftVal;
                    } else if (rightVal != -1) {
                        finalIVols[i][j] = rightVal;
                    } else {
                        for(int r = 0; r < finalMaturities.length; r++) {
                            if (finalIVols[r][j] != 0.20 && finalIVols[r][j] != 0.0) {
                                finalIVols[i][j] = finalIVols[r][j]; break;
                            }
                        }
                    }
                }
            }
        }
        System.out.println(">>> [TIMING] Auto-Patching (Riparazione dati) completato in " + (System.currentTimeMillis() - patchStartTime) + " ms.");

        long finmathStartTime = System.currentTimeMillis();
        // --- 6. COSTRUZIONE OGGETTI FINMATH ---
        java.time.LocalDate referenceDate = java.time.LocalDate.now();
        double flatRate = 0.03;

        double[] curveMaturities = new double[finalMaturities.length + 1];
        curveMaturities[0] = 0.0;
        System.arraycopy(finalMaturities, 0, curveMaturities, 1, finalMaturities.length);

        double[] curveZeroRates = new double[curveMaturities.length];
        java.util.Arrays.fill(curveZeroRates, flatRate);

        double[] discountFactors = new double[curveMaturities.length];
        for (int k = 0; k < curveMaturities.length; k++) {
            discountFactors[k] = spotPrice * Math.exp(flatRate * curveMaturities[k]);
        }

        ExtrapolationMethod exMethod = ExtrapolationMethod.CONSTANT;
        InterpolationMethod intMethod = InterpolationMethod.LINEAR;
        InterpolationEntity intEntity = InterpolationEntity.LOG_OF_VALUE;

        DiscountCurve myDiscountCurve = DiscountCurveInterpolation.createDiscountCurveFromZeroRates(
                "liveDiscountCurve", referenceDate, curveMaturities, curveZeroRates, intMethod, exMethod, intEntity);

        DiscountCurve equityForwardCurve = DiscountCurveInterpolation.createDiscountCurveFromDiscountFactors(
                "liveForwardCurve", curveMaturities, discountFactors, intMethod, exMethod, intEntity);

        QuotingConvention convention = QuotingConvention.VOLATILITYLOGNORMAL;
        OptionSmileData[] smileContainers = new OptionSmileData[finalMaturities.length];

        System.out.println("\n==================================================================");
        System.out.println("   📊 RIASSUNTO SUPERFICIE IVS REALE (" + symbol + ") 📊");
        System.out.println("==================================================================");

        for (int i = 0; i < finalMaturities.length; i++) {
            double[] realStrikesForThisMaturity = new double[strikeScaling.length];
            int targetDays = (int) Math.round(finalMaturities[i] * 365.0);

            int actualDays = targetDays;
            if (finalMatchedContracts[i][3] != null) {
                String expiryStr = finalMatchedContracts[i][3].lastTradeDateOrContractMonth();
                try {
                    String s = (expiryStr.length() == 6) ? expiryStr + "15" : expiryStr.substring(0, 8);
                    java.time.LocalDate expiryDate = java.time.LocalDate.parse(s, fmt8);
                    actualDays = (int) java.time.temporal.ChronoUnit.DAYS.between(referenceDate, expiryDate);
                } catch (Exception e) {}
            }

            System.out.printf("\n 📁 TARGET: %d Giorni -> SCADENZA REALE: %d Giorni\n", targetDays, actualDays);
            System.out.println(" ------------------------------------------------------------------");
            System.out.println(" |  STRIKE REALE ($) (TIPO) |  VOLATILITA' (%) |  TARGET % ATM  |");
            System.out.println(" ------------------------------------------------------------------");

            for (int j = 0; j < strikeScaling.length; j++) {
                double realStrike = (finalMatchedContracts[i][j] != null) ? finalMatchedContracts[i][j].strike() : spotPrice * strikeScaling[j];
                String optionType = (finalMatchedContracts[i][j] != null) ? finalMatchedContracts[i][j].getRight() : (strikeScaling[j] >= 1.0 ? "C" : "P");

                realStrikesForThisMaturity[j] = realStrike;

                System.out.printf(" |     %7.2f         (%s)  |     %6.2f %%      |     %5.1f %%    |\n",
                        realStrike, optionType, (finalIVols[i][j] * 100), strikeScaling[j] * 100);
            }
            System.out.println(" ------------------------------------------------------------------");

            // MODIFICA 2: TRUCCO MODULARE. Passiamo la scadenza TEORICA esatta a Finmath per la calibrazione Heston.
            smileContainers[i] = new OptionSmileData(symbol, referenceDate, realStrikesForThisMaturity, finalMaturities[i], finalIVols[i], convention);
        }

        System.out.println(">>> [TIMING] Costruzione oggetti Finmath completata in " + (System.currentTimeMillis() - finmathStartTime) + " ms.");
        System.out.println(">>> [TIMING GLOBALE] INTERA SUPERFICIE IVS GENERATA IN: " + (System.currentTimeMillis() - totalStartTime) + " ms.");

        return new OptionSurfaceData(smileContainers, myDiscountCurve, equityForwardCurve);
    }
}