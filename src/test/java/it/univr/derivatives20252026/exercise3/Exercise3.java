package it.univr.derivatives20252026.exercise3;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeMap;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.HestonCalibrationClass;
import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.functions.HestonModel; // Classe del Prof per le Greche (Delta)
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;
import net.finmath.optimizer.SolverException;
import net.finmath.time.daycount.DayCountConvention;
import net.finmath.time.daycount.DayCountConvention_ACT_365;

/**
 * Esercizio 3: Delta Hedging Storico nel modello di Heston.
 * Implementazione completa: Calibrazione -> Vendita Call -> Hedging Dinamico -> PnL.
 */
public class Exercise3 {

    public static void main(String[] args) {

    }
}