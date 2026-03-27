package it.univr.derivatives.marketdataprovider;

import com.ib.client.Contract;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveInterpolation;
import net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity;
import net.finmath.marketdata.model.volatilities.OptionSmileData;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Constructs the finmath-lib objects required for Heston model calibration.
 * <p>
 * This class is <b>pure</b>: it has no dependency on the IBKR API and can be tested
 * with synthetic data without a live TWS connection.
 * </p>
 * <p>
 * Two curves are built, following the same approach used by {@code MarketDataProvider}
 * for the historical DAX surface:
 * <ul>
 *   <li><b>Discount curve</b> — constructed from zero rates via
 *       {@code createDiscountCurveFromZeroRates}: {@code P(T) = e^{-r(T)·T}}.
 *       A node at {@code t=0} with rate 0 is prepended so that {@code P(0) = 1}.</li>
 *   <li><b>Equity forward curve</b> — constructed from discount factors via
 *       {@code createDiscountCurveFromDiscountFactors}: {@code F(T) = S · e^{r(T)·T}}.
 *       Dividend yield is currently assumed to be zero ({@code q = 0}).
 *       A node at {@code t=0} with value {@code S} is prepended so that {@code F(0) = S}.</li>
 * </ul>
 * Both curves use linear interpolation and constant extrapolation on the log of the value,
 * consistent with the DAX provider.
 * </p>
 * <p>
 * Strike values used in {@link OptionSmileData} are the actual listed strikes returned by
 * IBKR (from {@code contract.strike()}), not the theoretical spot × scaling values.
 * If a contract slot is null, the fallback is {@code spot × strikeScaling[j]}.
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class FinmathSurfaceBuilder {

    /* ===========================================================================
     * SURFACE BUILDER
     * ===========================================================================
     */

    /**
     * Builds a finmath-lib {@link OptionSurfaceData} from the downloaded and patched
     * implied-volatility grid.
     * <p>
     * The method performs the following steps:
     * <ol>
     *   <li>Prepends a {@code t=0} node to the maturity and rate arrays (required by finmath).</li>
     *   <li>Constructs the discount curve {@code P(T) = e^{-r(T)·T}} using
     *       {@code createDiscountCurveFromZeroRates} with the provided {@code zeroRates}.</li>
     *   <li>Computes forward factors {@code F(T) = S · e^{r(T)·T}} for each maturity
     *       (dividend yield = 0) and constructs the equity forward curve.</li>
     *   <li>For each maturity row, extracts the actual listed strikes from the matched
     *       contracts and wraps the smile data in an {@link OptionSmileData} object.</li>
     *   <li>Assembles all smiles, the discount curve, and the forward curve into an
     *       {@link OptionSurfaceData} and returns it.</li>
     * </ol>
     * </p>
     *
     * @param symbol         The underlying ticker, used as the label in {@link OptionSmileData}.
     * @param referenceDate  The pricing date, used as the reference for the discount curve.
     * @param spotPrice      The current underlying price {@code S}.
     * @param mats           Array of valid maturities in years (after patching/filtering).
     * @param zeroRates      Array of zero rates in decimal (e.g. 0.05 = 5%), one per maturity.
     *                       Must have length ≥ {@code mats.length}.
     * @param strikeScaling  Array of 7 strike multipliers (used as fallback if a contract is null).
     * @param iVols          Patched implied-volatility matrix {@code [mats.length][7]}.
     * @param contracts      Matched contract matrix {@code [mats.length][7]}; may contain nulls.
     * @return A fully constructed {@link OptionSurfaceData} ready for Heston calibration.
     * @throws IllegalArgumentException If {@code zeroRates.length < mats.length}.
     */
    static OptionSurfaceData build(String symbol, LocalDate referenceDate, double spotPrice,
                                   double[] mats, double[] zeroRates,
                                   double[] strikeScaling, double[][] iVols,
                                   Contract[][] contracts) {

        long t0 = System.currentTimeMillis();
        int n = mats.length;

        // Guard: zeroRates must cover all surviving maturities
        if (zeroRates.length < n)
            throw new IllegalArgumentException(String.format(
                    "zeroRates ha %d elementi ma ci sono %d scadenze valide. " +
                    "Fornire almeno %d zero rates.", zeroRates.length, n, n));

        // 1. Prepend t=0 node to maturities and zero rates (finmath convention)
        double[] newMaturities = new double[n + 1];
        newMaturities[0] = 0.0;
        System.arraycopy(mats, 0, newMaturities, 1, n);

        double[] newZeroRates = new double[n + 1];
        newZeroRates[0] = 0.0; // P(0) = e^0 = 1 (one unit today is worth one unit today)
        System.arraycopy(zeroRates, 0, newZeroRates, 1, n);

        // Curve interpolation settings — identical to MarketDataProvider (DAX)
        ExtrapolationMethod exMethod  = ExtrapolationMethod.CONSTANT;
        InterpolationMethod intMethod = InterpolationMethod.LINEAR;
        InterpolationEntity intEntity = InterpolationEntity.LOG_OF_VALUE;

        // 2. Build discount curve: P(T) = e^{-r(T)·T}
        DiscountCurve discountCurve = DiscountCurveInterpolation.createDiscountCurveFromZeroRates(
                "spxDiscountCurve",
                referenceDate,
                newMaturities,
                newZeroRates,
                intMethod, exMethod, intEntity);

        // 3. Compute forward factors and build equity forward curve: F(T) = S · e^{r(T)·T}
        //    Dividend yield q = 0 (assumption: no dividends on the index)
        double[] forwardFactors = new double[n + 1];
        forwardFactors[0] = spotPrice; // F(0) = S
        for (int k = 0; k < n; k++)
            forwardFactors[k + 1] = spotPrice * Math.exp(zeroRates[k] * mats[k]);

        DiscountCurve equityForwardCurve = DiscountCurveInterpolation.createDiscountCurveFromDiscountFactors(
                "spxForwardCurve",
                newMaturities,
                forwardFactors,
                intMethod, exMethod, intEntity);

        // 4. Build OptionSmileData for each maturity using real listed strikes
        QuotingConvention convention = QuotingConvention.VOLATILITYLOGNORMAL;
        OptionSmileData[] smiles = new OptionSmileData[n];

        for (int i = 0; i < n; i++) {
            double[] realStrikes = new double[strikeScaling.length];
            for (int j = 0; j < strikeScaling.length; j++)
                // Prefer the actual listed strike; fall back to theoretical if contract is null
                realStrikes[j] = contracts[i][j] != null
                        ? contracts[i][j].strike()
                        : spotPrice * strikeScaling[j];
            smiles[i] = new OptionSmileData(
                    symbol, referenceDate, realStrikes, mats[i], iVols[i], convention);
        }

        // 5. Assemble and return the surface
        System.out.printf(">>> Oggetti Finmath: %d ms%n", System.currentTimeMillis() - t0);
        return new OptionSurfaceData(smiles, discountCurve, equityForwardCurve);
    }


    /* ===========================================================================
     * CONSOLE PRINT
     * ===========================================================================
     */

    /**
     * Prints a formatted summary of the implied-volatility surface to standard output.
     * <p>
     * For each maturity row the table shows the actual listed strike, the option type
     * (Call/Put), the implied volatility in percent, and the strike as a percentage
     * of the spot (ATM reference). The actual days to expiration are computed from the
     * contract's {@code lastTradeDateOrContractMonth} field using the ATM contract
     * (strike index 3, i.e. the 1.0× scaling slot).
     * </p>
     *
     * @param symbol      The underlying ticker label.
     * @param today       The reference date used to compute actual days to expiration.
     * @param mats        Array of maturities in years.
     * @param strikeScaling Array of strike multipliers (used as % ATM column).
     * @param iVols       Patched implied-volatility matrix.
     * @param contracts   Matched contract matrix; may contain nulls.
     */
    static void printSurface(String symbol, LocalDate today,
                             double[] mats, double[] strikeScaling,
                             double[][] iVols, Contract[][] contracts) {

        System.out.println("\n==================================================================");
        System.out.println("   📊 SUPERFICIE IVS REALE (" + symbol + ")");
        System.out.println("==================================================================");

        for (int i = 0; i < mats.length; i++) {
            int targetDays = (int) Math.round(mats[i] * 365);
            int actualDays = targetDays; // fallback if ATM contract is null

            // Compute actual days from the ATM contract (index 3 = 1.0× scaling)
            if (contracts[i][3] != null) {
                String exp = contracts[i][3].lastTradeDateOrContractMonth();
                try {
                    String s = exp.length() == 6 ? exp + "15" : exp.substring(0, 8);
                    actualDays = (int) ChronoUnit.DAYS.between(
                            today, LocalDate.parse(s, IBKRConnection.FMT_8));
                } catch (Exception ignored) {}
            }

            System.out.printf("%n 📁 TARGET: %d gg → SCADENZA REALE: %d gg%n", targetDays, actualDays);
            System.out.println(" ------------------------------------------------------------------");
            System.out.println(" |  STRIKE ($)  (TIPO)  |  IV (%)  |  % ATM  |");
            System.out.println(" ------------------------------------------------------------------");

            for (int j = 0; j < strikeScaling.length; j++) {
                double strike = contracts[i][j] != null ? contracts[i][j].strike() : 0;
                String right  = contracts[i][j] != null ? contracts[i][j].getRight() : "?";
                System.out.printf(" |  %9.2f    (%s)   |  %6.2f%%  |  %5.1f%%  |%n",
                        strike, right, iVols[i][j] * 100, strikeScaling[j] * 100);
            }
            System.out.println(" ------------------------------------------------------------------");
        }
    }
}
