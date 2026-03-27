package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import ibkr.IBWrapperImpl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Downloads the option chain from IBKR and matches contracts to the target maturity/strike grid.
 * <p>
 * The class operates in two phases:
 * <ol>
 *   <li><b>Chain download ({@link #getChain}):</b> sends one {@code reqContractDetails} request
 *       per maturity filter (in {@code yyyyMM} or {@code yyyy} format) and collects all
 *       returned option contracts. A {@link CountDownLatch} ensures that all filter responses
 *       are received before returning.</li>
 *   <li><b>Grid matching ({@link #matchContracts}):</b> for each of the 7 target maturities,
 *       finds the closest available expiration date within a tolerance window, then for each
 *       of the 7 target strikes selects the nearest listed strike of the correct option type
 *       (Call for strikes ≥ ATM, Put for strikes &lt; ATM). Unmatched slots are left as
 *       {@code NaN} for downstream patching.</li>
 * </ol>
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class OptionChainFetcher {

    /* ===========================================================================
     * RESULT CONTAINER
     * ===========================================================================
     */

    /**
     * Immutable container for the output of {@link #matchContracts}.
     * <p>
     * Holds the matched maturities, the implied-volatility matrix (initialised to
     * {@link Double#NaN} — filled later by {@link VolatilityDownloader}), the matched
     * contracts, and the total count of non-null contract slots.
     * </p>
     */
    static class MatchResult {

        /**
         * Array of valid maturities (in years) for which at least one contract was found.
         * Length ≤ 7 (one entry per target maturity that survived the tolerance filter).
         */
        final double[] mats;

        /**
         * Implied-volatility matrix {@code [mats.length][7]}, initialised entirely to
         * {@link Double#NaN}. Filled in-place by {@link VolatilityDownloader#download}.
         */
        final double[][] iVols;

        /**
         * Matched option contracts {@code [mats.length][7]}.
         * A {@code null} entry means no suitable contract was found for that (maturity, strike) slot.
         */
        final Contract[][] contracts;

        /**
         * Total number of non-null slots in {@link #contracts}.
         * Used by {@link VolatilityDownloader} to size the {@link CountDownLatch} and
         * calculate the proportional download timeout.
         */
        final int contractCount;

        MatchResult(double[] mats, double[][] iVols, Contract[][] contracts, int contractCount) {
            this.mats          = mats;
            this.iVols         = iVols;
            this.contracts     = contracts;
            this.contractCount = contractCount;
        }
    }


    /* ===========================================================================
     * CHAIN DOWNLOAD
     * ===========================================================================
     */

    /**
     * Downloads available option contracts from IBKR for the given maturity filters.
     * <p>
     * The method performs the following steps:
     * <ol>
     *   <li>Creates a {@link CountDownLatch} of size {@code targetFilters.size()} — one
     *       count per filter request.</li>
     *   <li>Sends one {@code reqContractDetails} per filter (75 ms apart to avoid TWS
     *       throttling). Each filter is either a {@code yyyyMM} string (for maturities ≤ 1 year)
     *       or a {@code yyyy} string (for maturities > 1 year).</li>
     *   <li>Collects returned contracts in {@code contractDetailsEnd()} and counts down
     *       the latch. Error code 200 ("No security definition") also counts down to
     *       prevent a deadlock when a filter yields no results.</li>
     *   <li>Waits up to 120 seconds for all responses, then returns the collected list.</li>
     * </ol>
     * </p>
     *
     * @param symbol        The underlying ticker (e.g. "SPX").
     * @param exchange      The option exchange (e.g. "CBOE", "SMART").
     * @param currency      The currency (e.g. "USD").
     * @param targetFilters Set of date-filter strings ({@code yyyyMM} or {@code yyyy}).
     * @return List of all option {@link Contract} objects returned by TWS.
     *         Returns an empty list on timeout or connection failure.
     * @throws Exception If the connection to TWS fails.
     */
    static List<Contract> getChain(String symbol, String exchange, String currency,
                                   Set<String> targetFilters) throws Exception {

        long t0 = System.currentTimeMillis();
        List<Contract> optionList = new ArrayList<>();

        // One latch count per filter — released in contractDetailsEnd() or on error 200
        CountDownLatch latch = new CountDownLatch(targetFilters.size());

        IBWrapperImpl wrapper = new IBWrapperImpl() {

            /** Adds each returned contract to the shared list. */
            @Override public void contractDetails(int reqId, com.ib.client.ContractDetails cd) {
                optionList.add(cd.contract());
            }

            /** Signals that all contracts for this filter have been received. */
            @Override public void contractDetailsEnd(int reqId) { latch.countDown(); }

            /**
             * Error 200 = "No security definition found".
             * Count down to avoid deadlock when a filter yields no contracts.
             */
            @Override public void error(int id, int code, String msg) {
                if (code == 200) latch.countDown();
            }

            @Override public void error(Exception e) {}
            @Override public void error(String s) {}
            @Override public void securityDefinitionOptionParameter(int r, String ex, int u,
                String tc, String m, Set<String> exp, Set<Double> str) {}
            @Override public void securityDefinitionOptionParameterEnd(int r) {}
        };

        System.out.println(">>> SCARICO MINI-CHAIN (filtri: " + targetFilters + ")...");

        // Open dedicated connection for chain download (clientId 998)
        EClientSocket client = IBKRConnection.connectClient(wrapper, 998);
        try {
            int reqId = 10;
            for (String filter : targetFilters) {
                Contract c = new Contract();
                c.symbol(symbol); c.secType("OPT"); c.currency(currency);
                c.exchange(exchange); c.multiplier("100");
                c.lastTradeDateOrContractMonth(filter);
                client.reqContractDetails(reqId++, c);
                // 75 ms between requests to avoid TWS request throttling
                Thread.sleep(75);
            }
            latch.await(120, TimeUnit.SECONDS);
            System.out.printf(">>> MINI-CHAIN: %d contratti in %d ms%n",
                    optionList.size(), System.currentTimeMillis() - t0);
            return optionList;
        } catch (Exception e) {
            System.err.println(">>> Timeout mini-chain.");
            return new ArrayList<>();
        } finally {
            client.eDisconnect();
        }
    }


    /* ===========================================================================
     * GRID MATCHING
     * ===========================================================================
     */

    /**
     * Matches the downloaded option chain to the 7×7 target maturity/strike grid.
     * <p>
     * For each of the 7 target maturities:
     * <ol>
     *   <li>Finds the closest available expiration date within a tolerance window
     *       (±60 days for maturities ≤ 1 year, ±300 days for maturities > 1 year).</li>
     *   <li>For each of the 7 target strikes, selects the nearest listed strike of the
     *       correct option type: <b>Call</b> for strikes ≥ ATM ({@code scaling ≥ 1.0}),
     *       <b>Put</b> for strikes &lt; ATM ({@code scaling < 1.0}).</li>
     *   <li>Unmatched slots remain as {@code null} in the contract matrix and
     *       {@link Double#NaN} in the iVol matrix — both handled downstream by
     *       {@link VolSurfacePatcher}.</li>
     * </ol>
     * </p>
     *
     * @param fullChain        The list of available contracts returned by {@link #getChain}.
     * @param spotPrice        The current underlying price, used to compute absolute target strikes.
     * @param targetMaturities Array of 7 target maturities in years (e.g. 30/365, 60/365, ...).
     * @param strikeScaling    Array of 7 strike multipliers relative to spot
     *                         (e.g. {1.10, 1.05, ..., 0.90}).
     * @param today            The reference date used to compute days to expiration.
     * @return A {@link MatchResult} containing the matched maturities, NaN-initialised iVols,
     *         matched contracts, and the total non-null contract count.
     */
    static MatchResult matchContracts(List<Contract> fullChain, double spotPrice,
                                      double[] targetMaturities, double[] strikeScaling,
                                      LocalDate today) {

        System.out.println(">>> ABBINAMENTO STRIKE/SCADENZE...");
        long t0 = System.currentTimeMillis();

        List<Double>     validMaturities = new ArrayList<>();
        List<double[]>   validIVols      = new ArrayList<>();
        List<Contract[]> validContracts  = new ArrayList<>();
        int contractCount = 0;
        Set<String> usedDates = new HashSet<>(); // prevents reusing the same expiration twice

        for (double targetMat : targetMaturities) {
            double targetDays = targetMat * 365.0;

            // Wider tolerance for long-dated options: fewer liquid expirations available
            double maxTol   = targetDays > 365 ? 300 : 60;
            String bestDate = findBestDate(fullChain, today, targetDays, maxTol, usedDates);

            if (bestDate == null) {
                System.out.printf(">>> [WARN] Scadenza %.0f gg saltata%n", targetDays);
                continue; // Skip this maturity — no suitable expiration found
            }
            usedDates.add(bestDate);

            // Initialise iVols row to NaN — filled later by VolatilityDownloader
            double[]   rowIVols     = new double[strikeScaling.length];
            Arrays.fill(rowIVols, Double.NaN);
            Contract[] rowContracts = new Contract[strikeScaling.length];

            List<Contract> dateContracts = filterByDate(fullChain, bestDate);
            Set<Double> usedStrikes = new HashSet<>(); // prevents reusing the same strike twice

            for (int j = 0; j < strikeScaling.length; j++) {
                double targetStrike = spotPrice * strikeScaling[j];
                // Calls for ATM and OTM-up, Puts for OTM-down
                String right = strikeScaling[j] >= 1.0 ? "C" : "P";
                Contract best = findBestStrike(dateContracts, targetStrike, right, usedStrikes);
                if (best != null) {
                    rowContracts[j] = best;
                    usedStrikes.add(best.strike());
                    contractCount++;
                }
            }

            validMaturities.add(targetMat);
            validIVols.add(rowIVols);
            validContracts.add(rowContracts);
        }

        System.out.printf(">>> %d contratti abbinati in %d ms%n",
                contractCount, System.currentTimeMillis() - t0);

        double[]     mats      = validMaturities.stream().mapToDouble(Double::doubleValue).toArray();
        double[][]   iVols     = validIVols.toArray(new double[0][0]);
        Contract[][] contracts = validContracts.toArray(new Contract[0][0]);

        return new MatchResult(mats, iVols, contracts, contractCount);
    }


    /* ===========================================================================
     * PRIVATE UTILITY METHODS
     * ===========================================================================
     */

    /**
     * Finds the expiration date in the chain closest to the target number of days,
     * within the given tolerance and not already used by a previous maturity.
     * <p>
     * TWS returns expiration strings in two formats:
     * <ul>
     *   <li>{@code yyyyMMdd} (8 chars) — used as-is after truncation to 8 chars.</li>
     *   <li>{@code yyyyMM} (6 chars) — padded with "15" (mid-month) for day comparison.</li>
     * </ul>
     * </p>
     *
     * @param chain      The full list of available contracts.
     * @param today      The reference date.
     * @param targetDays Target number of calendar days to expiration.
     * @param maxTol     Maximum allowed deviation in days.
     * @param usedDates  Set of expiration strings already assigned to earlier maturities.
     * @return The best matching expiration string, or {@code null} if none is within tolerance.
     */
    private static String findBestDate(List<Contract> chain, LocalDate today,
                                       double targetDays, double maxTol, Set<String> usedDates) {
        String best    = null;
        double minDiff = Double.MAX_VALUE;

        for (Contract c : chain) {
            try {
                String ds = c.lastTradeDateOrContractMonth();
                if (ds == null || ds.isBlank() || usedDates.contains(ds)) continue;
                ds = ds.trim();
                // Normalise to 8-char date: pad 6-char "yyyyMM" with day "15"
                String norm = ds.length() == 6 ? ds + "15" : ds.substring(0, 8);
                double diff = Math.abs(
                        ChronoUnit.DAYS.between(today, LocalDate.parse(norm, IBKRConnection.FMT_8))
                        - targetDays);
                if (diff < minDiff) { minDiff = diff; best = c.lastTradeDateOrContractMonth(); }
            } catch (Exception ignored) {}
        }
        return (best != null && minDiff <= maxTol) ? best : null;
    }

    /**
     * Returns all contracts in the chain that match the given expiration string
     * and have a non-null {@code right} field (Call or Put).
     *
     * @param chain The full contract list.
     * @param date  The expiration string to match.
     * @return Filtered list of contracts for the given expiration.
     */
    private static List<Contract> filterByDate(List<Contract> chain, String date) {
        List<Contract> result = new ArrayList<>();
        for (Contract c : chain)
            if (date.equals(c.lastTradeDateOrContractMonth()) && c.getRight() != null)
                result.add(c);
        return result;
    }

    /**
     * Finds the contract with the strike closest to {@code targetStrike}, of the correct
     * option type (Call/Put), and not already assigned to a neighbouring strike slot.
     *
     * @param list         Contracts available for a given expiration date.
     * @param targetStrike Absolute strike price to match (e.g. spot × 1.05).
     * @param right        Option type: "C" for Call, "P" for Put.
     * @param used         Set of strikes already assigned in this expiration row.
     * @return The best matching {@link Contract}, or {@code null} if none is available.
     */
    private static Contract findBestStrike(List<Contract> list, double targetStrike,
                                           String right, Set<Double> used) {
        Contract best    = null;
        double   minDiff = Double.MAX_VALUE;

        for (Contract c : list) {
            if (!right.equalsIgnoreCase(c.getRight())) continue;
            if (used.contains(c.strike())) continue;
            double diff = Math.abs(c.strike() - targetStrike);
            if (diff < minDiff) { minDiff = diff; best = c; }
        }
        return best;
    }
}
