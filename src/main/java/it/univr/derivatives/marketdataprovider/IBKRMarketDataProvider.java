package it.univr.derivatives.marketdataprovider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttrib;
import ibkr.IBWrapperImpl; // Assicurati che l'import del tuo wrapper sia corretto per il tuo progetto

public class IBKRMarketDataProvider {

    /**
     * Metodo modulare per ottenere il prezzo Spot attuale di un asset.
     * Accetta secType ("STK", "IND", "CASH", ecc.) per essere universale.
     */
    public static double getRealTimeSpotPrice(String symbol, String secType, String exchange, String currency) throws Exception {

        CompletableFuture<Double> priceFuture = new CompletableFuture<>();

        // Creiamo un Wrapper "al volo" basato sul tuo IBWrapperImpl pulito.
        IBWrapperImpl dataWrapper = new IBWrapperImpl() {
            @Override
            public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
                // STAMPA DI DEBUG
                System.out.println("Ricevuto tick da IBKR -> Field: " + field + " | Prezzo: " + price);

                // field 4 = Last Price, 68 = Delayed Last, 9 = Close, 75 = Delayed Close
                if (field == 4 || field == 68 || field == 9 || field == 75) {
                    if (!priceFuture.isDone() && price > 0.0) {
                        priceFuture.complete(price); // Trovato! Sblocchiamo il futuro
                    }
                }
            }
        };

        // Setup connessione
        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);

        client.eConnect("127.0.0.1", 7497, 100);

        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(1000);

        // COSTRUZIONE DEL CONTRATTO UNIVERSALE
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType); // <-- Qui usiamo il parametro invece dell'if!
        contract.currency(currency);
        contract.exchange(exchange);

        // Forza IBKR a inviare i dati in differita o dell'ultima chiusura (se live non disponibile)
        client.reqMarketDataType(4);

        // Invia la richiesta dati
        client.reqMktData(1, contract, "", false, false, null);

        double spotPrice = 0.0;
        try {
            spotPrice = priceFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Timeout o errore nel recupero del prezzo per " + symbol);
        } finally {
            client.cancelMktData(1);
            client.eDisconnect();
        }

        return spotPrice;
    }

    /**
     * Scarica la serie storica di un asset e la restituisce come oggetto TimeSeries.
     * Accetta secType per essere universale.
     */
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
                    // Ignoriamo eventuali righe formattate male
                }
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                System.out.println("Download dati storici completato da " + startDateStr + " a " + endDateStr);
                historyFuture.complete(timeSeries);
            }
        };

        // Setup Connessione
        EReaderSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(dataWrapper, signal);
        client.eConnect("127.0.0.1", 7497, 102);

        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) {}
            }
        }).start();

        Thread.sleep(500);

        // COSTRUZIONE DEL CONTRATTO UNIVERSALE
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType); // <-- Usiamo il parametro anche qui
        contract.currency(currency);
        contract.exchange(exchange);

        System.out.println("Richiesta serie storica per " + symbol + " in corso...");

        client.reqHistoricalData(3, contract, "", duration, "1 day", "TRADES", 1, 1, false, null);

        it.univr.derivatives.utils.TimeSeries resultSeries = null;
        try {
            resultSeries = historyFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Timeout durante lo scaricamento dello storico.");
        } finally {
            client.eDisconnect();
        }

        return resultSeries;
    }
}