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
import com.dukascopy.api.IIndicators.MaType;
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
    public int dcTimePeriod = 22;
    //@Configurable("MA Applied Price (Fast)")
    public AppliedPrice appliedPriceFast = AppliedPrice.CLOSE;
    @Configurable("MA Period (Fast)")
    public int maTimePeriodFast = 8;
    @Configurable("MA Type (Fast)")
    public MaType maTypeFast = MaType.T3;
    //@Configurable("MA Applied Price (Slow)")
    public AppliedPrice appliedPriceSlow = AppliedPrice.CLOSE;
    @Configurable("MA Period (Slow)")
    public int maTimePeriodSlow = 40;
    @Configurable("MA Type (Slow)")
    public MaType maTypeSlow = MaType.T3;
    //@Configurable("Candles Before")
    public int numberOfCandlesBefore = 2;
    //@Configurable("Candles After")
    public int numberOfCandlesAfter = 0;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.01)
    public double riskPercent = 0.21;
    @Configurable(value="Use Leverage (Percent)", stepSize=0.1)
    public double maximumUoL = 40.0;
    @Configurable(value="Slippage (Pippets)", stepSize=0.1)
    public double slippage = 2;
    @Configurable(value="Stop Loss (factor)", stepSize=0.1)
    public double stopLossFactor = 2.1;
    private double stopLossPips = stopLossFactor;
    @Configurable(value="Take Profit (factor)", stepSize=0.1)
    public double takeProfitFactor = 5.5;
    private double takeProfitPips = takeProfitFactor;
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

        // Add indicators for visual testing
        IChart chart = context.getChart(instrument);
        if (chart != null && engine.getType() == IEngine.Type.TEST) {
            chart.addIndicator(indicators.getIndicator("DONCHIANCHANNEL"), new Object[] {dcTimePeriod});
            chart.addIndicator(indicators.getIndicator("DMA"), new Object[] {maTimePeriodFast, maTypeFast.ordinal(), maTimePeriodSlow, maTypeSlow.ordinal()});
            chart.addIndicator(indicators.getIndicator("HEIKINASHI"));
        }
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
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
            //console.getWarn().println(e.getMessage());
            console.getOut().println("<WARN> " + e.getMessage());
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

        IBar prevBar = history.getBar(instrument, period, OfferSide.BID, 1);
        double[][] ha = indicators.heikinAshi(instrument, period, OfferSide.BID,
                                              indicatorFilter, numberOfCandlesBefore, prevBar.getTime(), numberOfCandlesAfter);

        final int PREV = numberOfCandlesBefore + numberOfCandlesAfter - 1;
        final int OPEN = 0, HIGH = 2, LOW = 3, CLOSE = 1; /* DEMO */
        //final int OPEN = 0, HIGH = 3, LOW = 2, CLOSE = 1; /* LIVE */

        double average = priceToPips(((ha[PREV][HIGH] - ha[PREV][LOW]) + (ha[PREV-1][HIGH] - ha[PREV-1][LOW])) / 2.0);
        double spread = askPrice - bidPrice;

        stopLossPips = roundPips(stopLossFactor * average);
        if (stopLossPips < stopLossFactor)
            return;

        double volume = getLotSize(account);
        takeProfitPips = roundPips(takeProfitFactor * average);
        if (takeProfitPips < takeProfitFactor)
            return;

        if (!useStopLoss)
            stopLossPips = 0;

        // Major indicators
        double[][] dc = indicators.donchian(instrument, period, OfferSide.BID, dcTimePeriod,
                                            indicatorFilter, numberOfCandlesBefore, prevBar.getTime(), numberOfCandlesAfter);

        final int UPPER = 0, LOWER = 1;

        double[] maf = indicators.ma(instrument, period, OfferSide.BID, appliedPriceFast, maTimePeriodFast, maTypeFast,
                                     indicatorFilter, numberOfCandlesBefore, prevBar.getTime(), numberOfCandlesAfter);
        double[] mas = indicators.ma(instrument, period, OfferSide.BID, appliedPriceSlow, maTimePeriodSlow, maTypeSlow,
                                     indicatorFilter, numberOfCandlesBefore, prevBar.getTime(), numberOfCandlesAfter);

        // Take care, your profits should not turn into losses
        if (askPrice < maf[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (order.isLong() && order.getProfitLossInPips() > stopLossFactor) order.close();
                }
            }
        } else if  (bidPrice > maf[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (!order.isLong() && order.getProfitLossInPips() > stopLossFactor) order.close();
                }
            }
        }

        // Risk management; book growing/huge losses
        if (closeHugeLoss && askPrice > mas[PREV]) {
            for (IOrder order : engine.getOrders()) {
                if (order.getLabel().substring(0,id.length()).equals(id) && order.getState() == IOrder.State.FILLED) {
                    if (order.isLong() && order.getProfitLossInPips() < 0) order.close();
                }
            }
        } else if (closeHugeLoss && bidPrice < mas[PREV]) {
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
            console.getOut().printf("<DEBUG> %s\n", bdf.format(roundTime(prevBar.getTime(), 60000)));
            console.getOut().printf("  HA %s\n", Arrays.deepToString(ha));
            console.getOut().printf("  DC %s\n", Arrays.deepToString(dc));
            console.getOut().printf("  MAF %s\n", Arrays.toString(maf));
            console.getOut().printf("  MAS %s\n", Arrays.toString(mas));
            console.getOut().printf("  BID %.5f ASK %.5f SL %.2f TP %.2f\n", bidPrice, askPrice, stopLossPips, takeProfitPips);
            console.getOut().printf("  AVG %f SPD %.5f VOL %f\n", average, spread, volume);
            if (uol) console.getOut().printf("  AMT %f UoL %.2f\n", amt, account.getUseOfLeverage());
        }

        // BUY on lower lows
        if (longOk && bidPrice + spread < dc[LOWER][PREV] && dc[LOWER][PREV] < dc[LOWER][PREV-1]) {
            double stopLossPrice = stopLossPips > 0 ? stopLossPrice = askPrice - getPipPrice(stopLossPips) : 0;
            double takeProfitPrice = askPrice + getPipPrice(takeProfitPips);

            console.getOut().printf("%s <TWEET> BUY #%s @%.5f VOL %.4f SL %.5f TP %.5f\n", getLabel(instrument), instrument.name(), askPrice, volume, stopLossPrice, takeProfitPrice);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              stopLossPrice, takeProfitPrice);
        }
        // SELL on higher highs
        if (shortOk && askPrice > dc[UPPER][PREV] && dc[UPPER][PREV] > dc[UPPER][PREV-1]) {
            double stopLossPrice = stopLossPips > 0 ? stopLossPrice = bidPrice + getPipPrice(stopLossPips) : 0;
            double takeProfitPrice = bidPrice - getPipPrice(takeProfitPips);

            console.getOut().printf("%s <TWEET> SELL #%s @%.5f VOL %.4f SL %.5f TP %.5f\n", getLabel(instrument), instrument.name(), bidPrice, volume, stopLossPrice, takeProfitPrice);
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.SELL, volume, bidPrice, slippage,
                                              stopLossPrice, takeProfitPrice);
        }
    }

    // Order processing functions
    protected String getLabel(Instrument instrument) throws JFException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String label = instrument.name().toLowerCase();
        return id + label.substring(0, 2) + label.substring(3, 5) + sdf.format(roundTime(lastTick.getTime(), 60000));
    }

    // FIXME: for XAG and XAU
    protected double getLotSize(IAccount account) throws JFException {
        double accountEquity = account.getEquity();
        double leverage = account.getLeverage();
        double lotSize = accountEquity * (riskPercent / 100) / (stopLossPips * leverage);
        return lotSize - lotSize % 0.001 + 0.001;
    }

    protected double getPipPrice(double pips) throws JFException {
        double pipPrice = pips * instrument.getPipValue();
        return pipPrice - pipPrice % Math.pow(10, instrument.getPipScale() * -1);
    }

    protected double priceToPips(double price) {
        return price * Math.pow(10, instrument.getPipScale());
    }

    protected double roundPips(double pips) throws JFException {
        return pips - pips % 0.5 + 0.5;
    }

    protected long roundTime(long time, long milliseconds) throws JFException {
        return time - time % milliseconds + milliseconds;
    }
}
