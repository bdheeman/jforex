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
import com.dukascopy.api.IIndicators.MaType;

public class dma_rc2 implements IStrategy {
    private final String id = "dma_rc2";
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
    @Configurable("Fast MA Applied Price")
    public AppliedPrice appliedPriceFast = AppliedPrice.CLOSE;
    @Configurable("Slow MA Applied Price")
    public AppliedPrice appliedPriceSlow = AppliedPrice.CLOSE;
    @Configurable("Fast MA Time Period")
    public int timePeriodFast = 5;
    @Configurable("Fast MA Type")
    public MaType maTypeFast = MaType.SMA;
    @Configurable("Slow MA Time Period")
    public int timePeriodSlow = 34;
    @Configurable("Slow MA Type")
    public MaType maTypeSlow = MaType.SMA;
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
    //@Configurable(value="Break-Even (Percent)", stepSize=0.1)
    public double breakEeven = stopLossPips / takeProfitPips * 100;
    //@Configurable(value="Trailing Stop (Pips)", stepSize=0.5)
    public int trailingStopPips = 10;
    //@Configurable("Trailing Stop (Off)")
    public boolean enabledTrailingStop = false;
    @Configurable("Close all on Stop (Yes)")
    public boolean closeAllOnStop = true;

    private ITick lastTick;
    //private SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
    private double volume = 0.002;

    private boolean buyActive = false;
    private boolean sellActive = false;
    private double prevEquity;

    @Override
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        chart = context.getChart(instrument);
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();
        indicators = context.getIndicators();

        prevEquity = account.getEquity();
        onAccount(account);
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);
        if (chart != null) {
            chart.addIndicator(indicators.getIndicator("DMA"), new Object[]{timePeriodFast, maTypeFast, timePeriodSlow, maTypeSlow});
        }
        //bdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
        double accountEquity = account.getEquity();
        if (accountEquity > prevEquity) {
            if ((accountEquity - prevEquity) / prevEquity > 0.2) {
                riskPercent /= 2.0;
                if (riskPercent < 0.2) riskPercent = 0.2;
            };
        }
        double leverage = account.getLeverage();
        DecimalFormat df = new DecimalFormat(accountEquity < 2500 ? "#.###" : "##.##");
        volume = Double.valueOf(df.format(accountEquity * (riskPercent / 100) / (stopLossPips * leverage)));
        if (volume < 0.001) volume = 0.001;
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

                if (pips > (takeProfitPips * breakEeven / 100) + trailingStopPips) {
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
        IBar prevBar = history.getBar(instrument, period, OfferSide.BID, 1);
        double mas[] = indicators.ma(instrument, period, OfferSide.BID, appliedPriceFast, timePeriodFast, maTypeFast,
                                     indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        double mal[] = indicators.ma(instrument, period, OfferSide.BID, appliedPriceSlow, timePeriodSlow, maTypeSlow,
                                     indicatorFilter, candlesBefore, prevBar.getTime(), candlesAfter);
        final int PREV = 1;
        final int CURR = 0;

        // BUY
        if (mas[PREV] > mal[PREV] && mas[CURR] < mal[CURR] && askPrice > mas[CURR] && !buyActive) {
            //console.getOut().printf("pM: %.5f pL: %.5f\n", mas[PREV], mal[PREV]);
            //console.getOut().printf("cM: %.5f cL: %.5f\n", mas[CURR], mal[CURR]);
            //console.getOut().println("mas[CURR] ></ mal[CURR] " + bdf.format(prevBar.getTime()));
            CloseOrders(OrderCommand.SELL);
            sellActive = false;
            IOrder order = engine.submitOrder(getLabel(instrument), instrument, OrderCommand.BUY, volume, askPrice, slippage,
                                              askPrice - getPipPrice(stopLossPips), askPrice + getPipPrice(takeProfitPips));
            order.waitForUpdate(200);
            buyActive = true;
        }
        // SELL
        if (mas[PREV] < mal[PREV] && mas[CURR] > mal[CURR] && bidPrice < mas[CURR] && !sellActive) {
            //console.getOut().printf("pM: %.5f pL: %.5f\n", mas[PREV], mal[PREV]);
            //console.getOut().printf("cM: %.5f cL: %.5f\n", mas[CURR], mal[CURR]);
            //console.getOut().println("mas[CURR] ><\ mal[CURR] " + bdf.format(prevBar.getTime()));
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
