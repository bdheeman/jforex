//
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
package jforex.strategies.bdheeman;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;

public class t32_rc2 implements IStrategy {
    private final String id = "t32_rc2";
    private IAccount account;
    private IChart chart;
    private IConsole console;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Time Period")
    public Period period = Period.TEN_MINS;

    @Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    @Configurable("T3 Periods (int)")
    public int t3TimePeriodFast = 5;
    @Configurable("T3 Periods (Long)")
    public int t3TimePeriodSlow = 10;
    @Configurable(value="T3 Volume Factor", stepSize=0.05)
    public double t3VolumeFactor = 0.7;
    @Configurable(value="Threshold (pips)", stepSize=0.1)
    public double threshold = 4;
    //@Configurable("Candles Before")
    public int candlesBefore = 2;
    //@Configurable("Candles After")
    public int candlesAfter = 0;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.1)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (Pips)", stepSize=0.5)
    public double slippage = 2;
    @Configurable(value="Stop Loss (Pips)", stepSize=0.5)
    public double stopLossPips = 40;
    @Configurable(value="Take Profit (Pips)", stepSize=0.5)
    public double takeProfitPips = 200;
    //@Configurable(value="Trailing Stop (Pips)", stepSize=0.5)
    public int trailingStopPips = 30;
    //@Configurable("Trailing Stop (On)")
    public boolean enabledTrailingStop = false;
    @Configurable("Close all on Stop (Yes)")
    public boolean closeAllOnStop = true;

    private ITick lastTick;
    //private SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
    private double volume = 0.002;

    private boolean buyActive = false;
    private boolean sellActive = false;

    @Override
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        chart = context.getChart(instrument);
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();
        indicators = context.getIndicators();

        onAccount(account);
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);
        if (chart != null) {
            chart.addIndicator(indicators.getIndicator("T3"), new Object[]{t3TimePeriodFast, t3VolumeFactor});
            chart.addIndicator(indicators.getIndicator("T3"), new Object[]{t3TimePeriodSlow, t3VolumeFactor});
        }
        //bdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
        double accountEquity = account.getEquity();
        double leverage = account.getLeverage();
        DecimalFormat df = new DecimalFormat(accountEquity < 2500 ? "#.###" : "##.##");
        volume = Double.valueOf(df.format(accountEquity * (riskPercent / 100) / (stopLossPips * leverage)));
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        //if the message is related to order print its content
        if (message.getOrder() != null) {
            console.getOut().println(message.getOrder().getLabel() + " " + message.getType() + " " + message.getContent());
        }
    }

    @Override
    public void onStop() throws JFException {
        if (!closeAllOnStop)
            return;

        // close all orders
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id)) {
                order.close();
            }
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (!instrument.equals(this.instrument))
            return;

        lastTick = tick;

        if (!enabledTrailingStop)
            return;
        // Move stop-loss, if possible
        for (IOrder order : engine.getOrders()) {
            if (order.getState() == IOrder.State.FILLED && order.getLabel().substring(0,id.length()).equals(id)) {
                double pips = order.getProfitLossInPips();

                if (pips > trailingStopPips) {
                    double stopLossPips = order.isLong() ? pips - trailingStopPips : pips + trailingStopPips;
                    order.setStopLossPrice(getPipPrice((int) stopLossPips));
                }
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (!instrument.equals(this.instrument) || !period.equals(this.period))
            return;

        if (askBar.getVolume() == 0 || bidBar.getVolume() == 0 || volume == 0)
            return;

        double askPrice = askBar.getClose();
        double bidPrice = bidBar.getClose();
        IBar prevBar = history.getBar(instrument, period, OfferSide.BID, 0);
        double t3s[] = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriodFast,
                                     t3VolumeFactor, indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        double t3l[] = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriodSlow,
                                     t3VolumeFactor, indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        final int PREV = 1;
        final int CURR = 0;
        double change = Math.abs(t3s[PREV] - t3s[CURR]);

        // BUY
        if (t3s[PREV] > t3l[PREV] && t3s[CURR] < t3l[CURR] && askPrice > t3s[CURR] && change > threshold / 10000 && !buyActive) {
            //console.getOut().printf("pM: %.5f pL: %.5f\n", t3s[PREV], t3l[PREV]);
            console.getOut().printf("cM: %.5f cL: %.5f Mv: %.5f\n", t3s[CURR], t3l[CURR], change);
            //console.getOut().println("t3s[CURR] ></ t3l[CURR] " + bdf.format(prevBar.getTime()));
            CloseOrders(OrderCommand.SELL);
            sellActive = false;
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              askPrice - getPipPrice(stopLossPips), askPrice + getPipPrice(takeProfitPips));
            order.waitForUpdate(200);
            buyActive = true;
        }
        // SELL
        if (t3s[PREV] < t3l[PREV] && t3s[CURR] > t3l[CURR] && bidPrice < t3s[CURR] && change > threshold / 10000 && !sellActive) {
            //console.getOut().printf("pM: %.5f pL: %.5f\n", t3s[PREV], t3l[PREV]);
            console.getOut().printf("cM: %.5f cL: %.5f Mv: %.5f\n", t3s[CURR], t3l[CURR], change);
            //console.getOut().println("t3s[CURR] ><\ t3l[CURR] " + bdf.format(prevBar.getTime()));
            CloseOrders(OrderCommand.BUY);
            buyActive = false;
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.SELL, volume, bidPrice, slippage,
                                              bidPrice + getPipPrice(stopLossPips), bidPrice - getPipPrice(takeProfitPips));
            order.waitForUpdate(200);
            sellActive = true;
        }
    }

    private void CloseOrders(OrderCommand oc) throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id)) {
                if(order.getOrderCommand() == oc) order.close();
            }
        }
    }

    protected String getLabel(Instrument instrument) throws JFException {
        String label = instrument.name();
        return id + label.substring(0, 2) + label.substring(3, 5) + sdf.format(roundTime(lastTick.getTime(), 60000));
    }

    protected double getPipPrice(double pips) throws JFException {
        return pips * instrument.getPipValue();
    }

    protected long roundTime(long time, long milliseconds) throws JFException {
        return time - time % milliseconds + milliseconds;
    }
}
