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

import java.util.TimeZone;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;

public class tod_rc2 implements IStrategy {
    private final String id = this.getClass().getName().substring(27, 31);
    private IConsole console;
    private IEngine engine;
    private IHistory history;

    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Instrument")
    public Period period = Period.TEN_SECS;

    @Configurable(value="Risk Factor (Percent)", stepSize=0.01)
    public double riskPercent = 0.2;
    @Configurable(value="Slippage (Pips)", stepSize=0.1)
    public double slippage = 1;
    @Configurable(value="Stop Loss (Pips)", stepSize=0.1)
    public double stopLossPips = 5;
    @Configurable(value="Take Profit (Pips)", stepSize=0.1)
    public double takeProfitPips = 8;
    @Configurable("Close all on Stop (Yes)")
    public boolean closeAllOnStop = true;

    private ITick lastTick;
    //private SimpleDateFormat bdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
    private double volume = 0.002;

    @Override
    public void onStart(IContext context) throws JFException {
        console = context.getConsole();
        engine = context.getEngine();
        history = context.getHistory();

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

        //close all orders
        for (IOrder order : engine.getOrders(this.instrument)) {
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

        // take previous bar from historical data
        IBar prevBar = history.getBar(instrument, period, OfferSide.BID, 1);

        // console.getOut().println(bidBar.getClose() + " " + prevBar.getClose());
        OrderCommand cmd = bidBar.getClose() > prevBar.getClose()
                           ? OrderCommand.BUY
                           : OrderCommand.SELL;
        submitOrder(cmd);
    }

    private IOrder submitOrder(OrderCommand orderCmd) throws JFException {
        double stopLossPrice, takeProfitPrice;

        // Calculate stop loss and take profit prices
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

    protected String getLabel(Instrument instrument) throws JFException {
        String label = instrument.name().toLowerCase();
        return id + label.substring(0, 2) + label.substring(3, 5) + sdf.format(roundTime(lastTick.getTime(), 10000));
    }

    protected double getPipPrice(double pips) throws JFException {
        return pips * instrument.getPipValue();
    }

    protected long roundTime(long time, long milliseconds) throws JFException {
        return time - time % milliseconds + milliseconds;
    }
}
