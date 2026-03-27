package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import ibkr.IBWrapperImpl;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Downloads implied volatilities from IBKR for all matched option contracts.
 * <p>
 * Each contract slot {@code (i, j)} is identified by a unique {@code tickerId}
 * computed as {@code i * 100 + j}, where {@code i} is the maturity index and
 * {@code j} is the strike index. This encoding allows the callback to reconstruct
 * the grid position from the ticker ID alone.
 * </p>
 * <p>
 * Thread-safety is ensured by a {@code boolean[][] slotFilled} matrix guarded by
 * a {@code synchronized} block. TWS may fire multiple {@code tickOptionComputation}
 * callbacks for the same contract (different field types); only the first valid tick
 * per slot is accepted and counted down on the latch.
 * </p>
 * <p>
 * The download timeout is proportional to the number of contracts to prevent
 * premature termination when the option grid is large:
 * {@code timeout = max(20s, contractCount × 800 ms)}.
 * </p>
 * <p>
 * Market data type is set to 3 (delayed) via {@code reqMarketDataType(3)} to ensure
 * implied volatility computation is available even outside regular trading hours.
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class VolatilityDownloader {

    /* ===========================================================================
     * CONSTANTS
     * ===========================================================================
     */

    /**
     * Milliseconds allocated per contract for the download timeout.
     * <br>Total timeout = {@code max(20_000, contractCount × TIMEOUT_MS_PER_CONTRACT)}.
     */
    private static final long TIMEOUT_MS_PER_CONTRACT = 800L;


    /* ===========================================================================
     * DOWNLOAD
     * ===========================================================================
     */

    /**
     * Requests implied volatilities from TWS for all non-null contracts in the grid
     * and fills the {@code iVols} matrix in-place.
     * <p>
     * The method performs the following steps:
     * <ol>
     *   <li>Allocates a {@link CountDownLatch} with one count per non-null contract slot.</li>
     *   <li>Connects to TWS (clientId 997) and enables delayed market data (type 3).</li>
     *   <li>Sends one {@code reqMktData} request per non-null contract, using generic
     *       tick {@code "106"} (implied volatility). A 25 ms sleep between requests
     *       reduces the risk of TWS throttling.</li>
     *   <li>In the {@code tickOptionComputation()} callback, accepts the first valid
     *       IV ({@code 0 < IV < 2.5}) for each slot and records it in {@code iVols[i][j]}.
     *       The {@code slotFilled} flag prevents duplicate {@code countDown()} calls.</li>
     *   <li>Error code 200 on a ticker ID signals a missing contract — the slot is
     *       marked filled (with the existing {@link Double#NaN}) to unblock the latch.</li>
     *   <li>After the latch (or timeout), all market data subscriptions are cancelled
     *       and the connection is closed via try/finally.</li>
     * </ol>
     * </p>
     *
     * @param mats           Array of valid maturities in years (first dimension of the grid).
     * @param strikeScaling  Array of 7 strike multipliers (second dimension of the grid).
     * @param iVols          Implied-volatility matrix to fill in-place. Slots that receive
     *                       no valid tick remain {@link Double#NaN}.
     * @param contracts      Matched contract matrix; {@code null} entries are skipped.
     * @param contractCount  Number of non-null entries in {@code contracts}, used to size
     *                       the latch and compute the timeout.
     * @throws Exception If the connection to TWS fails.
     */
    static void download(double[] mats, double[] strikeScaling,
                         double[][] iVols, Contract[][] contracts,
                         int contractCount) throws Exception {

        if (contractCount == 0) return;

        // Thread-safety: one flag per grid slot — prevents double countDown on duplicate ticks
        boolean[][] slotFilled = new boolean[mats.length][strikeScaling.length];
        CountDownLatch latch   = new CountDownLatch(contractCount);

        IBWrapperImpl wrapper = new IBWrapperImpl() {

            /**
             * Called by TWS when a new option computation tick arrives.
             * Accepted field types: 10-13, 80-83 (model/bid/ask/last IV variants).
             * Only the first valid tick per slot is stored; subsequent ticks are ignored.
             */
            @Override
            public void tickOptionComputation(int tickerId, int field, int tickAttrib,
                                              double impliedVol, double delta, double optPrice,
                                              double pvDiv, double gamma, double vega,
                                              double theta, double undPrice) {

                // Accept only IV-bearing field types
                boolean validField = (field == 13 || field == 83 ||
                        field == 10 || field == 11 || field == 12 ||
                        field == 80 || field == 81 || field == 82);
                if (!validField || impliedVol <= 0.0 || impliedVol >= 2.5) return;

                // Decode grid position from tickerId: i = row (maturity), c = column (strike)
                int r = tickerId / 100, c = tickerId % 100;
                if (r >= iVols.length || c >= iVols[r].length) return;

                synchronized (slotFilled) {
                    if (slotFilled[r][c]) return; // Duplicate tick — ignore
                    slotFilled[r][c] = true;
                    iVols[r][c] = impliedVol;
                }
                latch.countDown();
            }

            /**
             * Error 200 = "No security definition" for this contract.
             * Mark the slot as filled (leaving it as NaN) to prevent latch deadlock.
             */
            @Override public void error(int id, int code, String msg) {
                if (code == 200 && id >= 0) {
                    int r = id / 100, c = id % 100;
                    if (r < slotFilled.length && c < slotFilled[r].length) {
                        synchronized (slotFilled) {
                            if (!slotFilled[r][c]) {
                                slotFilled[r][c] = true;
                                latch.countDown();
                            }
                        }
                    }
                }
            }

            @Override public void error(Exception e) {}
            @Override public void error(String s) {}
            @Override public void securityDefinitionOptionParameter(int r, String ex, int u,
                String tc, String m, Set<String> exp, Set<Double> str) {}
            @Override public void securityDefinitionOptionParameterEnd(int r) {}
        };

        long t0 = System.currentTimeMillis();

        // Open dedicated connection for volatility download (clientId 997)
        EClientSocket client = IBKRConnection.connectClient(wrapper, 997);
        try {
            // Use delayed data (type 3) to receive IV computation outside market hours
            client.reqMarketDataType(3);
            System.out.println(">>> DOWNLOAD VOL PER " + contractCount + " OPZIONI...");

            // Send one market data request per non-null contract slot
            for (int i = 0; i < mats.length; i++) {
                for (int j = 0; j < strikeScaling.length; j++) {
                    if (contracts[i][j] != null) {
                        // tickerId encodes grid position: row * 100 + col
                        client.reqMktData(i * 100 + j, contracts[i][j], "106", false, false, null);
                        // 25 ms delay between requests to avoid TWS throttling
                        Thread.sleep(25);
                    }
                }
            }

            // Proportional timeout: at least 20s, then 800ms per additional contract
            long timeoutMs = Math.max(20_000L, contractCount * TIMEOUT_MS_PER_CONTRACT);
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                System.out.printf(
                        ">>> [WARN] Timeout download dopo %d ms — slot mancanti → patching%n",
                        timeoutMs);
                int missing = 0;
                for (double[] row : iVols)
                    for (double v : row)
                        if (Double.isNaN(v)) missing++;
                if (missing == contractCount)
                    System.err.println(">>> [WARN] Zero volatilità ricevute — mercato probabilmente chiuso.");
            }

            // Cancel all subscriptions before disconnecting
            for (int i = 0; i < mats.length; i++)
                for (int j = 0; j < strikeScaling.length; j++)
                    if (contracts[i][j] != null) client.cancelMktData(i * 100 + j);

        } finally {
            client.eDisconnect();
        }
        System.out.printf(">>> Download vol: %d ms%n", System.currentTimeMillis() - t0);
    }
}
