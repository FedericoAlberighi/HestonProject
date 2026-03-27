package it.univr.derivatives.marketdataprovider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Repairs missing implied volatilities in the downloaded surface and discards
 * maturities with too many missing values.
 * <p>
 * This class is <b>pure</b>: it has no dependency on the IBKR API and can be
 * tested with synthetic data without a live TWS connection.
 * </p>
 * <p>
 * Missing values are represented by {@link Double#NaN} sentinels set by
 * {@link OptionChainFetcher} when no contract is matched, or left untouched by
 * {@link VolatilityDownloader} when no valid IV tick arrives. The patching
 * strategy, applied per slot in order of preference, is:
 * <ol>
 *   <li><b>Linear interpolation</b> between the nearest left and right neighbours
 *       along the strike axis (same maturity row).</li>
 *   <li><b>Flat extrapolation</b> from the nearest left neighbour (if no right exists).</li>
 *   <li><b>Flat extrapolation</b> from the nearest right neighbour (if no left exists).</li>
 *   <li><b>Cross-maturity copy</b> from the same strike column in the nearest other
 *       maturity row that has a valid value.</li>
 *   <li><b>Hard default of 20%</b> as a last resort, with a console warning.</li>
 * </ol>
 * If a maturity row has more than {@link #MAX_PATCHED_PER_MATURITY} patched slots,
 * it is discarded entirely to avoid corrupting the Heston calibration with unreliable data.
 * </p>
 *
 * @author Federico Alberighi
 * @version 1.0
 */
class VolSurfacePatcher {



    /**
     * Maximum number of NaN slots that may be patched in a single maturity row
     * before the entire row is discarded.
     * <br>A row with {@code patchedCount > MAX_PATCHED_PER_MATURITY} is considered
     * too sparse to be a reliable calibration input.
     */
    private static final int MAX_PATCHED_PER_MATURITY = 2;


    /* ===========================================================================
     * PATCHING
     * ===========================================================================
     */

    /**
     * Repairs NaN entries in the implied-volatility matrix and returns the subset of
     * maturities whose rows survived the quality filter.
     * <p>
     * The method modifies {@code iVols} <b>in-place</b> and returns an array of
     * maturities corresponding to the rows that were not discarded.
     * </p>
     * <p>
     * Patching strategy per NaN slot (in priority order):
     * <ol>
     *   <li><b>Linear interpolation:</b> if valid neighbours exist on both sides of
     *       the NaN along the strike axis, interpolate linearly using the strike
     *       scaling values as x-coordinates.</li>
     *   <li><b>Left extrapolation:</b> copy the nearest valid value to the left
     *       (higher strike, more liquid).</li>
     *   <li><b>Right extrapolation:</b> copy the nearest valid value to the right
     *       (lower strike, more OTM).</li>
     *   <li><b>Cross-maturity:</b> copy the value from the same strike column in the
     *       first other maturity row that has a valid value.</li>
     *   <li><b>Default 20%:</b> last resort — a constant 20% IV is assigned with a
     *       warning printed to the console.</li>
     * </ol>
     * After patching, rows with {@code patchedCount > MAX_PATCHED_PER_MATURITY} are
     * flagged as invalid and excluded from the returned maturities array.
     * </p>
     *
     * @param mats          Input maturity array (in years). Not modified.
     * @param strikeScaling Strike multipliers used as x-coordinates for interpolation.
     * @param iVols         Implied-volatility matrix modified in-place.
     *                      Dimensions: {@code [mats.length][strikeScaling.length]}.
     * @return Array of maturities (subset of {@code mats}) corresponding to rows that
     *         passed the quality filter. Length ≤ {@code mats.length}.
     * @throws RuntimeException If all maturity rows are discarded after patching.
     */
    static double[] patch(double[] mats, double[] strikeScaling, double[][] iVols) {

        long t0 = System.currentTimeMillis();
        boolean[] valid = new boolean[mats.length];
        Arrays.fill(valid, true);

        for (int i = 0; i < mats.length; i++) {
            int patchedCount = 0;

            for (int j = 0; j < strikeScaling.length; j++) {
                if (!Double.isNaN(iVols[i][j])) continue; // Slot already filled — skip
                patchedCount++;

                // Find nearest valid left neighbour (higher strike)
                double leftVal = Double.NaN; int leftIdx = -1;
                for (int k = j - 1; k >= 0; k--)
                    if (!Double.isNaN(iVols[i][k])) { leftVal = iVols[i][k]; leftIdx = k; break; }

                // Find nearest valid right neighbour (lower strike)
                double rightVal = Double.NaN; int rightIdx = -1;
                for (int k = j + 1; k < strikeScaling.length; k++)
                    if (!Double.isNaN(iVols[i][k])) { rightVal = iVols[i][k]; rightIdx = k; break; }

                if (!Double.isNaN(leftVal) && !Double.isNaN(rightVal)) {
                    // 1. Linear interpolation between left and right neighbours
                    double x  = strikeScaling[j];
                    double x1 = strikeScaling[leftIdx];
                    double x2 = strikeScaling[rightIdx];
                    iVols[i][j] = leftVal + (x - x1) * (rightVal - leftVal) / (x2 - x1);
                    System.out.printf(">>> [PATCH] %.0fgg j=%d: interp. lineare → %.2f%%%n",
                            mats[i]*365, j, iVols[i][j]*100);

                } else if (!Double.isNaN(leftVal)) {
                    // 2. Flat extrapolation from the left
                    iVols[i][j] = leftVal;
                    System.out.printf(">>> [PATCH] %.0fgg j=%d: extrap. flat sx → %.2f%%%n",
                            mats[i]*365, j, leftVal*100);

                } else if (!Double.isNaN(rightVal)) {
                    // 3. Flat extrapolation from the right
                    iVols[i][j] = rightVal;
                    System.out.printf(">>> [PATCH] %.0fgg j=%d: extrap. flat dx → %.2f%%%n",
                            mats[i]*365, j, rightVal*100);

                } else {
                    // 4. Cross-maturity: copy from the same strike column in another row
                    boolean found = false;
                    for (int r = 0; r < mats.length; r++) {
                        if (r != i && !Double.isNaN(iVols[r][j])) {
                            iVols[i][j] = iVols[r][j];
                            System.out.printf(">>> [PATCH] %.0fgg j=%d: cross-mat da %.0fgg → %.2f%%%n",
                                    mats[i]*365, j, mats[r]*365, iVols[r][j]*100);
                            found = true; break;
                        }
                    }
                    if (!found) {
                        // 5. Hard default — last resort, value is unreliable
                        iVols[i][j] = 0.20;
                        System.out.printf(">>> [PATCH] %.0fgg j=%d: nessun dato — default 20%%%n",
                                mats[i]*365, j);
                    }
                }
            }

            // Discard the row if too many slots were patched
            if (patchedCount > MAX_PATCHED_PER_MATURITY) {
                valid[i] = false;
                System.out.printf(">>> ⚠ SCADENZA %.0f GG SCARTATA: %d/%d patchati (soglia %d)%n",
                        mats[i]*365, patchedCount, strikeScaling.length, MAX_PATCHED_PER_MATURITY);
            } else if (patchedCount > 0) {
                System.out.printf(">>> ✓ Scadenza %.0fgg: %d patchati (ok)%n",
                        mats[i]*365, patchedCount);
            }
        }

        // Collect surviving maturities
        List<Double> cleanList = new ArrayList<>();
        for (int i = 0; i < mats.length; i++) if (valid[i]) cleanList.add(mats[i]);

        if (cleanList.isEmpty())
            throw new RuntimeException("Tutte le scadenze scartate — dati insufficienti.");

        System.out.printf(">>> Patching: %d ms — %d/%d scadenze valide%n",
                System.currentTimeMillis() - t0, cleanList.size(), mats.length);

        return cleanList.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
