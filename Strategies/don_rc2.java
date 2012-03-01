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
    @Configurable("Time Frame")
    public Period period = Period.TEN_MINS;

    @Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    //@Configurable("Candles Before")
    public int numberOfCandlesBefore = 2;
    //@Configurable("Candles After")
    public int numberOfCandlesAfter = 0;
    @Configurable("DC Time period")
    public int dcTimePeriod = 20;

    @Configurable(value="Risk (percent)", stepSize=0.05)
    public double riskPercent = 2.0;
    @Configurable(value="Breakeven (pips)", stepSize=0.5)
    public double breakevenPips = dcTimePeriod * 0.0; /* 50% */
    @Configurable(value="Slippage (pips)", stepSize=0.1)
    public double slippage = 2;
    @Configurable(value="Stop Loss (pips)", stepSize=0.5)
    public double stopLossPips = 0;
    @Configurable(value="Take Profit (pips)", stepSize=0.5)
    public double takeProfitPips = 0;
    @Configurable(value="Threshold (pips)", stepSize=0.5)
    public double thresholdPips = dcTimePeriod * 0.8; /* 80% */
    @Configurable(value="Close all on Stop? (No)")
    public boolean closeAllOnStop = false;

    private IOrder order = null, prevOrder = null;
    private int counter = 0;
    private double volume = 0.001;

    private IBar[] bar = {null, null};
    private double[][] dc = {{Double.NaN, Double.NaN},{Double.NaN, Double.NaN}};
    private final static int UPPER = 0, LOWER = 1;
    private final int PREV = numberOfCandlesBefore + numberOfCandlesAfter - 1;

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

    @Override
    public void onAccount(IAccount account) throws JFException {
        // Risk management, huh
        volume = account.getEquity() / (100 * account.getLeverage()) * (riskPercent / 100);
        volume = volume - volume % 0.001;
        if (volume < 0.001) volume = 0.001;
    }

    @Override
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

    @Override
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
        if (bar[PREV-1] == null || bar[PREV] == null)
            return;

        // Is it consolidation, eh
        if (priceToPips(instrument, dc[UPPER][PREV-1] - dc[LOWER][PREV-1]) < thresholdPips) {
            return;
        }

        // Buy/Long
        if (tick.getBid() > dc[UPPER][PREV-1] && bar[PREV-1].getClose() > dc[UPPER][PREV-1] && bar[PREV].getClose() <= dc[UPPER][PREV]) {
            if (order == null || !order.isLong()) {
                closeOrder(order);
                order = submitOrder(instrument, OrderCommand.BUY);
            }
        }
        // Sell/Short
        if (tick.getBid() < dc[LOWER][PREV-1] && bar[PREV-1].getClose() < dc[LOWER][PREV-1] && bar[PREV].getClose() >= dc[LOWER][PREV]) {
            if (order == null || order.isLong()) {
                closeOrder(order);
                order = submitOrder(instrument, OrderCommand.SELL);
            }
        }

        if (prevOrder != null) {
            switch (prevOrder.getState()) {
                case CREATED:
                case CLOSED:
                    this.prevOrder = null;
                    break;
                default:
                    //console.getWarn().println(prevOrder.getLabel() +" Closed failed!");
                    console.getOut().println(prevOrder.getLabel() +" <WARN> Closed failed!");
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (instrument != this.instrument || period != this.period)
            return;

        // private IBar[] bar = {null, null};
        bar[PREV] = history.getBar(instrument, period, OfferSide.BID, 2);
        bar[PREV-1] = history.getBar(instrument, period, OfferSide.BID, 1);

        // private double[][] dc = {{Double.NaN, Double.NaN},{Double.NaN, Double.NaN}};
        dc = indicators.donchian(instrument, period, OfferSide.BID, dcTimePeriod,
                                   indicatorFilter, numberOfCandlesBefore, bar[PREV-1].getTime(), numberOfCandlesAfter);

        // Set trailing stoploss, huh
        if (breakevenPips > 0 && isActive(order) && order.getProfitLossInPips() > breakevenPips) {
            if (order.isLong())
                order.setStopLossPrice(dc[LOWER][PREV-1] + getPipPrice(instrument, breakevenPips), OfferSide.BID);
            else
                order.setStopLossPrice(dc[UPPER][PREV-1] - getPipPrice(instrument, breakevenPips), OfferSide.ASK);
        }
    }

    // Order processing functions
    private IOrder submitOrder(Instrument instrument, OrderCommand orderCommand) throws JFException {
        double stopLossPrice = 0.0, takeProfitPrice = 0.0;
        double bidPrice = history.getLastTick(instrument).getBid();
        double askPrice = history.getLastTick(instrument).getAsk();
        String label = getLabel(instrument);
        String name = instrument.getPrimaryCurrency() + instrument.getPairsSeparator() + instrument.getSecondaryCurrency();

        if (orderCommand == OrderCommand.BUY) {
            if (stopLossPips > 0) {
                stopLossPrice = roundPrice(bidPrice - getPipPrice(instrument, stopLossPips));
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = roundPrice(bidPrice + getPipPrice(instrument, takeProfitPips));
            }
            console.getOut().printf("%s <TWEET> BUY #%s @%f SL %f TP %f\n", label, name, bidPrice, stopLossPrice, takeProfitPrice);
        } else {
            if (stopLossPips > 0) {
                stopLossPrice = roundPrice(askPrice + getPipPrice(instrument, stopLossPips));
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = roundPrice(askPrice - getPipPrice(instrument, takeProfitPips));
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
            prevOrder = order;
        }
    }

    protected boolean isActive(IOrder order) throws JFException {
        return (order != null && order.getState() == IOrder.State.FILLED) ? true : false;
    }

    protected String getLabel(Instrument instrument) {
        return id + instrument.name().substring(0, 2) + instrument.name().substring(3, 5) + String.format("%8d", ++counter).replace(" ", "0");
    }

    protected double getPipPrice(Instrument instrument, double pips) {
        return instrument.getPipValue() * pips;
    }

    protected double priceToPips(Instrument instrument, double price) {
        return price * Math.pow(10, instrument.getPipScale());
    }

    protected double roundPrice(double price) {
        return price - price % Math.pow(10, (this.instrument.getPipScale() + 1) * -1);
    }
}
