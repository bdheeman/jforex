package jforex.strategies.dukascopy;

import java.math.BigDecimal;
import java.util.UUID;
import javax.swing.SwingConstants;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.drawings.IRectangleChartObject;

public class swr_rc2 implements IStrategy {
    private IEngine engine;
    private IConsole console;
    private IHistory history;
    private IChart chart;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Volume")
    public double volume = 0.02;

    private IRectangleChartObject rectangle;
    private ITick formerTick;
    private int counter = 0;

    @Override
    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.console = context.getConsole();
        this.history = context.getHistory();
        this.chart = context.getChart(instrument);
        this.formerTick = history.getLastTick(instrument);

        ITick tick = history.getLastTick(instrument);
        double askBidDiff = Math.abs(tick.getAsk() - tick.getBid());

        // draw rectangle
        this.rectangle = chart.getChartObjectFactory().createRectangle(getKey("rectangle"), tick.getTime() + Period.TEN_SECS.getInterval(),
                         tick.getBid() - askBidDiff, tick.getTime() + 3 * Period.TEN_SECS.getInterval(), tick.getBid() + 3 * askBidDiff);

        chart.addToMainChartUnlocked(rectangle);
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
    }

    @Override
    public void onStop() throws JFException {
        // remove rectangle on exit
        chart.remove(rectangle.getKey());

        // close all orders
        for (IOrder order : engine.getOrders()) {
            engine.getOrder(order.getLabel()).close();
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (!instrument.equals(this.instrument))
            return;

        ITick lastTick = tick;

        // if the last tick has come into the rectangle from the left side
        if (!isInsideRectTime(formerTick) && isInsideRectTime(lastTick) && askInsideRect(lastTick)) {
            // take profit price = rectangle top
            // stop loss = rectangle bottom
            engine.submitOrder(getLabel(instrument), this.instrument, OrderCommand.BUY, this.volume, 0, 0, roundToTenthPip( rectangle.getPrice(0) ), roundToTenthPip(rectangle.getPrice(1)) );
        }

        // if the former tick has already been within the time frame of the rectangle but not within the price range
        // and the last tick has come into the rectangle from the top
        if (isInsideRectTime(lastTick) && bidInsideRect(lastTick) && !bidInsideRect(formerTick) && formerTick.getBid() > rectangle.getPrice(1)) {
            // take profit if price
            engine.submitOrder(getLabel(instrument), this.instrument, OrderCommand.SELL, this.volume);
        }

        // if the former tick has already been within the time frame of the rectangle but not within the price range
        // and the last tick has come into the rectangle from the bottom
        if (isInsideRectTime(lastTick) && askInsideRect(lastTick) && !askInsideRect(formerTick) && formerTick.getAsk() < rectangle.getPrice(0)) {
            // take profit if price
            engine.submitOrder(getLabel(instrument), this.instrument, OrderCommand.BUY, this.volume);
        }

        formerTick = lastTick;
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    }

    // Order processing functions
    private boolean askInsideRect(ITick tick) {
        return tick.getAsk() >= rectangle.getPrice(0) && tick.getAsk() <= rectangle.getPrice(1);
    }

    private boolean bidInsideRect(ITick tick) {
        return tick.getBid() >= rectangle.getPrice(0) && tick.getBid() <= rectangle.getPrice(1);
    }

    private boolean isInsideRectTime(ITick tick) {
        return tick.getTime() >= rectangle.getTime(0) && tick.getTime() <= rectangle.getTime(1);
    }

    private String getKey(String str) {
        return str + UUID.randomUUID().toString().replace('-', '0');
    }

    protected String getLabel(Instrument instrument) {
        String label = instrument.name();
        label = label + (counter++);
        label = label.toUpperCase();
        return label;
    }

    private void print(Object o) {
        console.getOut().println(o);
    }

    private double roundToTenthPip(double price) {
        BigDecimal bd = new BigDecimal(price);
        bd = bd.setScale(instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP);
        return bd.doubleValue();
    }
}
