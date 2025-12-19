package it.univr.derivatives.utils;

import java.time.LocalDate;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

import net.finmath.plots.Plot2D;
import net.finmath.plots.PlotableFunction2D;

public class TimeSeries {

    private final NavigableMap<LocalDate, Double> data = new TreeMap<>();

    /* ---------- Constructors ---------- */

    public TimeSeries() { }

    public TimeSeries(Map<LocalDate, Double> initialData) {
        data.putAll(initialData);
    }

    /* ---------- Basic Operations ---------- */

    public void add(LocalDate date, double value) {
        data.put(date, value);
    }

    public Double get(LocalDate date) {
        return data.get(date);
    }

    public NavigableMap<LocalDate, Double> getRange(LocalDate start, LocalDate end) {
        return data.subMap(start, true, end, true);
    }

    public LocalDate getFirstDate() {
        return data.firstKey();
    }

    public LocalDate getLastDate() {
        return data.lastKey();
    }

    public int size() {
        return data.size();
    }

    public List<Double> getValues() {
        return new ArrayList<>(data.values());
    }

    /* ---------- Descriptive Statistics ---------- */

    public double getMean() {
        return data.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public double getVariance() {
        double mean = getMean();
        return data.values().stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(Double.NaN);
    }

    public double getStandardDeviation() {
        return Math.sqrt(getVariance());
    }

    public double getMin() {
        return data.values().stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    public double getMax() {
        return data.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    public double getMedian() {
        List<Double> sorted = data.values().stream().sorted().collect(Collectors.toList());
        int n = sorted.size();
        if (n == 0) return Double.NaN;
        if (n % 2 == 1) return sorted.get(n / 2);
        else return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
    
    
    /**
     * Computes the log returns of this time series.
     * Each return is ln(P_t / P_{t-1}), where P_t are consecutive values.
     *
     * @return A new TimeSeries containing log returns (dates aligned with the later date in each pair).
     */
    public TimeSeries computeLogReturns() {
        TimeSeries logReturns = new TimeSeries();

        if (data.size() < 2) {
            System.out.println("Not enough data points to compute log returns.");
            return logReturns;
        }

        LocalDate previousDate = null;
        Double previousValue = null;

        for (Map.Entry<LocalDate, Double> entry : data.entrySet()) {
            if (previousDate != null && previousValue != null && previousValue > 0.0) {
                double currentValue = entry.getValue();
                if (currentValue > 0.0) {
                    double logReturn = Math.log(currentValue / previousValue);
                    logReturns.add(entry.getKey(), logReturn);
                } else {
                    logReturns.add(entry.getKey(), Double.NaN);
                }
            }
            previousDate = entry.getKey();
            previousValue = entry.getValue();
        }

        return logReturns;
    }
    
    /**
     * Computes the simple (arithmetic) returns of this time series.
     * Each return is (P_t / P_{t-1}) - 1, where P_t are consecutive values.
     *
     * @return A new TimeSeries containing simple returns (dates aligned with the later date in each pair).
     */
    public TimeSeries computeSimpleReturns() {
        TimeSeries simpleReturns = new TimeSeries();

        if (data.size() < 2) {
            System.out.println("Not enough data points to compute simple returns.");
            return simpleReturns;
        }

        LocalDate previousDate = null;
        Double previousValue = null;

        for (Map.Entry<LocalDate, Double> entry : data.entrySet()) {
            if (previousDate != null && previousValue != null && previousValue != 0.0) {
                double currentValue = entry.getValue();
                double simpleReturn = (currentValue / previousValue) - 1.0;
                simpleReturns.add(entry.getKey(), simpleReturn);
            }
            previousDate = entry.getKey();
            previousValue = entry.getValue();
        }

        return simpleReturns;
    }



    /* ---------- Plotting ---------- */

    /**
     * Creates a Finmath Plot2D of the time series.
     * The x-axis shows dates (converted to index numbers or epoch days).
     * 
     * This is a bit rudimentary, I 
     * @param title Title of the plot
     * @throws Exception if plot creation fails
     */
    public void plot(String title) throws Exception {
        if (data.isEmpty()) {
            System.out.println("TimeSeries is empty — nothing to plot.");
            return;
        }

        List<LocalDate> dates = new ArrayList<>(data.keySet());
        List<Double> values = new ArrayList<>(data.values());

        // Convert LocalDates to numeric X values (epoch days)
        double[] xValues = new double[dates.size()];
        double[] yValues = new double[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            xValues[i] = dates.get(i).toEpochDay();
            yValues[i] = values.get(i);
        }

        double xMin = xValues[0];
        double xMax = xValues[xValues.length - 1];

        // Define a function that interpolates between known points
        DoubleUnaryOperator interpolatedFunction = x -> {
            // Linear interpolation
            for (int i = 0; i < xValues.length - 1; i++) {
                if (x >= xValues[i] && x <= xValues[i + 1]) {
                    double t = (x - xValues[i]) / (xValues[i + 1] - xValues[i]);
                    return yValues[i] + t * (yValues[i + 1] - yValues[i]);
                }
            }
            // Out of bounds: return NaN
            return Double.NaN;
        };

        // Use the modern PlotableFunction2D constructor
        Plot2D plot = new Plot2D(Arrays.asList(
                new PlotableFunction2D(xMin, xMax, size(), interpolatedFunction))
        );

        plot.setTitle(title);
        plot.setXAxisLabel("Date (epoch days)");
        plot.setYAxisLabel("Value");
        plot.show();
    }


    /* ---------- Utility ---------- */

    public void printSummary() {
        System.out.println("TimeSeries Summary");
        System.out.println("-------------------");
        System.out.printf("Count: %d%n", size());
        System.out.printf("From: %s  To: %s%n", getFirstDate(), getLastDate());
        System.out.printf("Mean: %.4f%n", getMean());
        System.out.printf("Std Dev: %.4f%n", getStandardDeviation());
        System.out.printf("Min: %.4f%n", getMin());
        System.out.printf("Max: %.4f%n", getMax());
        System.out.printf("Median: %.4f%n", getMedian());
        System.out.println();
    }

    public void printData() {
        data.forEach((d, v) -> System.out.println(d + " → " + v));
    }

    /* ---------- Example ---------- */

    public static void main(String[] args) throws Exception {
        TimeSeries ts = new TimeSeries();
        ts.add(LocalDate.of(2024, 1, 1), 100.0);
        ts.add(LocalDate.of(2024, 1, 2), 101.5);
        ts.add(LocalDate.of(2024, 1, 3), 99.8);
        ts.add(LocalDate.of(2024, 1, 4), 102.3);
        ts.add(LocalDate.of(2024, 1, 5), 101.0);

        ts.printSummary();
        ts.plot("Example Time Series (Finmath Plot)");
    }
}
