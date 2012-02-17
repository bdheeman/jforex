package jforex.strategies.dukascopy;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;
import com.dukascopy.api.indicators.IIndicator;

public class t44_rc2 implements IStrategy {
    private IConsole console;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Period")
    public Period selectedPeriod = Period.TEN_MINS;

    @Configurable(value="Bars on sides")
    public int barCount = 7;

    @Configurable(value="Risk (percent)", stepSize=0.05)
    public double riskPercent = 2.0;
    @Configurable(value="Slippage (pips)", stepSize=0.1)
    public double slippage = 0.5;
    @Configurable(value="Stop loss (pips)", stepSize=0.5)
    public double stopLossPips = 0;
    @Configurable(value="Take profit (pips)", stepSize=0.5)
    public double takeProfitPips = 0;
    @Configurable(value="BE trigger (pips)", stepSize=0.5)
    public double beTrigger = 0;
    @Configurable(value="BE lock-in (pips)", stepSize=0.5)
    public double beLockin = 0;
    @Configurable(value="TS trigger (pips)", stepSize=0.5)
    public double trailingTrigger = 0;

    private IOrder order;
    private int counter = 0;
    private double volume = 0.001;
    private boolean wasSignal;

    @Override
    public void onStart(IContext context) throws JFException {
        this.console = context.getConsole();
        this.engine = context.getEngine();
        this.history = context.getHistory();
        this.indicators = context.getIndicators();

        // Do subscribe selected instrument
        Set subscribedInstruments = new HashSet();
        subscribedInstruments.add(instrument);
        context.setSubscribedInstruments(subscribedInstruments);

        // Add indicators for visual testing
        IChart chart = context.getChart(instrument);
        if (chart != null && engine.getType() == IEngine.Type.TEST) {
            chart.addIndicator(indicators.getIndicator("FractalLines"), new Object[] {barCount});
        }
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
    }

    @Override
    public void onStop() throws JFException {
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument != this.instrument)
            return;

        Object[] askFact0 = indicators.calculateIndicator(instrument, this.selectedPeriod,
                            new OfferSide[] { OfferSide.ASK }, "FractalLines", new AppliedPrice[] {AppliedPrice.CLOSE}, new Object[] {barCount}, 0);
        Object[] askFact1 = indicators.calculateIndicator(instrument, this.selectedPeriod,
                            new OfferSide[] { OfferSide.ASK }, "FractalLines", new AppliedPrice[] {AppliedPrice.CLOSE}, new Object[] {barCount}, 1);

        Object[] bidFact0 = indicators.calculateIndicator(instrument, this.selectedPeriod,
                            new OfferSide[] { OfferSide.BID }, "FractalLines", new AppliedPrice[] {AppliedPrice.CLOSE}, new Object[] {barCount}, 0);
        Object[] bidFact1 = indicators.calculateIndicator(instrument, this.selectedPeriod,
                            new OfferSide[] { OfferSide.BID }, "FractalLines", new AppliedPrice[] {AppliedPrice.CLOSE}, new Object[] {barCount}, 1);

        double red0 = (Double) askFact0[0];
        double red1 = (Double) askFact1[0];
        double blue0 = (Double) bidFact0[1];
        double blue1 = (Double) bidFact1[1];

        IBar askBar1 = history.getBar(instrument, selectedPeriod, OfferSide.ASK, 1);
        IBar bidBar1 = history.getBar(instrument, selectedPeriod, OfferSide.BID, 1);

        if (!isActive(order)) {
            // discard the order if is closed on stop loss or take profit
            order = null;
        }

        if (!wasSignal && askBar1.getHigh() <= red1 && tick.getAsk() > red0) {
            // long signal when current bar pirces upper red fractal line
            if (order != null && !order.isLong()) {
                closeOrder(order);
                order = null;
            }
            if (order == null) {
                order = submitOrder(OrderCommand.BUY);
            }
            wasSignal = true;
        }

        if (!wasSignal && bidBar1.getLow() >= blue1 && tick.getBid() < blue0) {
            // short signal when current bar pirces lower blue fractal line
            if (order != null && order.isLong()) {
                closeOrder(order);
                order = null;
            }
            if (order == null) {
                order = submitOrder(OrderCommand.SELL);
            }
            wasSignal = true;
        }

        if (order != null && order.getState() == IOrder.State.FILLED) {
            double newStop;

            double openPrice = order.getOpenPrice();
            double currentStopLoss = order.getStopLossPrice();

            if (order.isLong()) { // long side order
                if (trailingTrigger > 0
                        && tick.getBid() > currentStopLoss + pipToPrice(stopLossPips)
                        && tick.getBid() > openPrice + pipToPrice(trailingTrigger)) {
                    // trailing stop loss
                    newStop = tick.getBid() - pipToPrice(stopLossPips);
                    newStop = (new BigDecimal(newStop)).setScale(instrument.getPipScale(), BigDecimal.ROUND_HALF_UP).doubleValue();

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                    }
                } else if (beTrigger > 0 && tick.getBid() >= (openPrice + pipToPrice(beTrigger))) {
                    // break even
                    newStop = openPrice + pipToPrice(beLockin);
                    newStop = (new BigDecimal(newStop)).setScale(instrument.getPipScale(), BigDecimal.ROUND_HALF_UP).doubleValue();

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                    }
                }

            } else { // short side order
                if (trailingTrigger > 0
                        && tick.getAsk() < currentStopLoss - pipToPrice(stopLossPips)
                        && tick.getAsk() < openPrice - pipToPrice(trailingTrigger) ) {
                    // trailing stop loss
                    newStop = tick.getAsk() + pipToPrice(stopLossPips);
                    newStop = (new BigDecimal(newStop)).setScale(instrument.getPipScale(), BigDecimal.ROUND_HALF_UP).doubleValue();

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                    }
                } else if (beTrigger > 0 && tick.getAsk() <= (openPrice - pipToPrice(beTrigger))) {
                    // break even
                    newStop = openPrice - pipToPrice(beLockin);
                    newStop = (new BigDecimal(newStop)).setScale(instrument.getPipScale(), BigDecimal.ROUND_HALF_UP).doubleValue();

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                    }
                }
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (period != this.selectedPeriod || instrument != this.instrument)
            return;

        wasSignal = false;
    }

    // Order processing functions
    private IOrder submitOrder(OrderCommand orderCmd) throws JFException {
        double stopLossPrice = 0.0, takeProfitPrice = 0.0;

        if (orderCmd == OrderCommand.BUY) {
            if (stopLossPips > 0) {
                stopLossPrice = history.getLastTick(instrument).getBid() - pipToPrice(stopLossPips);
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = history.getLastTick(instrument).getBid() + pipToPrice(takeProfitPips);
            }
        } else {
            if (stopLossPips > 0) {
                stopLossPrice = history.getLastTick(instrument).getBid() + pipToPrice(stopLossPips);
            }
            if (takeProfitPips > 0) {
                takeProfitPrice = history.getLastTick(instrument).getBid() - pipToPrice(takeProfitPips);
            }
        }

        return engine.submitOrder(getLabel(instrument), instrument, orderCmd, volume, 0, slippage, stopLossPrice, takeProfitPrice);
    }

    private void closeOrder(IOrder order) throws JFException {
        if (isActive(order)) {
            order.close();
        }
    }

    private boolean isActive(IOrder order) throws JFException {
        if (order != null && order.getState() != IOrder.State.CLOSED && order.getState() != IOrder.State.CREATED && order.getState() != IOrder.State.CANCELED) {
            return true;
        }
        return false;
    }

    private String getLabel(Instrument instrument) {
        String label = instrument.name().toUpperCase();
        return label + (counter++);
    }

    private double pipToPrice(double pips) {
        return instrument.getPipValue() * pips;
    }
}
