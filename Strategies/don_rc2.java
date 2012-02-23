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

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;
import com.dukascopy.api.indicators.IIndicator;

public class don_rc2 implements IStrategy {
    private final String id = this.getClass().getName().substring(27, 31).toUpperCase();
    private IConsole console;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Period")
    public Period period = Period.TEN_MINS;

    //@Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    @Configurable("DC Time period")
    public int dcTimePeriod = 14;

    @Configurable(value="Risk (percent)", stepSize=0.05)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (pips)", stepSize=0.1)
    public double slippage = 2;
    @Configurable(value="Stop Loss (pips)", stepSize=0.5)
    public double stopLossPips = 0;
    @Configurable(value="Take Profit (pips)", stepSize=0.5)
    public double takeProfitPips = 0;
    @Configurable(value="Close all on Stop? (No)")
    public boolean closeAllOnStop = false;
    @Configurable(value="Verbose/Debug? (No)")
    public boolean verbose = false;

    private IOrder order = null;
    private int counter = 0;
    private double volume = 0.001;

    private final static int HIGH = 0, LOW = 1;
    private IBar bar1 = null, bar2 = null;
    private double[] donchian1 = {Double.NaN},  donchian2 = {Double.NaN};

    @Override
    public void onStart(IContext context) throws JFException {
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();
        indicators = context.getIndicators();

        // Do subscribe selected instrument
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);

        // Add indicators for visual testing
        IChart chart = context.getChart(instrument);
        if (chart != null && engine.getType() == IEngine.Type.TEST) {
            chart.addIndicator(indicators.getIndicator("DONCHIANCHANNEL"), new Object[] {dcTimePeriod});
        }

        // Recall existing; last position, if any
        this.order = null;
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id)) {
                if (this.order != null) {
                    //console.getWarn().println(this.order.getLabel() +" Order IGNORED, manage it manually");
                    console.getOut().println(this.order.getLabel() +" <WARN> Order IGNORED, manage it manually");
                }
                this.order = order;
                counter = Integer.valueOf(order.getLabel().replaceAll("[^0-9]",""));
                //console.getNotif().println(order.getLabel() +" Order FOUND, shall try handling it");
                console.getOut().println(order.getLabel() +" <NOTICE> Order FOUND, shall try handling it");
            }
        }
        if (isActive(order))
            //console.getInfo().println(order.getLabel() +" ORDER_FOUND_OK");
            console.getOut().println(order.getLabel() +" <INFO> ORDER_FOUND_OK");
    }

    public void onAccount(IAccount account) throws JFException {
        // Risk management, huh
        volume = account.getEquity() / (100 * account.getLeverage()) * (riskPercent / 100);
        volume = volume - volume % 0.001;
        if (volume < 0.001) volume = 0.001;
    }

    public void onMessage(IMessage message) throws JFException {
        // Print messages, but related to own orders
        if (message.getOrder() != null && message.getOrder().getLabel().substring(0,id.length()).equals(id)) {
            String orderLabel = message.getOrder().getLabel();
            IMessage.Type messageType = message.getType();
            switch (messageType) {
                    // Ignore the following
                case ORDER_FILL_OK:
                case ORDER_CHANGED_OK:
                    break;
                case ORDER_SUBMIT_OK:
                case ORDER_CLOSE_OK:
                case ORDERS_MERGE_OK:
                    //console.getInfo().println(orderLabel +" "+ messageType);
                    console.getOut().println(orderLabel +" <INFO> "+ messageType);
                    break;
                case NOTIFICATION:
                    //console.getNotif().println(orderLabel +" "+ message.getContent().replaceAll(".*-Order", "Order"));
                    console.getOut().println(orderLabel +" <NOTICE> "+ message.getContent().replaceAll(".*-Order", "Order"));
                    break;
                case ORDER_CHANGED_REJECTED:
                case ORDER_CLOSE_REJECTED:
                case ORDER_FILL_REJECTED:
                case ORDER_SUBMIT_REJECTED:
                case ORDERS_MERGE_REJECTED:
                    //console.getWarn().println(orderLabel +" "+ messageType);
                    console.getOut().println(orderLabel +" <WARN> "+ messageType);
                    break;
                default:
                    //console.getErr().println(orderLabel +" *"+ messageType +"* "+ message.getContent());
                    console.getOut().println(orderLabel +" *"+ messageType +"* "+ message.getContent());
                    break;
            }
        }
    }

    public void onStop() throws JFException {
        if (!closeAllOnStop)
            return;

        // Close all orders
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id))
                order.close();
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument != this.instrument)
            return;

        // Act, but after collecting needful data
        if (bar1 == null || bar2 == null)
            return;

        // Buy/Long
        if (tick.getBid() > donchian1[HIGH] && bar1.getClose() > donchian1[HIGH] && bar2.getClose() <= donchian2[HIGH]) {
            if (order == null || !order.isLong()) {
                closeOrder(order);
                order = submitOrder(OrderCommand.BUY);
            }
        }
        // Sell/Short
        if (tick.getBid() < donchian1[LOW] && bar1.getClose() < donchian1[LOW] && bar2.getClose() >= donchian2[LOW]) {
            if (order == null || order.isLong()) {
                closeOrder(order);
                order = submitOrder(OrderCommand.SELL);
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (instrument != this.instrument || period != this.period)
            return;

        // private double[] donchian1 = { Double.NaN }, donchian2 = { Double.NaN };
        donchian2 = indicators.donchian(instrument, period, OfferSide.BID, dcTimePeriod, 2);
        donchian1 = indicators.donchian(instrument, period, OfferSide.BID, dcTimePeriod, 1);

        // private IBar bar1 = null, bar2 = null;
        bar2 = history.getBar(instrument, period, OfferSide.BID, 2);
        bar1 = history.getBar(instrument, period, OfferSide.BID, 1);
    }

    // Order processing functions
    private IOrder submitOrder(OrderCommand orderCommand) throws JFException {
        double stopLossPrice = 0.0, takeProfitPrice = 0.0;
        double bidPrice = history.getLastTick(instrument).getBid();
        double askPrice = history.getLastTick(instrument).getAsk();
        String label = getLabel(instrument);
        String name = instrument.getPrimaryCurrency() + instrument.getPairsSeparator() + instrument.getSecondaryCurrency();

        if (orderCommand == OrderCommand.BUY) {
            if (stopLossPips > 0) {
                stopLossPrice = bidPrice - getPipPrice(stopLossPips);
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = bidPrice + getPipPrice(takeProfitPips);
            }
            console.getOut().printf("%s <TWEET> BUY #%s @%f SL %f TP %f\n", label, name, bidPrice, stopLossPrice, takeProfitPrice);
        } else {
            if (stopLossPips > 0) {
                stopLossPrice = askPrice + getPipPrice(stopLossPips);
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = askPrice - getPipPrice(takeProfitPips);
            }
            console.getOut().printf("%s <TWEET> SELL #%s @%f SL %f TP %f\n", label, name, bidPrice, stopLossPrice, takeProfitPrice);
        }

        return engine.submitOrder(label, instrument, orderCommand, volume, 0, slippage, stopLossPrice, takeProfitPrice);
    }

    protected void closeOrder(IOrder order) throws JFException {
        if (isActive(order)) {
            order.close();
            //order.waitForUpdate(200, IOrder.State.CLOSED);
            order.waitForUpdate(200);
            switch (order.getState()) {
                case CREATED:
                case CLOSED:
                    this.order = null;
                    break;
                default:
                    //console.getWarn().println(order.getLabel() +" Closed failed!");
                    console.getOut().println(order.getLabel() +" <WARN> Closed failed!");
            }
        }
    }

    protected boolean isActive(IOrder order) throws JFException {
        return (order != null && order.getState() == IOrder.State.FILLED) ? true : false;
    }

    protected String getLabel(Instrument instrument) {
        return id + String.format("%10d", ++counter).replace(" ", "0");
    }

    protected double getPipPrice(double pips) {
        return pips * instrument.getPipValue();
    }
}
