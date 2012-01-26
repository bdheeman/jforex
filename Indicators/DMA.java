//
// Copyright (c) 2011, Indiana "chriz" Pips <puntasabbioni/AT/gmail.com>
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
package jforex.indicators.chriz;

import com.dukascopy.api.IIndicators;
import com.dukascopy.api.indicators.IDrawingIndicator;
import com.dukascopy.api.indicators.IIndicator;
import com.dukascopy.api.indicators.IIndicatorContext;
import com.dukascopy.api.indicators.IIndicatorDrawingSupport;
import com.dukascopy.api.indicators.IIndicatorsProvider;
import com.dukascopy.api.indicators.IndicatorInfo;
import com.dukascopy.api.indicators.IndicatorResult;
import com.dukascopy.api.indicators.InputParameterInfo;
import com.dukascopy.api.indicators.IntegerListDescription;
import com.dukascopy.api.indicators.IntegerRangeDescription;
import com.dukascopy.api.indicators.OptInputParameterInfo;
import com.dukascopy.api.indicators.OutputParameterInfo;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Map;

public class DMA implements IIndicator, IDrawingIndicator {
    private IIndicatorContext context;
    private IndicatorInfo indicatorInfo;

    private int fastTimePeriod = 5;
    private int slowTimePeriod = 11;

    public static final Color DARK_GREEN = new Color(0x00, 0x66, 0x00);
    public static final Color DARK_RED = new Color(0xC0, 0x00, 0x00);
    public static final Color LIGHT_GREEN = new Color(0x80, 0xEE, 0x80);
    public static final Color LIGHT_RED  = new Color(0xFF, 0x80, 0x80);
    public static final Color VERY_LIGHT_GREEN = new Color(0x00, 0xC8, 0x00, 0x4E);
    public static final Color VERY_LIGHT_RED = new Color(0xC8, 0x00, 0x00, 0x4E);
    public static final int FASTMA = 0;
    public static final int SLOWMA = 1;

    private InputParameterInfo[] inputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;

    private IIndicator fastMaIndi;
    private IIndicator slowMaIndi;
    private InputParameterInfo inputParam1;
    private InputParameterInfo inputParam2;
    private double[][] inputs = new double[2][];
    private double[][] outputs = new double[4][];
    private Object[][] output = new Object[1][];

    public void onStart(IIndicatorContext context) {
        this.context = context;

        IIndicatorsProvider indicatorsProvider = context.getIndicatorsProvider();
        fastMaIndi = indicatorsProvider.getIndicator("MA");
        slowMaIndi = indicatorsProvider.getIndicator("MA");

        indicatorInfo = new IndicatorInfo("DMA", "Double MA (Ribbon Filled)", "Overlap Studies", true, false, true, 2, 4, 5);
        inputParam1 = new InputParameterInfo("Fast MA Applied Price", InputParameterInfo.Type.DOUBLE);
        inputParam2 = new InputParameterInfo("Slow MA Applied Price", InputParameterInfo.Type.DOUBLE);

        inputParam1.setAppliedPrice(IIndicators.AppliedPrice.CLOSE);
        inputParam2.setAppliedPrice(IIndicators.AppliedPrice.CLOSE);
        inputParameterInfos = new InputParameterInfo[] { inputParam1, inputParam2 };

        int[] maValues = new int[IIndicators.MaType.values().length];
        String[] maNames = new String[IIndicators.MaType.values().length];

        for (int i = 0; i < maValues.length; i++) {
            maValues[i] = i;
            maNames[i] = IIndicators.MaType.values()[i].name();
        }

        optInputParameterInfos = new OptInputParameterInfo[] {
            new OptInputParameterInfo("Fast MA Time Period", OptInputParameterInfo.Type.OTHER, new IntegerRangeDescription(fastTimePeriod, 2, 500, 1)),
            new OptInputParameterInfo("Fast MA Type", OptInputParameterInfo.Type.OTHER, new IntegerListDescription(IIndicators.MaType.T3.ordinal(), maValues, maNames)),
            new OptInputParameterInfo("Slow MA Time Period", OptInputParameterInfo.Type.OTHER, new IntegerRangeDescription(slowTimePeriod, 2, 500, 1)),
            new OptInputParameterInfo("Slow MA Type", OptInputParameterInfo.Type.OTHER, new IntegerListDescription(IIndicators.MaType.T3.ordinal(), maValues, maNames))
        };

        outputParameterInfos = new OutputParameterInfo[] {
        new OutputParameterInfo("Fast MA", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE) {{
                setColor(LIGHT_GREEN);
            }
        },
        new OutputParameterInfo("Slow MA", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE) {{
                setColor(LIGHT_RED);
            }
        },
        new OutputParameterInfo("Up Arrow", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.ARROW_SYMBOL_UP) {{
                setColor(DARK_GREEN);
            }
        },
        new OutputParameterInfo("Down Arrow", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.ARROW_SYMBOL_DOWN) {{
                setColor(DARK_RED);
            }
        },
        new OutputParameterInfo("Ribbon", OutputParameterInfo.Type.OBJECT, OutputParameterInfo.DrawingStyle.LINE) {{
                setDrawnByIndicator(true);
            }
        }
        };
    }

    public IndicatorResult calculate(int startIndex, int endIndex) {
        // calculate startIndex taking into account lookback value
        if (startIndex - getLookback() < 0) {
            startIndex -= startIndex - getLookback();
        }

        if (startIndex > endIndex) {
            return new IndicatorResult(0, 0);
        }

        double[] fastMAOut = new double[endIndex - startIndex + 1 + getLookback()];
        double[] slowMAOut = new double[endIndex - startIndex + 1 + getLookback()];

        // calculations on selected applied price
        fastMaIndi.setInputParameter(0, inputs[0]);
        slowMaIndi.setInputParameter(0, inputs[1]);

        fastMaIndi.setOutputParameter(0, fastMAOut);
        slowMaIndi.setOutputParameter(0, slowMAOut);

        IndicatorResult fastMaIndiResult = fastMaIndi.calculate(startIndex , endIndex);
        IndicatorResult slowMaIndiResult = slowMaIndi.calculate(startIndex , endIndex);

        int i, k;
        for (i = 0, k = slowMaIndiResult.getNumberOfElements(); i < k; i++) {
            outputs[0][i] = fastMAOut[i];
            outputs[1][i] = slowMAOut[i];
            outputs[2][i] = Double.NaN ;
            outputs[3][i] = Double.NaN ;
            if(i>0 && fastMAOut[i]>slowMAOut[i] && fastMAOut[i-1]<slowMAOut[i-1]) {
                // UP SIGNAL
                outputs[2][i] = slowMAOut[i];
            }
            if(i>0 && fastMAOut[i]<slowMAOut[i] && fastMAOut[i-1]>slowMAOut[i-1]) {
                // DN SIGNAL
                outputs[3][i] = slowMAOut[i];
            }

            double[] ribbon = new double[2];
            ribbon[FASTMA] = fastMAOut[i];
            ribbon[SLOWMA] = slowMAOut[i];
            // for drawing
            output[0][i] = ribbon;
        }

        return new IndicatorResult(startIndex, i );
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
        int fastMaLookBack = fastMaIndi.getLookback();
        int slowMaLookBack = slowMaIndi.getLookback();
        return Math.max( fastMaLookBack, slowMaLookBack) ;
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

    public OptInputParameterInfo getOptInputParameterInfo(int index) {
        if (index <= optInputParameterInfos.length) {
            return optInputParameterInfos[index];
        }
        return null;
    }

    public void setOptInputParameter(int index, Object value) {
        switch (index) {
        case 0:
            fastTimePeriod = (Integer) value;
            fastMaIndi.setOptInputParameter(0, fastTimePeriod);
            break;
        case 1:
            int maFastType = (Integer) value;
            fastMaIndi.setOptInputParameter(1,IIndicators.MaType.values()[maFastType].ordinal());
            break;
        case 2:
            slowTimePeriod = (Integer) value;
            slowMaIndi.setOptInputParameter(0, slowTimePeriod);
            break;
        case 3:
            int maSlowType = (Integer) value;
            slowMaIndi.setOptInputParameter(1,IIndicators.MaType.values()[maSlowType].ordinal());
            break;
        default:
            throw new ArrayIndexOutOfBoundsException(index);
        }
    }

    public void setOutputParameter(int index, Object array) {
        if(index<4) {
            outputs[index] = (double[]) array;
        } else {
            output[0] = (Object[]) array;
        }
    }

    public int getLookforward() {
        return 0;
    }

    public Point drawOutput(Graphics g, int outputIdx, Object values2, Color color, Stroke stroke,
                            IIndicatorDrawingSupport indicatorDrawingSupport, java.util.List<Shape> shapes,
                            Map<Color, java.util.List<Point>> handles) {
        Object[] values = (Object[]) values2;

        if (values2 != null) {
            for (int j = indicatorDrawingSupport.getIndexOfFirstCandleOnScreen(), k =
                        j + indicatorDrawingSupport.getNumberOfCandlesOnScreen() ; j < k; j++) {
                if (j > 0) {
                    if (values[j] != null) {
                        double[] pointPrev = (double[]) values[j - 1];
                        double[] point = (double[]) values[j];

                        int barMiddle = (int) indicatorDrawingSupport.getMiddleOfCandle(j );
                        int barMiddlePrev = (int) indicatorDrawingSupport.getMiddleOfCandle(j - 1);

                        if (point[FASTMA] > point[SLOWMA])
                            g.setColor(VERY_LIGHT_GREEN);
                        else
                            g.setColor(VERY_LIGHT_RED);

                        int[] xPoints = new int[4];
                        int[] yPoints = new int[4];
                        if (pointPrev != null && point != null) {
                            yPoints[0] = (int) indicatorDrawingSupport.getYForValue(pointPrev[FASTMA]);
                            yPoints[1] = (int) indicatorDrawingSupport.getYForValue(point[FASTMA]);
                            yPoints[2] = (int) indicatorDrawingSupport.getYForValue(point[SLOWMA]);
                            yPoints[3] = (int) indicatorDrawingSupport.getYForValue(pointPrev[SLOWMA]);

                            xPoints[0] = barMiddlePrev;
                            xPoints[1] = barMiddle;
                            xPoints[2] = barMiddle;
                            xPoints[3] = barMiddlePrev;

                            g.fillPolygon(xPoints, yPoints, 4);
                        }
                    }
                }
            }
        }

        return null;
    }

    private void print(String sss) {
        context.getConsole().getOut().println(sss) ;
    }
}
