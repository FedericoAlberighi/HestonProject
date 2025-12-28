package it.univr.derivatives.utils;

import java.time.LocalDate;

import net.finmath.fouriermethod.calibration.BoundConstraint;
import net.finmath.fouriermethod.calibration.CalibratedModel;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.fouriermethod.calibration.ScalarParameterInformationImplementation;
import net.finmath.fouriermethod.calibration.models.CalibratableHestonModel;
import net.finmath.fouriermethod.products.smile.EuropeanOptionSmileByCarrMadan;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;
import net.finmath.optimizer.OptimizerFactory;
import net.finmath.optimizer.OptimizerFactoryLevenbergMarquardt;
import net.finmath.optimizer.SolverException;

/**
 * Utility class for Heston Model Calibration.
 * <p>
 * This class acts as a wrapper to configure and execute the optimization of stochastic
 * volatility parameters using the <i>finmath-lib</i> library.
 * </p>
 * <p>
 * The process relies on minimizing the Root Mean Squared Error (RMSE) between:
 * <ul>
 * <li>Market prices (or implied volatilities) provided by {@link OptionSurfaceData}.</li>
 * <li>Theoretical prices calculated via the Carr-Madan FFT method.</li>
 * </ul>
 * </p>
 *
 * @author Federico Alberighi
 * @author Alice Bonizzoni
 * @version 1.1
 */
public class HestonCalibrationClass {

    /* ===========================================================================
     * PARAMETER BOUNDS
     * ===========================================================================
     */

    /**
     * Constraint for parameter <b>Sigma</b> (Initial Volatility).
     * <br>Represents the square root of the instantaneous variance at time t=0.
     * <br>Allowed range: [0.01 - 1.0] (1% to 100%)
     */
    private static final ScalarParameterInformationImplementation volatilityInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 1.0));

    /**
     * Constraint for parameter <b>Theta</b> (Long Run Variance).
     * <br>Represents the long-term mean level to which variance reverts.
     * <br>Allowed range: [0.01 - 0.20]
     */
    private static final ScalarParameterInformationImplementation thetaInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 0.2));

    /**
     * Constraint for parameter <b>Kappa</b> (Mean Reversion Speed).
     * <br>Determines the speed at which variance reverts to the Theta level.
     * <br>Allowed range: [0.01 - 5.0].
     * <p>Note: A higher value implies a faster reversion to the mean.</p>
     */
    private static final ScalarParameterInformationImplementation kappaInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 5.0));

    /**
     * Constraint for parameter <b>Xi</b> (Volatility of Volatility).
     * <br>Determines the "volatility" (standard deviation) of the stochastic variance process.
     * <br>Allowed range: [0.01 - 2.0]
     */
    private static final ScalarParameterInformationImplementation xiInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 2.0));

    /**
     * Constraint for parameter <b>Rho</b> (Correlation).
     * <br>Correlation between the asset price Brownian motion and the variance Brownian motion.
     * <br>Allowed range: [-1.0 - 1.0]
     * <p>Note: For equity markets, negative values are generally expected (leverage effect).</p>
     */
    private static final ScalarParameterInformationImplementation rhoInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(-1.0, 1.0));


    /**
     * Factory for creating the optimization algorithm.
     * The Levenberg-Marquardt algorithm is used, which is particularly suitable
     * for non-linear least squares problems.
     */
    private final OptimizerFactory optimizerFactory;

    /**
     * Constructor for the calibration class.
     * <p>
     * Initializes the {@link OptimizerFactoryLevenbergMarquardt} with default parameters:
     * <ul>
     * <li>Max Iterations: 300</li>
     * <li>Number of Threads: 2 (for parallel computation)</li>
     * </ul>
     * </p>
     */
    public HestonCalibrationClass() {
        this.optimizerFactory = new OptimizerFactoryLevenbergMarquardt(300, 2);
    }

    /**
     * Performs Heston model calibration for a specific valuation date.
     * <p>The method executes the following steps:</p>
     * <ol>
     * <li>Extracts discount and forward curves from market data.</li>
     * <li>Constructs a {@link HestonModelDescriptor} using the initial guess parameters.</li>
     * <li>Initializes the {@link CalibratableHestonModel} applying the defined bounds.</li>
     * <li>Configures the FFT Pricer (Carr-Madan) on a standard maturity (e.g., 1 year).</li>
     * <li>Runs the optimization to minimize the distance between model and market prices.</li>
     * </ol>
     *
     * @param date
     * The valuation date for which calibration is performed.
     * @param marketData
     * {@link OptionSurfaceData} object containing the volatility surface and yield curves.
     * @param initialParameters
     * Array of 5 doubles containing the initial guess in the following order:
     * {@code {sigma, theta, kappa, xi, rho}}.
     *
     * @return {@link OptimizationResult}
     * Object containing the calibrated model, the residual error (RMSE), and the iteration count.
     *
     * @throws SolverException
     * If the optimization algorithm fails to converge or encounters numerical errors.
     */
    public OptimizationResult calibrate(LocalDate date, OptionSurfaceData marketData, double[] initialParameters) throws SolverException {

        // 1. Extract Curves and Initial Spot from market data
        DiscountCurve discountCurve = marketData.getDiscountCurve();
        DiscountCurve equityForwardCurve = marketData.getEquityForwardCurve();
        double initialValue = equityForwardCurve.getValue(0.0);

        // 2. Create Model Descriptor (HestonModelDescriptor)
        // This object acts as a "snapshot" of the model parameters at a given time.
        HestonModelDescriptor hestonDescriptor = new HestonModelDescriptor(
                date,
                initialValue,
                discountCurve,
                discountCurve,        // Assumption: drift curve = discount curve (r = d)
                initialParameters[0], // volatility (sigma)
                initialParameters[1], // theta
                initialParameters[2], // kappa
                initialParameters[3], // xi
                initialParameters[4]  // rho
        );

        // 3. Create Calibratable Model
        // Binds the descriptor to the statically defined constraints.
        CalibratableHestonModel model = new CalibratableHestonModel(
                hestonDescriptor,
                volatilityInfo,
                thetaInfo,
                kappaInfo,
                xiInfo,
                rhoInfo,
                false // applyParameterConstraintsToInitialParameter: false allows the guess to slightly violate initial bounds
        );

        // 4. Configure FFT Pricer (Carr-Madan)
        // We use 1-year options from the market smile as the primary calibration target.
        double maturity = 1.0;
        double[] strikes = marketData.getSmile(maturity).getStrikes();
        EuropeanOptionSmileByCarrMadan pricer = new EuropeanOptionSmileByCarrMadan(maturity, strikes);

        // 5. Configure Calibration Problem
        // Defines the "step" for numerical derivative calculations (sensitivity) during optimization.
        double[] parameterStep = new double[] { 0.01, 0.01, 0.01, 0.01, 0.01 };

        CalibratedModel calibrationProblem = new CalibratedModel(
                marketData,       // Target (Market data to fit)
                model,            // Mathematical model
                optimizerFactory, // Optimization engine
                pricer,           // Objective function (Analytic Pricer)
                initialParameters,// Starting point in parameter space
                parameterStep     // Step size for numerical gradients
        );

        // 6. Execute Optimization and return results
        return calibrationProblem.getCalibration();
    }
}