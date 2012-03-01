package jforex.indicators;

import java.awt.Color;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import com.dukascopy.api.IBar;
import com.dukascopy.api.IIndicators;
import com.dukascopy.api.IIndicators.AppliedPrice;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.indicators.IIndicator;
import com.dukascopy.api.indicators.IIndicatorContext;
import com.dukascopy.api.indicators.IndicatorInfo;
import com.dukascopy.api.indicators.IndicatorResult;
import com.dukascopy.api.indicators.InputParameterInfo;
import com.dukascopy.api.indicators.IntegerListDescription;
import com.dukascopy.api.indicators.IntegerRangeDescription;
import com.dukascopy.api.indicators.OptInputParameterInfo;
import com.dukascopy.api.indicators.OutputParameterInfo;

public class CMA implements IIndicator {
    private IIndicator maInd;
    private IIndicator stdDevInd;
    private IndicatorInfo indicatorInfo;
    private InputParameterInfo[] inputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;
    private double[][] inPrices;
    private double[][] output;
    private int appliedPrice = CLOSE;
    private int maType = IIndicators.MaType.SMA.ordinal();
    private int period = 35;

    public static final int OPEN = 0;
    public static final int CLOSE = 1;
    public static final int MAX = 2;
    public static final int MIN = 3;

    public void onStart(IIndicatorContext context) {
        maInd = context.getIndicatorsProvider().getIndicator("MA");
        stdDevInd = context.getIndicatorsProvider().getIndicator("STDDEV");

        int[] priceValues = {OPEN, MAX, MIN, CLOSE};
        String[] priceNames = {"Open", "High", "Low", "Close"};

        int[] maTypeValues = new int[IIndicators.MaType.values().length];
        String[] maTypeNames = new String[IIndicators.MaType.values().length];
        for (int i = 0; i < maTypeValues.length; i++) {
            maTypeValues[i] = i;
            maTypeNames[i] = IIndicators.MaType.values()[i].name();
        }

        inputParameterInfos = new InputParameterInfo[] {
        new InputParameterInfo("low", InputParameterInfo.Type.PRICE) {},
        };

        optInputParameterInfos = new OptInputParameterInfo[] {
            new OptInputParameterInfo("Applied price",
                                      OptInputParameterInfo.Type.OTHER,
                                      new IntegerListDescription(appliedPrice, priceValues, priceNames)),
            new OptInputParameterInfo("MA Type",
                                      OptInputParameterInfo.Type.OTHER,
                                      new IntegerListDescription(maType, maTypeValues, maTypeNames)),
            new OptInputParameterInfo("Period",
                                      OptInputParameterInfo.Type.OTHER,
                                      new IntegerRangeDescription(period, 2, 100, 1)),
        };

        outputParameterInfos = new OutputParameterInfo[] {
            new OutputParameterInfo("MA",
                                    OutputParameterInfo.Type.DOUBLE,
        OutputParameterInfo.DrawingStyle.LINE) {
            {
                this.setColor(Color.BLUE);
            }
        },
        };

        output = new double[outputParameterInfos.length][];
        //inPrice = new double[inputParameterInfos.length][];

        indicatorInfo = new IndicatorInfo("CMA", "Corrective MA", "Overlap Studies", true, false, false,
                                          inputParameterInfos.length, optInputParameterInfos.length, outputParameterInfos.length);
        indicatorInfo.setRecalculateAll(true);
    }

    public IndicatorResult calculate(int startIndex, int endIndex) {
        if (startIndex - getLookback() < 0) {
            startIndex -= startIndex - getLookback();
        }
        if (startIndex > endIndex) {
            return new IndicatorResult(0, 0);
        }
        int len = endIndex - startIndex + 1;
        int maOutLen = len + 1;

        double[] ma = new double[maOutLen];
        double[] stdDevOut = new double[maOutLen];

        // sma
        maInd.setInputParameter(0, inPrices[appliedPrice]);
        maInd.setOutputParameter(0, ma);
        maInd.calculate(startIndex - 1, endIndex);

        stdDevInd.setInputParameter(0, inPrices[appliedPrice]);
        stdDevInd.setOptInputParameter(0, period);
        stdDevInd.setOptInputParameter(1, 1.0);
        stdDevInd.setOutputParameter(0, stdDevOut);
        stdDevInd.calculate(startIndex - 1, endIndex);

        double prev = ma[0];
        for (int i = 0; i < len; i++) {
            int maIdx = i + 1;

            double v1 = stdDevOut[maIdx] * stdDevOut[maIdx];
            double t = prev - ma[maIdx];
            double v2 = t * t;
            double k;
            if (v2 < v1 || v2 == 0) {
                k = 0;
            } else {
                k = 1 - v1/v2;
            }
            output[0][i] = prev + k * (ma[maIdx] - prev);
            prev = output[0][i];
        }

        return new IndicatorResult(startIndex, len);
    }

    public IndicatorInfo getIndicatorInfo() {
        return indicatorInfo;
    }

    public InputParameterInfo getInputParameterInfo(int index) {
        if (index <= inputParameterInfos.length) {
            return inputParameterInfos[index];
        }
        return null;
    }

    public int getLookback() {
        maInd.setOptInputParameter(0, period);
        maInd.setOptInputParameter(1, maType);
        return maInd.getLookback() + 1;
    }

    public int getLookforward() {
        return 0;
    }

    public OptInputParameterInfo getOptInputParameterInfo(int index) {
        if (index <= optInputParameterInfos.length) {
            return optInputParameterInfos[index];
        }
        return null;
    }

    public OutputParameterInfo getOutputParameterInfo(int index) {
        if (index <= outputParameterInfos.length) {
            return outputParameterInfos[index];
        }
        return null;
    }

    public void setInputParameter(int index, Object array) {
        if (array instanceof double[][]) {
            inPrices = (double[][]) array;
        }
    }

    public void setOptInputParameter(int index, Object value) {
        switch (index) {
            case 0:
                appliedPrice = (Integer) value;
                break;
            case 1:
                maType = (Integer) value;
                break;
            case 2:
                period = (Integer) value;
                break;
        }
    }

    public void setOutputParameter(int index, Object array) {
        output[index] = (double[]) array;
    }
}
