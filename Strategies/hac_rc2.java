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

import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;

public class hac_rc2 implements IStrategy {
    private final String id = "hac_rc2";
    private IAccount account;
    //private IChart chart;
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
    //@Configurable("Candles Before")
    public int candlesBefore = 3;
    //@Configurable("Candles After")
    public int candlesAfter = 0;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.1)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (Pips)", stepSize=0.5)
    public double slippage = 2;
    @Configurable(value="Stop Loss (Pips)", stepSize=0.5)
    public double stopLossPips = 20;
    @Configurable(value="Take Profit (Pips)", stepSize=0.5)
    public double takeProfitPips = 140;
    @Configurable(value="Threshold (Pips)", stepSize=0.1)
    public double threshold = 1.5;
    @Configurable("Close all on Stop (Yes)")
    public boolean closeAllOnStop = true;

    private ITick lastTick;
    //private SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
    private double volume = 0.05;

    private boolean buyActive = false;
    private boolean sellActive = false;

    @Override
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        //chart = context.getChart(instrument);
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();
        indicators = context.getIndicators();

        onAccount(account);
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);
        //if (chart != null) {
        //    chart.removeAll();
        //    chart.addIndicator(indicators.getIndicator("HEIKINASHI"));
        //}
        //bdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        threshold /= 10000;
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
        // Strange! JForex's heikenAshi returns OPEN, CLOSE, MIN, MAX :(
        double[][] ha = indicators.heikenAshi(instrument, period, OfferSide.BID, indicatorFilter,
                                                candlesBefore, prevBar.getTime(), candlesAfter);
        final int PREV = 0;
        final int CURR = 1;
        final int THIS = 2;
        final int OPEN = 0;
        final int HIGH = 3;
        final int LOW = 2;
        final int CLOSE = 1;

        double average = ((ha[PREV][HIGH] - ha[PREV][LOW]) + (ha[CURR][HIGH] - ha[CURR][LOW]) + (ha[THIS][HIGH] - ha[THIS][LOW])) / 3;
        if (average < threshold)
            return;

        // BUY
        if (ha[PREV][CLOSE] < ha[PREV][OPEN] && ha[CURR][CLOSE] < ha[CURR][OPEN] && ha[THIS][CLOSE] > ha[THIS][OPEN] && !buyActive) {
            //console.getOut().printf("pO: %.5f pH: %.5f pL: %.5f pC: %.5f\n", ha[PREV][OPEN], ha[PREV][HIGH], ha[PREV][LOW], ha[PREV][CLOSE]);
            //console.getOut().printf("hO: %.5f hH: %.5f hL: %.5f hC: %.5f\n", ha[CURR][OPEN], ha[CURR][HIGH], ha[CURR][LOW], ha[CURR][CLOSE]);
            //console.getOut().println("HA truned Blue " + bdf.format(prevBar.getTime()));
            CloseOrders(OrderCommand.SELL);
            sellActive = false;
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              askPrice - getPipPrice(stopLossPips), askPrice + getPipPrice(takeProfitPips));
            order.waitForUpdate(2000);
            buyActive = true;
        }
        // SELL
        if (ha[PREV][CLOSE] > ha[PREV][OPEN] && ha[CURR][CLOSE] > ha[CURR][OPEN] && ha[THIS][CLOSE] < ha[THIS][OPEN] && !sellActive) {
            //console.getOut().printf("pO: %.5f pH: %.5f pL: %.5f pC: %.5f\n", ha[PREV][OPEN], ha[PREV][HIGH], ha[PREV][LOW], ha[PREV][CLOSE]);
            //console.getOut().printf("hO: %.5f hH: %.5f hL: %.5f hC: %.5f\n", ha[CURR][OPEN], ha[CURR][HIGH], ha[CURR][LOW], ha[CURR][CLOSE]);
            //console.getOut().println("HA truned Yellow " + bdf.format(prevBar.getTime()));
            CloseOrders(OrderCommand.BUY);
            buyActive = false;
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.SELL, volume, bidPrice, slippage,
                                              bidPrice + getPipPrice(stopLossPips), bidPrice - getPipPrice(takeProfitPips));
            order.waitForUpdate(2000);
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
