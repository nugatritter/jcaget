package de.helmholtzberlin.jcaget;

import gov.aps.jca.CAException;
import gov.aps.jca.CAStatus;
import gov.aps.jca.Channel;
import gov.aps.jca.Context;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.event.GetEvent;
import gov.aps.jca.event.GetListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal caget: reads the current value of one or more EPICS PVs via Channel Access.
 * All PVs are connected and read in parallel; the output keeps the order of the arguments.
 *
 * Usage: java -jar jcaget.jar PVName [PVName ...]
 */
public final class JCaGet {

    private static final double TIMEOUT_SECONDS = 5.0;

    private JCaGet() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar jcaget.jar <PVName> [PVName ...]");
            System.exit(2);
        }
        int failed = 0;
        Context ctxt = null;
        try {
            ctxt = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
            DBR[] results = read(ctxt, args);
            int width = PvFormat.maxLength(args);
            for (int i = 0; i < args.length; i++) {
                if (results[i] == null) {
                    System.err.println(args[i] + "  *** no value (not connected or timeout)");
                    failed++;
                } else {
                    System.out.println(PvFormat.line(args[i], width, results[i]));
                }
            }
        } catch (CAException e) {
            System.err.println("Channel Access error: " + e.getMessage());
            failed++;
        } finally {
            destroy(ctxt);
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Reads the PV and returns one formatted line: name, timestamp, value, status, severity. */
    public static String get(String pvName) throws CAException, TimeoutException {
        Context ctxt = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
        try {
            DBR dbr = read(ctxt, new String[] {pvName})[0];
            if (dbr == null) {
                throw new TimeoutException("PV '" + pvName + "' not reachable");
            }
            return PvFormat.line(pvName, 0, dbr);
        } finally {
            destroy(ctxt);
        }
    }

    /** Connects all PVs at once, then reads all connected ones at once. Entries are null on failure. */
    private static DBR[] read(Context ctxt, String[] pvs) throws CAException {
        Channel[] channels = new Channel[pvs.length];
        for (int i = 0; i < pvs.length; i++) {
            channels[i] = ctxt.createChannel(pvs[i]);
        }
        try {
            ctxt.pendIO(TIMEOUT_SECONDS);
        } catch (TimeoutException ignored) {
            // unconnected channels are detected below via their connection state
        }

        DBR[] results = new DBR[pvs.length];
        CountDownLatch done = new CountDownLatch(countConnected(channels));
        for (int i = 0; i < pvs.length; i++) {
            if (channels[i].getConnectionState() != Channel.CONNECTED) {
                continue;
            }
            final int index = i;
            channels[i].get(PvFormat.timeType(channels[i]), channels[i].getElementCount(), new GetListener() {
                @Override
                public void getCompleted(GetEvent ev) {
                    if (ev.getStatus() == CAStatus.NORMAL) {
                        results[index] = ev.getDBR();
                    }
                    done.countDown();
                }
            });
        }
        ctxt.flushIO();
        try {
            // pendIO does not wait for get callbacks, so wait for them explicitly
            done.await((long) (TIMEOUT_SECONDS * 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return results;
    }

    private static int countConnected(Channel[] channels) {
        int n = 0;
        for (Channel c : channels) {
            if (c.getConnectionState() == Channel.CONNECTED) {
                n++;
            }
        }
        return n;
    }

    static void destroy(Context ctxt) {
        if (ctxt != null) {
            try {
                ctxt.destroy();
            } catch (CAException | IllegalStateException ignored) {
                // nothing left to clean up
            }
        }
    }
}
