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

public class t43_rc2 implements IStrategy {
    private final String id = this.getClass().getName().substring(27, 31);
    private IAccount account;
    private IChart chart;
    private IConsole console;
    private IContext context;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Time Period")
    public Period period = Period.FIVE_MINS;

    @Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    @Configurable(value="TE Time Period", stepSize=1)
    public int teTimePeriod = 14;
    @Configurable(value="TE Deviation", stepSize=0.01)
    public double teDeviation = 0.1;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.1)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (Pips)", stepSize=0.5)
    public double slippage = 2;
    @Configurable(value="Stop Loss (Pips)", stepSize=0.5)
    public double stopLossPips = 19.5;
    @Configurable(value="Take Profit (Pips)", stepSize=0.5)
    public double takeProfitPips = 59.5;
    @Configurable("Close all on Stop (Yes)")
    public boolean closeAllOnStop = true;

    private ITick lastTick;
    //private SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
    private double volume = 0.002;

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
            chart.addIndicator(indicators.getIndicator("TRENDENVELOPES"), new Object[]{teTimePeriod, teDeviation});
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
        final int LOOK_BACK = 4000;
        double[][] te = indicators.trendEnv(instrument, period, OfferSide.BID, teTimePeriod, teDeviation,
                                            indicatorFilter, LOOK_BACK, bidBar.getTime(), 0);

        // BUY
        if(Double.isNaN(te[0][LOOK_BACK - 2]) && te[0][LOOK_BACK - 1] > 0) {
            closeOrders(IEngine.OrderCommand.SELL);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              askPrice - getPipPrice(stopLossPips), askPrice + getPipPrice(takeProfitPips));
            order.waitForUpdate(200);
        }
        // SELL
        if(te[0][LOOK_BACK - 2] > 0 && Double.isNaN(te[0][LOOK_BACK - 1])) {
            closeOrders(IEngine.OrderCommand.BUY);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.SELL, volume, bidPrice, slippage,
                                              bidPrice + getPipPrice(stopLossPips), bidPrice - getPipPrice(takeProfitPips));
            order.waitForUpdate(200);
        }
    }

    private void closeOrders(IEngine.OrderCommand oc) throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id)) {
                if(order.getOrderCommand() == oc) order.close();
            }
        }
    }

    protected String getLabel(Instrument instrument) throws JFException {
        String label = instrument.name().toLowerCase();
        return id + label.substring(0, 2) + label.substring(3, 5) + sdf.format(roundTime(lastTick.getTime(), 60000));
    }

    protected double getPipPrice(double pips) throws JFException {
        return pips * instrument.getPipValue();
    }

    protected long roundTime(long time, long milliseconds) throws JFException {
        return time - time % milliseconds + milliseconds;
    }
}
