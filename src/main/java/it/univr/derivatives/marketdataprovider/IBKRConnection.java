package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import ibkr.IBWrapperImpl;

import java.time.format.DateTimeFormatter;

/**
 * Low-level connection manager for the Interactive Brokers TWS API.
 * <p>
 * This class is responsible for establishing and configuring the socket connection
 * to Trader Workstation (TWS) running locally on port 7497. It is the single point
 * of access to the IBKR network layer and is shared by all IBKR-dependent fetchers.
 * </p>
 * <p>
 * Key design decisions:
 * <ul>
 *   <li>A 300 ms sleep is used after {@code eConnect()} to allow the handshake to
 *       complete before any request is sent. This replaces the original 1000 ms sleep.</li>
 *   <li>The message-processing loop runs on a dedicated daemon thread so that
 *       {@code signal.waitForSignal()} never blocks the caller's thread.</li>
 *   <li>Connection teardown ({@code eDisconnect()}) is always the caller's responsibility,
 *       enforced via try/finally blocks in each fetcher.</li>
 * </ul>
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class IBKRConnection {

    /* ===========================================================================
     * DATE FORMATTERS — shared across all classes in this package
     * ===========================================================================
     */

    /**
     * Full date formatter: {@code yyyyMMdd} (e.g. "20251231").
     * Used to parse TWS expiration strings and to match option chain dates.
     */
    static final DateTimeFormatter FMT_8 = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Year-month formatter: {@code yyyyMM} (e.g. "202512").
     * Used to build IBKR option chain filters for maturities ≤ 1 year.
     */
    static final DateTimeFormatter FMT_YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * Year-only formatter: {@code yyyy} (e.g. "2026").
     * Used to build IBKR option chain filters for maturities > 1 year.
     */
    static final DateTimeFormatter FMT_YYYY = DateTimeFormatter.ofPattern("yyyy");


    /* ===========================================================================
     * CONNECTION
     * ===========================================================================
     */

    /**
     * Creates, connects, and starts a fully operational {@link EClientSocket}.
     * <p>
     * The method performs the following steps:
     * <ol>
     *   <li>Creates an {@link EJavaSignal} used by the reader thread to signal
     *       that new messages are available in the socket buffer.</li>
     *   <li>Instantiates an {@link EClientSocket} bound to the provided {@code wrapper}
     *       which will receive all TWS callback events.</li>
     *   <li>Opens the TCP connection to TWS at {@code 127.0.0.1:7497}.</li>
     *   <li>Creates and starts an {@link EReader} that reads raw bytes from the socket
     *       and decodes them into API messages.</li>
     *   <li>Starts a daemon thread that continuously calls {@code reader.processMsgs()}
     *       whenever the signal fires, dispatching callbacks to the wrapper.</li>
     *   <li>Sleeps 300 ms to allow the TWS handshake (server version negotiation)
     *       to complete before the first request is sent.</li>
     * </ol>
     * </p>
     *
     * <p><b>Important:</b> The caller is responsible for calling {@code client.eDisconnect()}
     * in a {@code finally} block to guarantee connection teardown on any exit path.</p>
     *
     * @param wrapper  The {@link IBWrapperImpl} instance that handles all TWS callbacks
     *                 (market data ticks, errors, historical bars, etc.).
     * @param clientId A unique integer identifier for this connection. TWS rejects
     *                 duplicate client IDs on the same session.
     * @return A connected and ready-to-use {@link EClientSocket}.
     * @throws InterruptedException If the thread is interrupted during the 300 ms sleep.
     */
    static EClientSocket connectClient(IBWrapperImpl wrapper, int clientId)
            throws InterruptedException {

        // 1. Create the signal used to wake the processing thread
        EReaderSignal signal = new EJavaSignal();

        // 2. Create the client socket bound to our callback wrapper
        EClientSocket client = new EClientSocket(wrapper, signal);

        // 3. Open the TCP connection to TWS
        client.eConnect("127.0.0.1", 7497, clientId);

        // 4. Start the EReader that decodes incoming bytes
        EReader reader = new EReader(client, signal);
        reader.start();

        // 5. Start the daemon thread that dispatches decoded messages to the wrapper
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception ignored) {}
            }
        }).start();

        // 6. Wait for the TWS handshake to complete (version negotiation)
        Thread.sleep(300);
        return client;
    }


    /* ===========================================================================
     * CONTRACT BUILDER
     * ===========================================================================
     */

    /**
     * Builds an IBKR {@link Contract} for a given underlying instrument.
     * <p>
     * The resulting contract is used to request historical bars, real-time ticks,
     * and as the underlying reference when constructing option chain filters.
     * </p>
     *
     * @param symbol   The ticker symbol (e.g. "SPX", "AAPL", "SPY").
     * @param secType  The security type (e.g. "IND" for index, "STK" for stock).
     * @param exchange The exchange or routing destination (e.g. "CBOE", "SMART").
     * @param currency The currency of the instrument (e.g. "USD").
     * @return A configured {@link Contract} ready to be used in TWS API requests.
     */
    static Contract buildContract(String symbol, String secType,
                                  String exchange, String currency) {
        Contract c = new Contract();
        c.symbol(symbol);
        c.secType(secType);
        c.exchange(exchange);
        c.currency(currency);
        return c;
    }
}
