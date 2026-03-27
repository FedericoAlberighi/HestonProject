package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import ibkr.IBWrapperImpl;
import it.univr.derivatives.utils.TimeSeries;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Retrieves the current spot price and historical price series from IBKR/TWS.
 * <p>
 * Spot price retrieval uses a two-phase strategy to handle both open and closed market sessions:
 * <ul>
 *   <li><b>Phase 1 (live):</b> requests the last 1-minute OHLCV bar via
 *       {@code reqHistoricalData(..., "1 min", "TRADES")}. Succeeds during regular trading hours.</li>
 *   <li><b>Phase 2 (fallback):</b> if Phase 1 times out (8 s), requests the last 5 daily
 *       closing bars and returns the most recent non-zero close. Used after market hours.</li>
 * </ul>
 * </p>
 * <p>
 * Each phase uses its own dedicated {@link EClientSocket} with a unique client ID to avoid
 * conflicts. Both connections are closed via try/finally to guarantee teardown on any exit path.
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class SpotPriceFetcher {

    /* ===========================================================================
     * SPOT PRICE
     * ===========================================================================
     */

    /**
     * Fetches the current spot price of an underlying instrument from IBKR.
     * <p>
     * The method attempts two strategies in sequence:
     * <ol>
     *   <li><b>Phase 1 — 1-minute bar:</b> Requests the last 1-minute TRADES bar.
     *       If a valid close price ({@code > 0}) is received within 8 seconds,
     *       it is returned immediately. This phase works during regular trading hours.</li>
     *   <li><b>Phase 2 — daily close fallback:</b> If Phase 1 times out (market closed
     *       or no data), requests 5 days of daily TRADES bars and returns the most
     *       recent non-zero closing price. Timeout for this phase is 10 seconds.</li>
     * </ol>
     * If both phases fail, a {@link RuntimeException} is thrown.
     * </p>
     *
     * @param symbol   The ticker symbol (e.g. "SPX", "SPY").
     * @param secType  The security type (e.g. "IND", "STK").
     * @param exchange The exchange (e.g. "CBOE", "SMART").
     * @param currency The currency (e.g. "USD").
     * @return The spot price as a positive {@code double}.
     * @throws Exception If TWS is unreachable or both phases fail to return a valid price.
     */
    static double fetch(String symbol, String secType,
                        String exchange, String currency) throws Exception {

        System.out.println(">>> RICERCA PREZZO SPOT (bypass storico 1 min)...");
        Contract contract = IBKRConnection.buildContract(symbol, secType, exchange, currency);

        /* ── Phase 1: last 1-minute bar (works during market hours) ── */
        CompletableFuture<Double> priceFuture = new CompletableFuture<>();
        IBWrapperImpl w1 = new IBWrapperImpl() {
            private double lastClose = 0.0;

            /** Accumulates the close of each bar returned by TWS. */
            @Override public void historicalData(int reqId, com.ib.client.Bar bar) {
                lastClose = bar.close();
            }

            /** When TWS signals end-of-data, completes the future with the last close. */
            @Override public void historicalDataEnd(int reqId, String s, String e) {
                if (!priceFuture.isDone() && lastClose > 0.0) priceFuture.complete(lastClose);
            }

            @Override public void error(int id, int code, String msg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String s) {}
            @Override public void securityDefinitionOptionParameter(int r, String ex, int u,
                String tc, String m, Set<String> exp, Set<Double> str) {}
            @Override public void securityDefinitionOptionParameterEnd(int r) {}
        };

        // Open dedicated connection for Phase 1 (clientId 999)
        EClientSocket c1 = IBKRConnection.connectClient(w1, 999);
        try {
            c1.reqHistoricalData(1, contract, "", "1 D", "1 min", "TRADES", 0, 1, false, null);
            try {
                double spot = priceFuture.get(8, TimeUnit.SECONDS);
                System.out.println(">>> SPOT: " + spot + " $");
                return spot;
            } catch (Exception ignored) {
                // Phase 1 timed out — market is likely closed, proceed to Phase 2
                System.err.println(">>> Mercato chiuso. Fallback storico...");
            }
        } finally {
            c1.eDisconnect();
        }

        /* ── Phase 2: last daily close (fallback for after-hours) ── */
        System.out.println(">>> RECUPERO ULTIMA CHIUSURA (5 giorni)...");
        CompletableFuture<Double> fallback = new CompletableFuture<>();
        IBWrapperImpl w2 = new IBWrapperImpl() {
            private double dailyClose = 0.0;

            /** Keeps the most recent positive daily close. */
            @Override public void historicalData(int reqId, com.ib.client.Bar bar) {
                if (bar.close() > 0.0) dailyClose = bar.close();
            }

            /** Completes the future with the last valid close price. */
            @Override public void historicalDataEnd(int reqId, String s, String e) {
                if (!fallback.isDone() && dailyClose > 0.0) fallback.complete(dailyClose);
            }

            @Override public void error(int id, int code, String msg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String s) {}
            @Override public void securityDefinitionOptionParameter(int r, String ex, int u,
                String tc, String m, Set<String> exp, Set<Double> str) {}
            @Override public void securityDefinitionOptionParameterEnd(int r) {}
        };

        // Open dedicated connection for Phase 2 (clientId 995)
        EClientSocket c2 = IBKRConnection.connectClient(w2, 995);
        try {
            c2.reqHistoricalData(2, contract, "", "5 D", "1 day", "TRADES", 0, 1, false, null);
            try {
                double spot = fallback.get(10, TimeUnit.SECONDS);
                System.out.println(">>> CHIUSURA STORICA: " + spot + " $");
                return spot;
            } catch (Exception e) {
                throw new RuntimeException("Impossibile recuperare spot da IBKR.", e);
            }
        } finally {
            c2.eDisconnect();
        }
    }


    /* ===========================================================================
     * HISTORICAL SERIES
     * ===========================================================================
     */

    /**
     * Downloads a daily historical price series from IBKR and returns it as a {@link TimeSeries}.
     * <p>
     * Uses {@code reqHistoricalData} with bar size "1 day" and data type "TRADES".
     * The series is populated asynchronously via the {@code historicalData()} callback
     * and returned when {@code historicalDataEnd()} signals completion.
     * </p>
     *
     * @param symbol   The ticker symbol (e.g. "AAPL", "NVDA").
     * @param secType  The security type (e.g. "STK").
     * @param exchange The exchange (e.g. "SMART").
     * @param currency The currency (e.g. "USD").
     * @param duration The duration string in IBKR format (e.g. "1 Y", "6 M", "60 D").
     * @return A {@link TimeSeries} of daily closing prices, or {@code null} if the
     *         request times out (20-second limit).
     * @throws Exception If the connection to TWS fails.
     */
    static TimeSeries fetchHistorical(String symbol, String secType,
                                      String exchange, String currency,
                                      String duration) throws Exception {

        CompletableFuture<TimeSeries> future = new CompletableFuture<>();
        TimeSeries ts = new TimeSeries();

        // Formatter for TWS bar date strings ("yyyyMMdd")
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        IBWrapperImpl wrapper = new IBWrapperImpl() {

            /** Appends each daily bar to the time series. */
            @Override public void historicalData(int reqId, com.ib.client.Bar bar) {
                try { ts.add(LocalDate.parse(bar.time(), fmt), bar.close()); }
                catch (Exception ignored) {}
            }

            /** Signals that all bars have been received. */
            @Override public void historicalDataEnd(int reqId, String s, String e) {
                future.complete(ts);
            }

            @Override public void error(int id, int code, String msg) {}
            @Override public void error(Exception e) {}
            @Override public void error(String s) {}
            @Override public void securityDefinitionOptionParameter(int r, String ex, int u,
                String tc, String m, Set<String> exp, Set<Double> str) {}
            @Override public void securityDefinitionOptionParameterEnd(int r) {}
        };

        // Open dedicated connection (clientId 996)
        EClientSocket client = IBKRConnection.connectClient(wrapper, 996);
        try {
            Contract contract = IBKRConnection.buildContract(symbol, secType, exchange, currency);
            // useRTH=1: use only regular trading hours data
            client.reqHistoricalData(3, contract, "", duration, "1 day", "TRADES", 1, 1, false, null);
            try { return future.get(20, TimeUnit.SECONDS); }
            catch (Exception e) { return null; }
        } finally {
            client.eDisconnect();
        }
    }
}
