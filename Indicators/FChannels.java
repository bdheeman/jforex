//
// Copyright (c) 2011, sol (c) MoneyFactory.biz
// Copyright (c) 2012, Balwinder S "bdheeman" Dheeman <bdheeman/AT/gmail.com>
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY  or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
// more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc., 675
// Mass Ave, Cambridge, MA 02139, USA.
//
package jforex.indicators.sol;

import com.dukascopy.api.indicators.IIndicator;
import com.dukascopy.api.indicators.IIndicatorContext;
import com.dukascopy.api.indicators.IIndicatorsProvider;
import com.dukascopy.api.indicators.IndicatorInfo;
import com.dukascopy.api.indicators.IndicatorResult;
import com.dukascopy.api.indicators.InputParameterInfo;
import com.dukascopy.api.indicators.IntegerListDescription;
import com.dukascopy.api.indicators.IntegerRangeDescription;
import com.dukascopy.api.indicators.OptInputParameterInfo;
import com.dukascopy.api.indicators.OutputParameterInfo;

import java.awt.Color;

public class FChannels implements IIndicator {
    private IndicatorInfo indicatorInfo;
    private InputParameterInfo[] inputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;

    public static final Color DARK_GREEN = new Color(0x00, 0x66, 0x00);
    public static final Color DARK_RED = new Color(0xC0, 0x00, 0x00);

    private IIndicator FractalIndicator;
    private double[][][] inputs = new double[1][][];
    private double[][] outputs = new double[2][];
    private int bars = 2;
    private double maximums = Double.NaN;
    private double minimums = Double.NaN;

    public void onStart(IIndicatorContext context) {
        FractalIndicator = context.getIndicatorsProvider().getIndicator("FRACTAL");

        indicatorInfo = new IndicatorInfo("FCHANNELS", "Fractal Channels", "Overlap Studies", true, false, true, 1, 1, 2);

        inputParameterInfos = new InputParameterInfo[] {
            new InputParameterInfo("Price", InputParameterInfo.Type.PRICE)
        };

        optInputParameterInfos = new OptInputParameterInfo[] {
            new OptInputParameterInfo("Number of bars on sides", OptInputParameterInfo.Type.OTHER, new IntegerRangeDescription(bars, 2, 50, 1))
        };

        outputParameterInfos = new OutputParameterInfo[] {
        new OutputParameterInfo("Maximums", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE) {{
                setColor(DARK_GREEN);
            }
        },
        new OutputParameterInfo("Minimums", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE) {{
                setColor(DARK_RED);
            }
        }
        };
    }

    public IndicatorResult calculate(int startIndex, int endIndex) {
        // calculate startIndex taking into account lookback value
        if (startIndex - getLookback() < 0) {
            startIndex -= startIndex - getLookback();
        }

        int i, j;
        double[][] fractal_res = new double[2][endIndex - startIndex + 2 + FractalIndicator.getLookback()];

        // multiPrice - copy the section of the array
        FractalIndicator.setInputParameter(0, inputs[0]);
        FractalIndicator.setOutputParameter(0, fractal_res[0]);
        FractalIndicator.setOutputParameter(1, fractal_res[1]);
        FractalIndicator.setOptInputParameter(0, bars);

        IndicatorResult FractalIndicatorRes = FractalIndicator.calculate(startIndex - 1, endIndex);

        for (i = startIndex, j = 0; i <= endIndex; i++, j++) {
            if(fractal_res[0][j] >= inputs[0][2][i])
                maximums = fractal_res[0][j];
            if(fractal_res[1][j] <= inputs[0][3][i] && !Double.isNaN(fractal_res[1][j]) && fractal_res[1][j] > 0)
                minimums = fractal_res[1][j];

            outputs[0][j] = maximums;
            outputs[1][j] = minimums;
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
        return bars * 2;
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
        inputs[index] = (double[][]) array;
    }

    public void setOptInputParameter(int index, Object value) {
        if(index == 0) bars = (Integer) value;
    }

    public void setOutputParameter(int index, Object array) {
        outputs[index] = (double[]) array;
    }
}
