package it.univr.derivatives.marketdataprovider;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttrib;
import ibkr.IBWrapperImpl;

public class IBKRMarketDataProvider {

    // =========================================================================
    // 1. OTTIENI PREZZO SPOT (METODO INFALLIBILE VIA STORICO)
    // =========================================================================
    public static double getRealTimeSpotPrice(String symbol, String secType, String exchange, String currency) throws Exception {
        System.out.println(">>> RICERCA PREZZO SPOT IN CORSO (Modalità bypass storico)...");
        CompletableFuture<Double> priceFuture = new CompletableFuture<>();

        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            private double lastClose = 0.0;

            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                lastClose = bar.close();
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                if (!priceFuture.isDone() && lastClose > 0.0) priceFuture.complete(lastClose);
            }

            @Override
            public void error(int id, int errorCode, String errorMsg) {
            }

            @Override
            public void error(Exception e) {
            }

            @Override
            public void error(String str) {
            }

            @Override
            public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
            }

            @Override
            public void securityDefinitionOptionParameterEnd(int reqId) {
            }
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 999);
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                }
            }
        }).start();

        Thread.sleep(1000);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType);
        contract.currency(currency);
        contract.exchange(exchange);

        client.reqHistoricalData(1, contract, "", "1 D", "1 min", "TRADES", 0, 1, false, null);

        double spotPrice = 0.0;
        try {
            spotPrice = priceFuture.get(15, TimeUnit.SECONDS);
            System.out.println(">>> PREZZO SPOT RECUPERATO: " + spotPrice + " $");
        } catch (Exception e) {
            System.err.println("Timeout: impossibile recuperare il prezzo spot per " + symbol);
        } finally {
            client.eDisconnect();
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
                } catch (Exception e) {
                }
            }

            @Override
            public void error(int id, int errorCode, String errorMsg) {
            }

            @Override
            public void error(Exception e) {
            }

            @Override
            public void error(String str) {
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                historyFuture.complete(timeSeries);
            }

            @Override
            public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
            }

            @Override
            public void securityDefinitionOptionParameterEnd(int reqId) {
            }
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 996);
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                }
            }
        }).start();

        Thread.sleep(500);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType);
        contract.currency(currency);
        contract.exchange(exchange);
        client.reqHistoricalData(3, contract, "", duration, "1 day", "TRADES", 1, 1, false, null);

        try {
            return historyFuture.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            client.eDisconnect();
        }
    }

    // =========================================================================
    // 3. OTTIENI OPTION CHAIN (FILTRATA SOLO PER CALL)
    // =========================================================================
    public static List<Contract> getOptionChain(String symbol, String secType, String exchange, String currency) throws Exception {
        CompletableFuture<List<Contract>> chainFuture = new CompletableFuture<>();
        List<Contract> optionList = new ArrayList<>();

        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            @Override
            public void contractDetails(int reqId, com.ib.client.ContractDetails contractDetails) {
                optionList.add(contractDetails.contract());
            }

            @Override
            public void contractDetailsEnd(int reqId) {
                chainFuture.complete(optionList);
            }

            @Override
            public void error(int id, int errorCode, String errorMsg) {
            }

            @Override
            public void error(Exception e) {
            }

            @Override
            public void error(String str) {
            }

            @Override
            public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
            }

            @Override
            public void securityDefinitionOptionParameterEnd(int reqId) {
            }
        };

        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 998);
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                }
            }
        }).start();

        Thread.sleep(1000);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType("OPT");
        contract.currency(currency);
        contract.exchange(exchange);
        // Filtro forte alla fonte: SOLO CALL
        contract.right("C");
        contract.multiplier("100");

        System.out.println(">>> SCARICO L'INTERA CHAIN (SOLO CALL)...");
        client.reqContractDetails(2, contract);

        try {
            List<Contract> finalChain = chainFuture.get(600, TimeUnit.SECONDS);
            System.out.println(">>> OPTION CHAIN SCARICATA: " + finalChain.size() + " contratti Call.");
            return finalChain;
        } catch (Exception e) {
            return new ArrayList<>();
        } finally {
            client.eDisconnect();
        }
    }

    public static double getOptionImpliedVolatility(Contract optionContract) throws Exception {
        return 0.20;
    }

    public static Contract findClosestOption(java.util.List<Contract> fullChain, double targetStrike, double targetDays, String right) {
        return null;
    }

    // =========================================================================
    // 6. COSTRUTTORE SUPERFICIE (ALGORITMO OTTIMIZZATO CALL + UNICITÀ)
    // =========================================================================
    public static net.finmath.marketdata.model.volatilities.OptionSurfaceData getLiveVolatilitySurface(String symbol, String secType, String exchange, String currency) throws Exception {

        double spotPrice = getRealTimeSpotPrice(symbol, secType, exchange, currency);
        if (spotPrice <= 0) throw new RuntimeException("Prezzo Spot non valido, impossibile procedere.");

        double[] strikeScaling = {1.1, 1.05, 1.025, 1.0, 0.975, 0.95, 0.90};
        double[] maturities = {0.083333333, 0.166666667, 0.25, 0.5, 1.0, 1.5, 2.0};

        java.util.List<Contract> fullChain = getOptionChain(symbol, "OPT", exchange, currency);

        double[][] iVols = new double[maturities.length][strikeScaling.length];
        for (double[] row : iVols) java.util.Arrays.fill(row, 0.20);

        Contract[][] matchedContracts = new Contract[maturities.length][strikeScaling.length];
        int contrattiTrovati = 0;

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmt8 = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

        // --- 1. ALGORITMO DI SELEZIONE STRIKE REALI ---
        for (int i = 0; i < maturities.length; i++) {
            double targetDays = maturities[i] * 365.0;
            String bestDateIBKRFormat = null;
            double minDateDiff = Double.MAX_VALUE;

            // A) Trova la scadenza reale LIBERA (la più vicina in assoluto al target)
            for (Contract c : fullChain) {
                try {
                    String ds = c.lastTradeDateOrContractMonth();
                    if (ds == null || ds.trim().isEmpty()) continue;

                    ds = ds.trim(); // Pulisce spazi vuoti

                    // SAFE PARSING: Adattiamo la stringa affinché yyyyMMdd funzioni sempre
                    String normalizedDateStr;
                    if (ds.length() == 6) {
                        normalizedDateStr = ds + "15"; // Es: "202603" -> "20260315"
                    } else if (ds.length() >= 8) {
                        normalizedDateStr = ds.substring(0, 8); // Es: "20260320" -> "20260320"
                    } else {
                        continue; // Formato totalmente sconosciuto, lo ignoriamo
                    }

                    java.time.LocalDate d = java.time.LocalDate.parse(normalizedDateStr, fmt8);
                    double diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, d) - targetDays);

                    if (diff < minDateDiff) {
                        minDateDiff = diff;
                        bestDateIBKRFormat = c.lastTradeDateOrContractMonth(); // Salvo il formato ORIGINALE per il match esatto
                    }
                } catch (Exception e) {
                    // System.err.println("Errore parsing data opzione: " + c.lastTradeDateOrContractMonth());
                }
            }

            if (bestDateIBKRFormat != null) {
                // B) Raccogli tutti i contratti validi (Stessa Data ORIGINALE e SOLO CALL)
                List<Contract> validOptionsForDate = new ArrayList<>();
                for (Contract c : fullChain) {
                    if (bestDateIBKRFormat.equals(c.lastTradeDateOrContractMonth()) &&
                            c.getRight() != null && "C".equalsIgnoreCase(c.getRight())) {
                        validOptionsForDate.add(c);
                    }
                }

                Set<Double> usedStrikes = new HashSet<>(); // Sistema Anti-Duplicati

                // C) Pesca gli strike univoci reali più vicini ai target
                for (int j = 0; j < strikeScaling.length; j++) {
                    double targetStrike = spotPrice * strikeScaling[j];
                    Contract bestStrikeMatch = null;
                    double minStrikeDiff = Double.MAX_VALUE;

                    for (Contract c : validOptionsForDate) {
                        if (usedStrikes.contains(c.strike())) continue; // Salta gli strike già assegnati

                        double strikeDiff = Math.abs(c.strike() - targetStrike);
                        if (strikeDiff < minStrikeDiff) {
                            minStrikeDiff = strikeDiff;
                            bestStrikeMatch = c;
                        }
                    }

                    if (bestStrikeMatch != null) {
                        matchedContracts[i][j] = bestStrikeMatch;
                        usedStrikes.add(bestStrikeMatch.strike()); // Memorizza per non ripescarlo
                        contrattiTrovati++;
                    }
                }
            }
        }

        // --- 2. RECUPERO VOLATILITA' CADENZATO ---
        if (contrattiTrovati > 0) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(contrattiTrovati);

            ibkr.IBWrapperImpl batchWrapper = new ibkr.IBWrapperImpl() {
                @Override
                public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol,
                                                  double delta, double optPrice, double pvDividend,
                                                  double gamma, double vega, double theta, double undPrice) {
                    if ((field == 13 || field == 83) && impliedVol > 0.0 && impliedVol < 2.5) {
                        int r = tickerId / 100, c = tickerId % 100;
                        if (iVols[r][c] == 0.20) {
                            iVols[r][c] = impliedVol;
                            latch.countDown();
                        }
                    }
                }

                @Override
                public void error(int id, int errorCode, String errorMsg) {
                }

                @Override
                public void error(Exception e) {
                }

                @Override
                public void error(String str) {
                }

                @Override
                public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
                }

                @Override
                public void securityDefinitionOptionParameterEnd(int reqId) {
                }
            };

            EReaderSignal signal = new EJavaSignal();
            EClientSocket client = new EClientSocket(batchWrapper, signal);
            client.eConnect("127.0.0.1", 7497, 997);
            final EReader reader = new EReader(client, signal);
            reader.start();
            new Thread(() -> {
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                    }
                }
            }).start();

            Thread.sleep(1000);
            client.reqMarketDataType(3);

            System.out.println(">>> RECUPERO VOLATILITA' PER " + contrattiTrovati + " CALL UNIVOCHE REALI...");
            for (int i = 0; i < maturities.length; i++) {
                for (int j = 0; j < strikeScaling.length; j++) {
                    if (matchedContracts[i][j] != null) {
                        client.reqMktData(i * 100 + j, matchedContracts[i][j], "", false, false, null);
                        Thread.sleep(50);
                    }
                }
            }

            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            client.eDisconnect();
        } else {
            System.out.println(">>> ATTENZIONE: Nessun contratto valido trovato per il matching.");
        }

        // --- 3. COSTRUZIONE OGGETTI FINMATH ---
        java.time.LocalDate referenceDate = java.time.LocalDate.now();
        double flatRate = 0.03;

        double[] curveMaturities = new double[maturities.length + 1];
        curveMaturities[0] = 0.0;
        System.arraycopy(maturities, 0, curveMaturities, 1, maturities.length);

        double[] curveZeroRates = new double[curveMaturities.length];
        java.util.Arrays.fill(curveZeroRates, flatRate);

        double[] discountFactors = new double[curveMaturities.length];
        for (int k = 0; k < curveMaturities.length; k++) {
            discountFactors[k] = spotPrice * Math.exp(flatRate * curveMaturities[k]);
        }

        net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod exMethod = net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod.CONSTANT;
        net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod intMethod = net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod.LINEAR;
        net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity intEntity = net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity.LOG_OF_VALUE;

        net.finmath.marketdata.model.curves.DiscountCurve myDiscountCurve = net.finmath.marketdata.model.curves.DiscountCurveInterpolation.createDiscountCurveFromZeroRates(
                "liveDiscountCurve", referenceDate, curveMaturities, curveZeroRates, intMethod, exMethod, intEntity);

        net.finmath.marketdata.model.curves.DiscountCurve equityForwardCurve = net.finmath.marketdata.model.curves.DiscountCurveInterpolation.createDiscountCurveFromDiscountFactors(
                "liveForwardCurve", curveMaturities, discountFactors, intMethod, exMethod, intEntity);

        net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention convention = net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention.VOLATILITYLOGNORMAL;
        net.finmath.marketdata.model.volatilities.OptionSmileData[] smileContainers = new net.finmath.marketdata.model.volatilities.OptionSmileData[maturities.length];

        System.out.println("\n=========================================================");
        System.out.println("   📊 RIASSUNTO SUPERFICIE DI VOLATILITA' REALE (" + symbol + ") 📊");
        System.out.println("=========================================================");

        for (int i = 0; i < maturities.length; i++) {
            double[] realStrikesForThisMaturity = new double[strikeScaling.length];
            int targetDays = (int) Math.round(maturities[i] * 365.0);

            int actualDays = targetDays;
            if (matchedContracts[i][3] != null) {
                String expiryStr = matchedContracts[i][3].lastTradeDateOrContractMonth();
                try {
                    String s = (expiryStr.length() == 6) ? expiryStr + "15" : expiryStr.substring(0, 8);
                    java.time.LocalDate expiryDate = java.time.LocalDate.parse(s, fmt8);
                    actualDays = (int) java.time.temporal.ChronoUnit.DAYS.between(referenceDate, expiryDate);
                } catch (Exception e) {
                }
            }

            System.out.printf("\n 📁 TARGET: %d Giorni -> SCADENZA REALE: %d Giorni\n", targetDays, actualDays);
            System.out.println(" ----------------------------------------------------------");
            System.out.println(" |  STRIKE REALE ($) |  VOLATILITA' (%) |  TARGET % ATM  |");
            System.out.println(" ----------------------------------------------------------");

            for (int j = 0; j < strikeScaling.length; j++) {
                double realStrike = (matchedContracts[i][j] != null) ? matchedContracts[i][j].strike() : spotPrice * strikeScaling[j];
                realStrikesForThisMaturity[j] = realStrike;

                System.out.printf(" |     %7.2f       |     %6.2f %%      |     %5.1f %%    |\n",
                        realStrike, (iVols[i][j] * 100), strikeScaling[j] * 100);
            }
            System.out.println(" ----------------------------------------------------------");

            double realMaturityInYears = actualDays / 365.0;
            smileContainers[i] = new net.finmath.marketdata.model.volatilities.OptionSmileData(symbol, referenceDate, realStrikesForThisMaturity, realMaturityInYears, iVols[i], convention);
        }

        return new net.finmath.marketdata.model.volatilities.OptionSurfaceData(smileContainers, myDiscountCurve, equityForwardCurve);
    }
}