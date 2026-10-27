package de.helmholtzberlin.jcaget;

import gov.aps.jca.CAException;
import gov.aps.jca.CAStatus;
import gov.aps.jca.Channel;
import gov.aps.jca.Context;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.Monitor;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.event.ConnectionEvent;
import gov.aps.jca.event.ConnectionListener;
import gov.aps.jca.event.MonitorEvent;
import gov.aps.jca.event.MonitorListener;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal camonitor: prints every change of one or more EPICS PVs until interrupted (Ctrl+C).
 * PVs that are not connected yet are monitored as soon as they appear.
 *
 * Usage: java -jar jcaget.jar monitor PVName [PVName ...]
 */
public final class JCaMonitor {

    private static final long CONNECT_WARNING_MILLIS = 5000;

    private JCaMonitor() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar jcaget.jar monitor <PVName> [PVName ...]");
            System.exit(2);
        }
        final int width = PvFormat.maxLength(args);
        final Set<String> connected = Collections.synchronizedSet(new HashSet<>());
        try {
            final Context context = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    context.destroy();
                } catch (CAException | IllegalStateException ignored) {
                    // already shut down
                }
            }));

            for (String pv : args) {
                context.createChannel(pv, new ConnectionListener() {
                    private boolean monitoring;

                    @Override
                    public void connectionChanged(ConnectionEvent ev) {
                        Channel channel = (Channel) ev.getSource();
                        if (!ev.isConnected()) {
                            System.err.println(pv + "  *** disconnected");
                            return;
                        }
                        connected.add(pv);
                        if (monitoring) {
                            return; // the monitor survives reconnects
                        }
                        monitoring = true;
                        try {
                            channel.addMonitor(PvFormat.timeType(channel), channel.getElementCount(),
                                    Monitor.VALUE | Monitor.ALARM, new MonitorListener() {
                                        @Override
                                        public void monitorChanged(MonitorEvent mev) {
                                            if (mev.getStatus() != CAStatus.NORMAL) {
                                                System.err.println(pv + "  *** monitor error: " + mev.getStatus());
                                                return;
                                            }
                                            DBR dbr = mev.getDBR();
                                            System.out.println(PvFormat.line(pv, width, dbr));
                                        }
                                    });
                            context.flushIO();
                        } catch (CAException e) {
                            System.err.println(pv + "  *** Channel Access error: " + e.getMessage());
                        }
                    }
                });
            }
            context.flushIO();

            Thread.sleep(CONNECT_WARNING_MILLIS);
            for (String pv : args) {
                if (!connected.contains(pv)) {
                    System.err.println(pv + "  *** not connected, still waiting");
                }
            }
            new CountDownLatch(1).await();
        } catch (CAException e) {
            System.err.println("Channel Access error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
