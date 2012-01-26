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

public class t33_rc2 implements IStrategy {
    private final String id = "t33_rc2";
    private IAccount account;
    private IChart chart;
    private IConsole console;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Time Period")
    public Period period = Period.FIVE_MINS;

    @Configurable("Indicator Filter")
    public Filter indicatorFilter = Filter.NO_FILTER;
    @Configurable("T3 Periods")
    public int t3TimePeriods = 14;
    @Configurable(value="T3 Volume Factor", stepSize=0.05)
    public double t3VolumeFactor = 0.7;
    //@Configurable("Candles Before")
    public int candlesBefore = 2;
    //@Configurable("Candles After")
    public int candlesAfter = 0;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.1)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (Pips)", stepSize=0.5)
    public double slippage = 2;
    @Configurable(value="Stop Loss (Pips)", stepSize=0.5)
    public double stopLossPips = 20;
    @Configurable(value="Take Profit (Pips)", stepSize=0.5)
    public double takeProfitPips = 40;
    @Configurable("Break-even (Percent)")
    public double breakEven = 60.0;
    @Configurable("Trailing Stop (Pips)")
    public int trailingStopPips = 10;
    @Configurable("Trailing Stop (On)")
    public boolean enabledTrailingStop = true;
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
            chart.addIndicator(indicators.getIndicator("T3"), new Object[]{t3TimePeriods / 2, t3VolumeFactor, Color.BLUE});
            chart.addIndicator(indicators.getIndicator("T3"), new Object[]{t3TimePeriods, t3VolumeFactor, Color.RED});
            chart.addIndicator(indicators.getIndicator("T3"), new Object[]{t3TimePeriods * 2, t3VolumeFactor, Color.BLACK});
        }
        //bdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
        double accountEquity = account.getEquity();
        double leverage = account.getLeverage();
        DecimalFormat df = new DecimalFormat(accountEquity < 1000 ? "#.####" : "##.###");
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
        for (IOrder order : engine.getOrders()) {
            engine.getOrder(order.getLabel()).close();
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
            if (order.getState() == IOrder.State.FILLED) {
                double pips = order.getProfitLossInPips();

                if (order.isLong() && pips > takeProfitPips * (breakEven / 100)) {
                    order.setStopLossPrice(getPipPrice((int) pips - trailingStopPips));
                } else if (!order.isLong() && pips > takeProfitPips * (breakEven / 100)) {
                    order.setStopLossPrice(getPipPrice((int) pips + trailingStopPips));
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
        IBar prevBar = history.getBar(instrument, period, OfferSide.BID, 1);
        double t3s[] = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriods / 2,
                                   t3VolumeFactor, indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        double t3m[] = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriods,
                                   t3VolumeFactor, indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        double t3l[] = indicators.t3(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, t3TimePeriods * 2,
                                   t3VolumeFactor, indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);

        if (positionsTotal(instrument) == 0)  {
            if (/* t3m[1] > t3m[0] && */ t3m[1] > t3l[1] && bidPrice > t3s[1]) {
                submitOrder(OrderCommand.BUY);
            }
            else if (/* t3m[1] < t3m[0] && */ t3m[1] < t3l[1] && askPrice < t3s[1]) {
                submitOrder(OrderCommand.SELL);
            }
        }
    }

    private IOrder submitOrder(OrderCommand orderCmd) throws JFException {
        double stopLossPrice, takeProfitPrice;

        // Calculate stop-loss and take-profit prices
        if (orderCmd == OrderCommand.BUY) {
            stopLossPrice = history.getLastTick(instrument).getBid() - getPipPrice(stopLossPips);
            takeProfitPrice = history.getLastTick(instrument).getBid() + getPipPrice(takeProfitPips);
        } else {
            stopLossPrice = history.getLastTick(instrument).getAsk() + getPipPrice(stopLossPips);
            takeProfitPrice = history.getLastTick(instrument).getAsk() - getPipPrice(takeProfitPips);
        }

        // Submit an order for the specified instrument at the current market price
        return engine.submitOrder(getLabel(instrument), instrument, orderCmd, volume, 0, slippage,
                                  stopLossPrice, takeProfitPrice);
    }

    private int positionsTotal(Instrument instrument) throws JFException {
        int count = 0;
        for (IOrder order : engine.getOrders(instrument))
            if (order.getState() == IOrder.State.FILLED) count++;

        return count;
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
