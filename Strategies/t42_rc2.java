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

import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;
import com.dukascopy.api.IMessage.Type;

public class t42_rc2 implements IStrategy {
    private final String id = this.getClass().getName().substring(27, 31);
    private IAccount account;
    private IConsole console;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Time Period")
    public Period period = Period.ONE_MIN;

    @Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    @Configurable(value="DC Period", stepSize=1)
    public int dcTimePeriod = 21;
    @Configurable("T3 Period (Fast)")
    public int t3TimePeriodFast = 34;
    @Configurable("T3 Period (Slow)")
    public int t3TimePeriodSlow = 233;
    @Configurable(value="T3 Volume Factor", stepSize=0.05)
    public double t3VolumeFactor = 0.7;
    //@Configurable("Candles Before")
    public int numberOfCandlesBefore = 2;
    //@Configurable("Candles After")
    public int numberOfCandlesAfter = 0;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.01)
    public double riskPercent = 0.21;
    @Configurable(value="Use Leverage (Percent)", stepSize=0.1)
    public double maximumUoL = 40.0;
    @Configurable(value="Slippage (Pippets)", stepSize=0.1)
    public double slippage = 2.1;
    @Configurable(value="Stop Loss (factor)", stepSize=0.1)
    public double mStopLossPips = 2.1;
    private double stopLossPips = mStopLossPips;
    @Configurable(value="Take Profit (factor)", stepSize=0.1)
    public double mTakeProfitPips = 5.5;
    private double takeProfitPips = mTakeProfitPips;
    @Configurable("Use StopLoss? (No)")
    public boolean useStopLoss = false;
    @Configurable("Close HugeLoss? (No)")
    public boolean closeHugeLoss = false;
    @Configurable("Close all on Stop? (Yes)")
    public boolean closeAllOnStop = true;
    @Configurable("Debug/Verbose? (No)")
    public boolean verbose = false;

    private double bidPrice, askPrice;
    private ITick lastTick;

    @Override
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();
        indicators = context.getIndicators();

        // Do subscribe an instrument
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        // Print messages, but related to orders
        if (message.getOrder() != null && message.getOrder().getLabel().substring(0,id.length()).equals(id)) {
            switch (message.getType()) {
                // skipp the following
                case ORDER_SUBMIT_OK:
                case ORDER_CLOSE_OK:
                case ORDER_FILL_OK:
                case ORDERS_MERGE_OK:
                case ORDER_CHANGED_OK:
                    break;
                case NOTIFICATION:
                    console.getOut().println(message.getOrder().getLabel() +" "+ message.getContent().replaceAll(".*-Order", "Order"));
                    break;
                default:
                    console.getOut().println(message.getOrder().getLabel() +" "+ message.getType() +" "+ message.getContent());
            }
        }
    }

    @Override
    public void onStop() throws JFException {
        if (!closeAllOnStop)
            return;

        // Prepare for merge; remove SL and TP if any
        List<IOrder> existingOrders = new ArrayList<IOrder>();
        for (IOrder order : engine.getOrders(instrument)) {
            if(order.getLabel().substring(0,id.length()).equals(id)) {
                if (order.getState() == IOrder.State.FILLED) {
                    if (order.getStopLossPrice() > 0) {
                        order.setStopLossPrice(0);
                        order.waitForUpdate(200);
                    }
                    if (order.getTakeProfitPrice() > 0) {
                        order.setTakeProfitPrice(0);
                        order.waitForUpdate(200);
                    }
                    existingOrders.add(order);
                }
            }
        }

        // Try merging all orders, if more than 1
        try {
            IOrder prevOrder = null;
            int counter = 0;
            for (IOrder order : existingOrders) {
                if (prevOrder == null) {
                    prevOrder = order;
                    continue;
                }
                IOrder mergedOrder = engine.mergeOrders(getLabel(instrument) + "_" + ++counter, prevOrder, order);
                mergedOrder.waitForUpdate(200);
                prevOrder = mergedOrder;
            }
        } catch (JFException e) {
            console.getOut().println(e.getMessage());
        } finally {
            // Close all orders
            for (IOrder order : engine.getOrders(instrument)) {
                if(order.getLabel().substring(0,id.length()).equals(id))
                    order.close();
            }
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (!instrument.equals(this.instrument))
            return;

        bidPrice = tick.getBid();
        askPrice = tick.getAsk();
        lastTick = tick;
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (!instrument.equals(this.instrument) || !period.equals(this.period))
            return;

        if (askBar.getVolume() == 0 || bidBar.getVolume() == 0)
            return;

        IBar histBar = history.getBar(instrument, period, OfferSide.BID, 1);
        double[][] ha = indicators.heikenAshi(instrument, period, OfferSide.BID,
            indicatorFilter, numberOfCandlesBefore, histBar.getTime(), numberOfCandlesAfter);

        final int PREV = numberOfCandlesBefore + numberOfCandlesAfter - 1;
        final int OPEN = 0; final int HIGH = 3; final int LOW = 2; final int CLOSE = 1;

        double average = ((ha[PREV][HIGH] - ha[PREV][LOW]) + (ha[PREV-1][HIGH] - ha[PREV-1][LOW])) / 2.0 * 10000;
        double spread = askPrice - bidPrice;

        stopLossPips = roundPips(mStopLossPips * average);
        if (stopLossPips < mStopLossPips)
            return;

        double volume = getLotSize(account);
        takeProfitPips = roundPips(mTakeProfitPips * average);
        if (takeProfitPips < mTakeProfitPips)
            return;

        if (!useStopLoss)
            stopLossPips = 0;

        // Major indicators
        double[][] dc = indicators.donchian(instrument, period, OfferSide.BID, dcTimePeriod,
            indicatorFilter, numberOfCandlesBefore, histBar.getTime(), numberOfCandlesAfter);

        final int UPPER = 0; final int LOWER = 1;

        double[] t3f = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriodFast, t3VolumeFactor,
            indicatorFilter, numberOfCandlesBefore, histBar.getTime(), numberOfCandlesAfter);
        double[] t3s = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriodSlow, t3VolumeFactor,
            indicatorFilter, numberOfCandlesBefore, histBar.getTime(), numberOfCandlesAfter);

        // Take care, your profits should not turn into losses
        if (askPrice < t3f[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (order.isLong() && order.getProfitLossInPips() > mStopLossPips) order.close();
                }
            }
        } else if  (bidPrice > t3f[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (!order.isLong() && order.getProfitLossInPips() > mStopLossPips) order.close();
                }
            }
        }

        // Risk management; book growing/huge losses
        if (closeHugeLoss && askPrice > t3s[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (order.isLong() && order.getProfitLossInPips() < 0) order.close();
                }
            }
        } else if  (closeHugeLoss && bidPrice < t3s[PREV]) {
            for (IOrder order : engine.getOrders()) {
            if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                if (!order.isLong() && order.getProfitLossInPips() < 0) order.close();
                }
            }
        }

        // Use of Leverage management
        double amt = 0;
        boolean uol = account.getUseOfLeverage() > maximumUoL;
        if (uol) {
            for (IOrder order : engine.getOrders(instrument)) {
                if (order.getState() == IOrder.State.FILLED) {
                    if (order.isLong())
                        amt += order.getAmount();
                    else
                        amt -= order.getAmount();
                }
            }
        }
        boolean longOk = (uol && amt > 0) ? false : true;
        boolean shortOk = (uol && amt < 0) ? false : true;

        // Print debug/info messages
        if (verbose) {
            SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            bdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            console.getOut().printf("%s\n", bdf.format(roundTime(histBar.getTime(), 60000)));
            console.getOut().printf("  HA %s\n", Arrays.deepToString(ha));
            console.getOut().printf("  DC %s\n", Arrays.deepToString(dc));
            console.getOut().printf("  3F %s\n", Arrays.toString(t3f));
            console.getOut().printf("  3S %s\n", Arrays.toString(t3s));
            console.getOut().printf("  BID %.5f ASK %.5f SL %.2f TP %.2f\n", bidPrice, askPrice, stopLossPips, takeProfitPips);
            console.getOut().printf("  AVG %f SPD %.5f VOL %f\n", average, spread, volume);
            if (uol) console.getOut().printf("  AMT %f UoL %.2f\n", amt, account.getUseOfLeverage());
        }

        // BUY on lower lows
        if (longOk && bidPrice + spread < dc[LOWER][PREV] && dc[LOWER][PREV] < dc[LOWER][PREV-1]) {
            double stopLossPrice = stopLossPips > 0 ? stopLossPrice = askPrice - getPipPrice(stopLossPips) : 0;
            double takeProfitPrice = askPrice + getPipPrice(takeProfitPips);

            console.getOut().printf("%s BUY #%s @%.5f VOL %.4f SL %.5f TP %.5f\n", getLabel(instrument), instrument.name(), askPrice, volume, stopLossPrice, takeProfitPrice);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              stopLossPrice, takeProfitPrice);
        }
        // SELL on higher highs
        if (shortOk && askPrice > dc[UPPER][PREV] && dc[UPPER][PREV] > dc[UPPER][PREV-1]) {
            double stopLossPrice = stopLossPips > 0 ? stopLossPrice = bidPrice + getPipPrice(stopLossPips) : 0;
            double takeProfitPrice = bidPrice - getPipPrice(takeProfitPips);

            console.getOut().printf("%s SELL #%s @%.5f VOL %.4f SL %.5f TP %.5f\n", getLabel(instrument), instrument.name(), bidPrice, volume, stopLossPrice, takeProfitPrice);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.SELL, volume, bidPrice, slippage,
                                              stopLossPrice, takeProfitPrice);
        }
    }

    protected String getLabel(Instrument instrument) throws JFException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String label = instrument.name().toLowerCase();
        return id + label.substring(0, 2) + label.substring(3, 5) + sdf.format(roundTime(lastTick.getTime(), 60000));
    }

    protected double getLotSize(IAccount account) throws JFException {
        double accountEquity = account.getEquity();
        double leverage = account.getLeverage();
        double lotSize = accountEquity * (riskPercent / 100) / (stopLossPips * leverage);
        return lotSize - lotSize % 0.001 + 0.001;
    }

    protected double getPipPrice(double pips) throws JFException {
        double pipPrice = pips * instrument.getPipValue();
        return pipPrice - pipPrice % 0.000001;
    }

    protected double roundPips(double pips) throws JFException {
        return pips - pips % 0.5 + 0.5;
    }

    protected long roundTime(long time, long milliseconds) throws JFException {
        return time - time % milliseconds + milliseconds;
    }
}
