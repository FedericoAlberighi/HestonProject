package it.univr.derivatives.marketdataprovider;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import it.univr.derivatives.utils.TimeSeries;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveInterpolation;
import net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod;
import net.finmath.marketdata.model.volatilities.OptionSmileData;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention;

public class MarketDataProvider {
	
	
	public static TreeMap<LocalDate, OptionSurfaceData> getVolatilityDataContainer(){
		
		String filePath = "src/test/resources/DAX Bloomi.xls"; // path to your file
		
		TreeMap<LocalDate, OptionSurfaceData> marketDataDatabase = new TreeMap<>();

		try (FileInputStream fis = new FileInputStream(new File(filePath)); Workbook workbook = new HSSFWorkbook(fis)) { // For
																															// .xls
																															// format

			Sheet sheet = workbook.getSheet("DAX"); // get sheet by name

			/*
			 * Inizialize a database of volatility surfaces
			 */
			
			

			for (int i = 3; i <= sheet.getLastRowNum(); i++) {

				Row row = sheet.getRow(i);
				
				//110%	105%	102,50%	100%	97,50%	95%	90%
				double[] strikeScaling = {1.1, 1.05, 1.025, 1.0, 0.975, 0.95, 0.90};
				
				/*
				 * The maturities
				 */
				double[] maturities = {0.083333333,	0.166666667,0.25,0.5,1.0,1.5,2.0};
			

				/*
				 * The local date
				 */
				Cell myCell = row.getCell(1); //B
				LocalDate referenceDate = myCell.getLocalDateTimeCellValue().toLocalDate();

				/*
				 * The value of the Dax Index
				 */
				myCell = row.getCell(2); //C
				double valueOfUnderlying = myCell.getNumericCellValue();
				
				
				/*
				 * Arrays with the implied Volatilities for each maturity
				 */
				double[][] iVols = new double[maturities.length][strikeScaling.length];
				
				int startingIndex = 3;
				for(int j = 0; j < maturities.length; j++) {
					double[] values  = extractData(row, startingIndex + j*7);
					iVols[j] = java.util.Arrays.stream(values)
						    .map(v -> v / 100.0)
						    .toArray();
				}
				
				/*
				 * The risk-free curve
				 */
				double[] percentageZeroRates = extractData(row, 52);
				double[] zeroRates = java.util.Arrays.stream(percentageZeroRates)
						    .map(v -> v / 100.0)
						    .toArray();
				
					
				
				final ExtrapolationMethod exMethod = ExtrapolationMethod.CONSTANT;
				final InterpolationMethod intMethod = InterpolationMethod.LINEAR;
				final InterpolationEntity intEntity = InterpolationEntity.LOG_OF_VALUE;

				
				DiscountCurve equityForwardCurve = DiscountCurveInterpolation.createDiscountCurveFromDiscountFactors(
						"daxForwardCurve"								/* name */,
						new double[] {0.0, 0.083333333,	0.166666667,0.25,0.5,1.0,1.5,2.0}	/* maturities */,
						new double[] {valueOfUnderlying, valueOfUnderlying*Math.exp(zeroRates[0]*maturities[0]),
								valueOfUnderlying*Math.exp(zeroRates[1]*maturities[1]),
								valueOfUnderlying*Math.exp(zeroRates[2]*maturities[2]),
								valueOfUnderlying*Math.exp(zeroRates[3]*maturities[3]),
								valueOfUnderlying*Math.exp(zeroRates[4]*maturities[4]),
								valueOfUnderlying*Math.exp(zeroRates[5]*maturities[5]),
								valueOfUnderlying*Math.exp(zeroRates[6]*maturities[6])
								}	/* discount factors */,
						intMethod, exMethod,intEntity);

				
				//I need to put 0.0 as the first maturity
				double[] newMaturities = new double[maturities.length + 1];
			    newMaturities[0] = 0.0; // first element
			    System.arraycopy(maturities, 0, newMaturities, 1, maturities.length);
			    
			    //I just put a zero for the first yield, one Eur today is worth one Eur
			    double[] newZeroRates = new double[zeroRates.length +1];
			    newZeroRates[0] = 0.0;
			    System.arraycopy(zeroRates, 0, newZeroRates, 1, zeroRates.length);
			    
			    
				
				DiscountCurve myDiscountCurve = DiscountCurveInterpolation.createDiscountCurveFromZeroRates(
						"myDiscountCurve"								/* name */,
						referenceDate,
						newMaturities	/* maturities */,
						newZeroRates,
						intMethod, exMethod,intEntity);
				
				double[] multipliedStrikes = new double[strikeScaling.length];
				
				for(int j = 0; j< strikeScaling.length; j++)
					multipliedStrikes[j] = strikeScaling[j] * valueOfUnderlying;
					
				QuotingConvention convention = QuotingConvention.VOLATILITYLOGNORMAL;
						
				OptionSmileData[] smileContainers = new OptionSmileData[maturities.length];
				
				for(int j = 0; j< maturities.length; j++) {
					smileContainers[j] = new OptionSmileData("DAX", referenceDate, multipliedStrikes, maturities[j], iVols[j], convention);
				}
				
				OptionSurfaceData surface = new OptionSurfaceData(smileContainers, myDiscountCurve, equityForwardCurve);
			
				marketDataDatabase.put(referenceDate, surface);

			}
			
			
			LocalDate someDate = LocalDate.of(2006, 1, 2);
			OptionSurfaceData surface = marketDataDatabase.get(someDate);
			
	
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return marketDataDatabase;
	}
	
	
	/**
	 * Extracts a 7-column smile from a given row starting at the specified column
	 * index.
	 *
	 * @param row      The Excel row to read from.
	 * @param startCol The starting column index (0-based) of the smile.
	 * @return An array of 7 double values containing the smile.
	 */
	public static double[] extractData(Row row, int startCol) {
		double[] smile = new double[7]; // Always 7 columns for a smile
		if (row == null) {
			// Fill with NaN if row is null
			for (int i = 0; i < 7; i++)
				smile[i] = Double.NaN;
			return smile;
		}

		for (int j = 0; j < 7; j++) {
			Cell cell = row.getCell(startCol + j);
			if (cell != null && cell.getCellType() == CellType.NUMERIC) {
				smile[j] = cell.getNumericCellValue();
			} else {
				smile[j] = Double.NaN; // or 0 depending on preference
			}
		}

		return smile;
	}
	
public static TimeSeries getDaxData(){
		
		String filePath = "src/test/resources/DAX Bloomi.xls"; // path to your file
		
		
		TimeSeries daxTimeSeries = new TimeSeries();
		
		try (FileInputStream fis = new FileInputStream(new File(filePath)); Workbook workbook = new HSSFWorkbook(fis)) { // For
																															// .xls
																															// format

			Sheet sheet = workbook.getSheet("DAX"); // get sheet by name


			for (int i = 3; i <= sheet.getLastRowNum(); i++) {

				Row row = sheet.getRow(i);
				
				/*
				 * The local date
				 */
				Cell myCell = row.getCell(1); //B
				LocalDate referenceDate = myCell.getLocalDateTimeCellValue().toLocalDate();

				/*
				 * The value of the Dax Index
				 */
				myCell = row.getCell(2); //C
				double valueOfUnderlying = myCell.getNumericCellValue();
				
				daxTimeSeries.add(referenceDate, valueOfUnderlying);
				

			}
			
					
	
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return daxTimeSeries;
	}
	


}
