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
 * <p><strong>Classe di utilità per la Calibrazione del Modello di Heston.</strong></p>
 * * <p>Questa classe incapsula la complessità della configurazione di Finmath per l'ottimizzazione
 * dei parametri del modello stocastico di volatilità.</p>
 * * <p>Il processo di calibrazione cerca di minimizzare la differenza tra i prezzi delle opzioni
 * osservati sul mercato (Superficie di Volatilità) e quelli teorici generati dal modello
 * tramite il metodo FFT di Carr-Madan.</p>
 *
 * @author Federico Alberighi
 * @author Alice Bonizzoni
 * @version 1.0
 */
public class HestonCalibrationClass {

    /* * ===========================================================================
     * VINCOLI SUI PARAMETRI (PARAMETER BOUNDS)
     * ===========================================================================
     */

    private static final ScalarParameterInformationImplementation volatilityInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 1.0));

    private static final ScalarParameterInformationImplementation thetaInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 0.2));

    /**
     * Vincolo per Kappa (Mean Reversion Speed).
     * Velocità con cui la varianza torna verso Theta.
     * Range: [0.01 - 5.0].
     * Nota: Il limite superiore è stato esteso a 5.0 rispetto allo standard 1.0
     */
    private static final ScalarParameterInformationImplementation kappaInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 5.0));

    private static final ScalarParameterInformationImplementation xiInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(0.01, 2.0));

    private static final ScalarParameterInformationImplementation rhoInfo =
            new ScalarParameterInformationImplementation(true, new BoundConstraint(-1.0, 1.0));


    // Factory per l'algoritmo di ottimizzazione (Levenberg-Marquardt)
    private final OptimizerFactory optimizerFactory;

    /**
     * Costruttore: Inizializza il motore di ottimizzazione.
     * Utilizza l'algoritmo di Levenberg-Marquardt
     */
    public HestonCalibrationClass() {
        // Configuriamo l'ottimizzatore con 300 iterazioni massime e 4 thread paralleli
        this.optimizerFactory = new OptimizerFactoryLevenbergMarquardt(300, 4);
    }

    /**
     * Esegue la procedura di calibrazione per una specifica data di mercato.
     * * @param date La data di riferimento per la calibrazione (Valuation Date).
     * @param marketData Il contenitore dei dati di mercato (Discount Curve, Forward Curve, Superficie Volatilità).
     * @param initialParameters Array di 5 double contenente il "guess" iniziale {vol, theta, kappa, xi, rho}.
     * * @return OptimizationResult Un oggetto contenente:
     * <ul>
     * <li>Il modello calibrato (con i parametri ottimali).</li>
     * <li>L'errore finale (RMSE).</li>
     * <li>Il numero di iterazioni impiegate.</li>
     * </ul>
     * @throws SolverException Se l'algoritmo di ottimizzazione fallisce.
     */
    public OptimizationResult calibrate(LocalDate date, OptionSurfaceData marketData, double[] initialParameters) throws SolverException {

        // 1. Estrazione Curve e Spot iniziale dai dati di mercato
        DiscountCurve discountCurve = marketData.getDiscountCurve();
        DiscountCurve equityForwardCurve = marketData.getEquityForwardCurve();
        double initialValue = equityForwardCurve.getValue(0.0);

        // 2. Creazione del Descrittore del Modello (HestonModelDescriptor)
        HestonModelDescriptor hestonDescriptor = new HestonModelDescriptor(
                date,
                initialValue,
                discountCurve,
                discountCurve,       // Usiamo la stessa curva per discount e drift (ipotesi r=d)
                initialParameters[0], // volatility (sigma)
                initialParameters[1], // theta
                initialParameters[2], // kappa
                initialParameters[3], // xi
                initialParameters[4]  // rho
        );

        // 3. Creazione del Modello Calibrabile (CalibratableHestonModel)
        CalibratableHestonModel model = new CalibratableHestonModel(
                hestonDescriptor,
                volatilityInfo,
                thetaInfo,
                kappaInfo,
                xiInfo,
                rhoInfo,
                false // applyParameterConstraintsToInitialParameter (false = permette guess leggermente fuori bound se necessario)
        );

        // 4. Configurazione del Pricer FFT (Carr-Madan)
        double maturity = 1.0;
        double[] strikes = marketData.getSmile(maturity).getStrikes();
        EuropeanOptionSmileByCarrMadan pricer = new EuropeanOptionSmileByCarrMadan(maturity, strikes);

        // 5. Configurazione del Problema di Calibrazione (CalibratedModel)
        double[] parameterStep = new double[] { 0.01, 0.01, 0.01, 0.01, 0.01 }; // Step per le derivate numeriche

        CalibratedModel calibrationProblem = new CalibratedModel(
                marketData,       // Target (Prezzi/Volatilità di mercato)
                model,            // Modello da calibrare
                optimizerFactory, // Algoritmo (Levenberg-Marquardt)
                pricer,           // Funzione di pricing
                initialParameters,// Punto di partenza
                parameterStep     // Sensibilità parametri
        );

        // 6. Esecuzione dell'Ottimizzazione
        return calibrationProblem.getCalibration();
    }
}