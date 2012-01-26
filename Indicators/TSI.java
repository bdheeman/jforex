package jforex.indicators.bdheeman;

import com.dukascopy.api.indicators.*;

public class TSI implements IIndicator {
    private IndicatorInfo indicatorInfo;
    private InputParameterInfo[] inputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;
    private double[][] inputs = new double[1][];
    private int timePeriod = 2;
    private double[][] outputs = new double[1][];

    public void onStart(IIndicatorContext context) {
        indicatorInfo = new IndicatorInfo("TSI", "True Strength Index", "Momentum Indicators",
        		false, false, false, 1, 1, 1);
        inputParameterInfos = new InputParameterInfo[] {new InputParameterInfo("Input data", InputParameterInfo.Type.DOUBLE)};
        optInputParameterInfos = new OptInputParameterInfo[] {new OptInputParameterInfo("Time period", OptInputParameterInfo.Type.OTHER,
                new IntegerRangeDescription(2, 2, 100, 1))};
        outputParameterInfos = new OutputParameterInfo[] {new OutputParameterInfo("TSI", OutputParameterInfo.Type.DOUBLE,
                OutputParameterInfo.DrawingStyle.LINE)};
    }

    public IndicatorResult calculate(int startIndex, int endIndex) {
        //calculating startIndex taking into account lookback value
        if (startIndex - getLookback() < 0) {
            startIndex -= startIndex - getLookback();
        }
        int i, j;
        for (i = startIndex, j = 0; i <= endIndex; i++, j++) {
        	double value = 0;
        	for (int k = timePeriod; k > 0; k--) {
        		value += inputs[0][i - k];
        	}
            outputs[0][j] = value;
        }
        return new IndicatorResult(startIndex, j);
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
        return timePeriod;
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
        inputs[index] = (double[]) array;
    }

    public void setOptInputParameter(int index, Object value) {
        timePeriod = (Integer) value;
    }

    public void setOutputParameter(int index, Object array) {
        outputs[index] = (double[]) array;
    }
}
