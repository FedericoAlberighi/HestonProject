package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import it.univr.derivatives.utils.TimeSeries;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Public facade for live market data retrieval from Interactive Brokers (IBKR/TWS).
 * <p>
 * This class is the single entry point for all external callers (e.g. {@code VarianceSwapIB}).
 * It contains no business logic of its own: it delegates every step to six specialised
 * package-private classes and orchestrates their execution.
 * </p>
 *
 * <p><b>Internal architecture:</b></p>
 * <pre>
 *   IBKRMarketDataProvider  (this class — facade)
 *        │
 *        ├── IBKRConnection        — TCP connection to TWS, contract builder
 *        ├── SpotPriceFetcher      — spot price (1-min bar + daily fallback)
 *        ├── OptionChainFetcher    — option chain download + strike/maturity matching
 *        ├── VolatilityDownloader  — implied volatility download (thread-safe latch)
 *        ├── VolSurfacePatcher     — NaN patching + maturity quality filter  [pure]
 *        └── FinmathSurfaceBuilder — discount/forward curves + OptionSurfaceData [pure]
 * </pre>
 *
 * <p>
 * The main method {@link #getLiveVolatilitySurface} runs spot retrieval and option
 * chain download <b>in parallel</b> using {@link CompletableFuture#supplyAsync}, since
 * the two operations are independent. The remaining steps (matching → download → patch
 * → build) are sequential because each depends on the output of the previous one.
 * </p>
 *
 * <p>
 * Default zero rates ({@link #DEFAULT_ZERO_RATES}) approximate the SOFR curve for SPX.
 * For higher accuracy, supply an external rate array via the overloaded variant
 * {@link #getLiveVolatilitySurface(String, String, String, String, double[])}.
 * </p>
 *
 * @author Federico Alberighi
 * @version 2.0
 */
public class IBKRMarketDataProvider {

    /* ===========================================================================
     * CONSTANTS
     * ===========================================================================
     */

    /**
     * Default zero rates (decimal, e.g. 0.053 = 5.3%) approximating the SOFR curve
     * for SPX options, one per target maturity:
     * <br>{@code [30d, 60d, 90d, 180d, 1y, 1.5y, 2y]}
     * <p>Note: Update periodically to reflect current market rates.</p>
     */
    private static final double[] DEFAULT_ZERO_RATES ={
            0.0373,  // 30gg  — 1 mese
            0.0372,  // 60gg  — 2 mesi (interpolato)
            0.0373,  // 90gg  — 3 mesi
            0.0376,  // 180gg — 6 mesi
            0.0373,  // 365gg — 1 anno
            0.0376,  // 548gg — interpolato tra 1y e 2y
            0.0379   // 730gg — 2 anni
    };
    /**
     * Target strike multipliers relative to spot (ATM = 1.0).
     * <br>Calls: 1.10×, 1.05×, 1.025×, 1.0× — Puts: 0.975×, 0.95×, 0.90×.
     */
    private static final double[] STRIKE_SCALING = {1.10, 1.05, 1.025, 1.0, 0.975, 0.95, 0.90};

    /**
     * Target maturities in years: 30d, 60d, 90d, 180d, 1y, 1.5y, 2y.
     * These are the standard tenors used for Heston calibration, matching
     * the historical DAX surface in {@code MarketDataProvider}.
     */
    private static final double[] TARGET_MATURITIES = {
            30.0/365, 60.0/365, 90.0/365, 180.0/365, 365.0/365, 548.0/365, 730.0/365
    };


    /* ===========================================================================
     * PUBLIC API
     * ===========================================================================
     */

    /**
     * Returns the current spot price of the specified underlying.
     * Delegates to {@link SpotPriceFetcher#fetch}.
     *
     * @param symbol   Ticker symbol (e.g. "SPX", "SPY").
     * @param secType  Security type (e.g. "IND", "STK").
     * @param exchange Exchange (e.g. "CBOE", "SMART").
     * @param currency Currency (e.g. "USD").
     * @return Current spot price as a positive {@code double}.
     * @throws Exception If TWS is unreachable or no price data is available.
     */
    public static double getRealTimeSpotPrice(
            String symbol, String secType, String exchange, String currency) throws Exception {
        return SpotPriceFetcher.fetch(symbol, secType, exchange, currency);
    }

    /**
     * Downloads a daily historical price series from IBKR.
     * Delegates to {@link SpotPriceFetcher#fetchHistorical}.
     *
     * @param symbol   Ticker symbol.
     * @param secType  Security type.
     * @param exchange Exchange.
     * @param currency Currency.
     * @param duration IBKR duration string (e.g. "1 Y", "6 M", "60 D").
     * @return {@link TimeSeries} of daily closes, or {@code null} on timeout.
     * @throws Exception If the connection to TWS fails.
     */
    public static TimeSeries getHistoricalData(
            String symbol, String secType, String exchange, String currency,
            String duration) throws Exception {
        return SpotPriceFetcher.fetchHistorical(symbol, secType, exchange, currency, duration);
    }

    /**
     * Downloads the option chain for the given maturity filters.
     * Delegates to {@link OptionChainFetcher#getChain}.
     *
     * @param symbol        Underlying ticker.
     * @param exchange      Option exchange.
     * @param currency      Currency.
     * @param targetFilters Set of date-filter strings ({@code yyyyMM} or {@code yyyy}).
     * @return List of available option {@link Contract} objects.
     * @throws Exception If the connection to TWS fails.
     */
    public static List<Contract> getTargetDatesChain(
            String symbol, String exchange, String currency,
            Set<String> targetFilters) throws Exception {
        return OptionChainFetcher.getChain(symbol, exchange, currency, targetFilters);
    }

    /**
     * Retrieves the live implied-volatility surface using {@link #DEFAULT_ZERO_RATES}.
     * Convenience overload — calls {@link #getLiveVolatilitySurface(String, String, String, String, double[])}.
     *
     * @param symbol   Underlying ticker (e.g. "SPX").
     * @param secType  Security type (e.g. "IND").
     * @param exchange Exchange (e.g. "CBOE").
     * @param currency Currency (e.g. "USD").
     * @return A fully calibrated {@link OptionSurfaceData} for the Heston model.
     * @throws Exception If any network or data step fails.
     */
    public static OptionSurfaceData getLiveVolatilitySurface(
            String symbol, String secType, String exchange, String currency) throws Exception {
        return getLiveVolatilitySurface(symbol, secType, exchange, currency, DEFAULT_ZERO_RATES);
    }

    /**
     * Retrieves the live implied-volatility surface using externally supplied zero rates.
     * <p>
     * Orchestration sequence:
     * <ol>
     *   <li>Computes IBKR date filters from {@link #TARGET_MATURITIES} (no network call).</li>
     *   <li>Launches spot retrieval and chain download <b>in parallel</b> via
     *       {@link CompletableFuture#supplyAsync} — the two are independent.</li>
     *   <li>Matches the downloaded contracts to the 7×7 grid via
     *       {@link OptionChainFetcher#matchContracts}.</li>
     *   <li>Downloads implied volatilities for all matched contracts via
     *       {@link VolatilityDownloader#download}.</li>
     *   <li>Patches missing values and discards low-quality maturities via
     *       {@link VolSurfacePatcher#patch}.</li>
     *   <li>Re-aligns the iVols and contract matrices to the surviving maturities.</li>
     *   <li>Prints the surface summary and builds the finmath objects via
     *       {@link FinmathSurfaceBuilder}.</li>
     * </ol>
     * </p>
     *
     * @param symbol     Underlying ticker (e.g. "SPX").
     * @param secType    Security type (e.g. "IND").
     * @param exchange   Exchange (e.g. "CBOE").
     * @param currency   Currency (e.g. "USD").
     * @param zeroRates  Array of zero rates in decimal, one per target maturity (length ≥ 7).
     * @return A fully constructed {@link OptionSurfaceData} ready for Heston calibration.
     * @throws Exception If any network, data, or validation step fails.
     */
    public static OptionSurfaceData getLiveVolatilitySurface(
            String symbol, String secType, String exchange, String currency,
            double[] zeroRates) throws Exception {

        long t0 = System.currentTimeMillis();
        LocalDate today = LocalDate.now();

        // 1. Compute IBKR date filters from target maturities (no network call)
        Set<String> filters = new HashSet<>();
        for (double mat : TARGET_MATURITIES) {
            LocalDate d = today.plusDays(Math.round(mat * 365));
            filters.add(mat <= 1.0
                    ? d.format(IBKRConnection.FMT_YYYYMM)
                    : d.format(IBKRConnection.FMT_YYYY));
        }

        // 2. Spot and chain in parallel — both independent of each other
        CompletableFuture<Double> spotFuture = CompletableFuture.supplyAsync(() -> {
            try { return SpotPriceFetcher.fetch(symbol, secType, exchange, currency); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        CompletableFuture<List<Contract>> chainFuture = CompletableFuture.supplyAsync(() -> {
            try { return OptionChainFetcher.getChain(symbol, exchange, currency, filters); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        CompletableFuture.allOf(spotFuture, chainFuture).join();

        double spotPrice     = spotFuture.join();
        List<Contract> chain = chainFuture.join();

        if (spotPrice <= 0)  throw new RuntimeException("Spot non valido.");
        if (chain.isEmpty()) throw new RuntimeException("Chain vuota. Verifica connessione TWS.");

        // 3. Match downloaded contracts to the 7x7 target grid
        OptionChainFetcher.MatchResult match =
                OptionChainFetcher.matchContracts(chain, spotPrice, TARGET_MATURITIES, STRIKE_SCALING, today);
        if (match.mats.length == 0) throw new RuntimeException("Nessuna scadenza valida.");

        // 4. Download implied volatilities (fills match.iVols in-place)
        VolatilityDownloader.download(
                match.mats, STRIKE_SCALING, match.iVols, match.contracts, match.contractCount);

        // 5. Patch NaN slots and discard low-quality maturity rows
        double[] cleanMats = VolSurfacePatcher.patch(match.mats, STRIKE_SCALING, match.iVols);

        // 6. Re-align iVols and contracts to the surviving maturities
        List<double[]>   cleanIVols     = new ArrayList<>();
        List<Contract[]> cleanContracts = new ArrayList<>();
        for (double cleanMat : cleanMats) {
            for (int k = 0; k < match.mats.length; k++) {
                if (Math.abs(match.mats[k] - cleanMat) < 1e-9) {
                    cleanIVols.add(match.iVols[k]);
                    cleanContracts.add(match.contracts[k]);
                    break;
                }
            }
        }
        double[][]   finalIVols     = cleanIVols.toArray(new double[0][0]);
        Contract[][] finalContracts = cleanContracts.toArray(new Contract[0][0]);

        // 7. Print summary and build finmath surface
        FinmathSurfaceBuilder.printSurface(symbol, today, cleanMats, STRIKE_SCALING,
                finalIVols, finalContracts);

        OptionSurfaceData surface = FinmathSurfaceBuilder.build(
                symbol, today, spotPrice, cleanMats, zeroRates,
                STRIKE_SCALING, finalIVols, finalContracts);

        System.out.printf(">>> [TIMING GLOBALE] %d ms%n", System.currentTimeMillis() - t0);
        return surface;
    }
}
