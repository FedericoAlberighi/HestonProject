package it.univr.derivatives20252026.exercise1;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeMap;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.HestonCalibrationClass;
import it.univr.derivatives.utils.TimeSeries;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;

/**
 * <p><strong>Exercise 1: Model Risk and Historical Recalibration.</strong></p>
 * <p>
 * This class implements the stability analysis of the Heston model parameters through a
 * daily calibration procedure (Rolling Calibration).
 * </p>
 * <p>
 * The goal is to observe how the 5 model parameters (<tt>sigma, theta, kappa, xi, rho</tt>)
 * evolve over time to adapt to new market volatility surfaces.
 * </p>
 * <p>
 * The class uses a <b>Bootstrapping the parameters </b>: the optimal parameters
 * calibrated at day <i>t</i> are used as the <i>Initial Guess</i> for the calibration
 * at day <i>t+1</i>.
 * </p>
 *
 * @author Federico Alberighi
 * @author Alice Bonizzoni
 * @version 1.1
 */
public class HistoricalRecalibration {

    /**
     * <p>The workflow is as follows:</p>
     * <ol>
     * <li>Loading historical volatility surfaces (from Excel/CSV).</li>
     * <li>Initialization of TimeSeries to store the results.</li>
     * <li>Definition of starting parameters (Initial Guess).</li>
     * <li>Execution of the daily calibration loop (with daily update).</li>
     * <li>Generation of final plots.</li>
     * </ol>
     */
    public static void main(String[] args) throws Exception {

        // =======================================================================
        // 1. MARKET DATA LOADING
        // =======================================================================
        System.out.println("Loading market data...");

        // TreeMap sorted by date containing volatility surfaces (Smile/Skew)
        TreeMap<LocalDate, OptionSurfaceData> marketData = MarketDataProvider.getVolatilityDataContainer();
        Set<LocalDate> keys = marketData.keySet();

        // =======================================================================
        // 2. DATA STRUCTURES PREPARATION
        // =======================================================================

        // Initialization of TimeSeries to track the evolution of each parameter
        TimeSeries volatilityTimeSeries = new TimeSeries(); // Sigma (spot volatility)
        TimeSeries thetaTimeSeries = new TimeSeries();      // Theta (long-run variance)
        TimeSeries kappaTimeSeries = new TimeSeries();      // Kappa (mean reversion speed)
        TimeSeries xiTimeSeries = new TimeSeries();         // Xi (volatility of volatility)
        TimeSeries rhoTimeSeries = new TimeSeries();        // Rho (spot-vol correlation)
        TimeSeries rmseTimeSeries = new TimeSeries();       // RMSE (calibration error)

        /*
         * Initial Parameters (Initial Guess) for the first calibration day.
         * [0]: volatility (sqrt(V0))
         * [1]: theta
         * [2]: kappa
         * [3]: xi
         * [4]: rho
         */
        double[] currentParameters = new double[] {
                0.0423,
                0.0818,
                0.8455,
                0.4639,
                -0.4
        };

        HestonCalibrationClass calibrator = new HestonCalibrationClass();

        /*
         * Analysis time window definition.
         * It is advisable to limit the period to manage computation times.
         */
        LocalDate startDate = LocalDate.of(2010, 3, 1);
        LocalDate limit = LocalDate.of(2010, 4, 15);

        System.out.println("Starting calibration from " + startDate + " to " + limit);
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------");
        // Output table header for console monitoring
        System.out.printf("%-12s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s | %-6s | %-8s%n",
                "Date", "RMSE", "Vol", "Theta", "Kappa", "Xi", "Rho", "Iter", "Time(s)");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------");

        // =======================================================================
        // 3. DAILY CALIBRATION LOOP
        // =======================================================================
        for(LocalDate today : keys) {

            // Skip dates outside the selected time window
            if(today.isBefore(startDate)) continue;
            if(today.isAfter(limit)) break;

            // Retrieve the specific volatility surface for the current day
            OptionSurfaceData todaysMarket = marketData.get(today);

            try {
                // Timer to monitor calibration performance
                long startMillis = System.currentTimeMillis();

                /*
                 * --- CORE CALL TO CALIBRATION ---
                 * We pass 'currentParameters' which contains the results of the previous day (t-1).
                 * This implements the sequential logic (Parameter Bootstrapping).
                 */
                OptimizationResult result = calibrator.calibrate(today, todaysMarket, currentParameters);

                long endMillis = System.currentTimeMillis();
                double calculationTime = ((endMillis - startMillis) / 1000.0);

                // --- EXTRACT RESULTS ---
                double rmse = result.getRootMeanSquaredError();
                int iterations = result.getIterations();

                // Extract calibrated parameters from the Heston model descriptor
                HestonModelDescriptor calibratedDescriptor = (HestonModelDescriptor) result.getModel().getModelDescriptor();

                double calibVol = calibratedDescriptor.getVolatility();
                double calibTheta = calibratedDescriptor.getTheta();
                double calibKappa = calibratedDescriptor.getKappa();
                double calibXi = calibratedDescriptor.getXi();
                double calibRho = calibratedDescriptor.getRho();

                // --- OUTPUT AND DATA UPDATE ---

                if (Double.isFinite(rmse)) {
                    // Formatted output of results
                    System.out.printf("%-12s | %-10.6f | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-6d | %-8.3f%n",
                            today, rmse, calibVol, calibTheta, calibKappa, calibXi, calibRho, iterations, calculationTime);

                    /*
                     * Daily UPDATE :
                     * We overwrite 'currentParameters' with the values just found.
                     * In the next iteration, the optimizer will start from these values.
                     */
                    currentParameters[0] = calibVol;
                    currentParameters[1] = calibTheta;
                    currentParameters[2] = calibKappa;
                    currentParameters[3] = calibXi;
                    currentParameters[4] = calibRho;

                    // Save to structures for plotting
                    volatilityTimeSeries.add(today, calibVol);
                    thetaTimeSeries.add(today, calibTheta);
                    kappaTimeSeries.add(today, calibKappa);
                    xiTimeSeries.add(today, calibXi);
                    rhoTimeSeries.add(today, calibRho);
                    rmseTimeSeries.add(today, rmse);
                } else {
                    // Robust handling of numerical failures (Infinite RMSE or NaN)
                    System.out.printf("%-12s | FAILED (RMSE: %s) | Iter: %d | Time: %.3fs%n",
                            today, rmse, iterations, calculationTime);

                    // Insert NaN to indicate a data gap (avoids incorrect lines in charts)
                    volatilityTimeSeries.add(today, Double.NaN);
                    thetaTimeSeries.add(today, Double.NaN);
                    kappaTimeSeries.add(today, Double.NaN);
                    xiTimeSeries.add(today, Double.NaN);
                    rhoTimeSeries.add(today, Double.NaN);
                    rmseTimeSeries.add(today, Double.NaN);
                }

            } catch (Exception e) {
                // Generic catch to not interrupt the entire loop if a single day fails
                System.err.println("Critical error during calibration on " + today + ": " + e.getMessage());
            }
        }

        // =======================================================================
        // 4. CHART GENERATION
        // =======================================================================
        System.out.println("Generating charts...");
            if(rmseTimeSeries.size() > 0) {
                // Visualization of charts via JFreeChart (encapsulated in TimeSeries)
                rmseTimeSeries.plot("Calibration Error (RMSE)");
                volatilityTimeSeries.plot("Parameter: Initial Volatility (sigma)");
                thetaTimeSeries.plot("Parameter: Theta (Long Run Variance)");
                kappaTimeSeries.plot("Parameter: Kappa (Mean Reversion)");
                xiTimeSeries.plot("Parameter: Xi (Vol of Vol)");
                rhoTimeSeries.plot("Parameter: Rho (Correlation)");
            } else {
                System.out.println("No valid data collected to generate charts.");
        }
    }
}