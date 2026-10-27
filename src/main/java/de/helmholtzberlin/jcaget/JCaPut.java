package de.helmholtzberlin.jcaget;

import gov.aps.jca.CAException;
import gov.aps.jca.Channel;
import gov.aps.jca.Context;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.TimeoutException;

import java.util.Arrays;

/**
 * Minimal caput: writes a value to an EPICS PV via Channel Access.
 *
 * Usage: java -jar jcaget.jar put PVName Value [Value ...]
 */
public final class JCaPut {

    private static final double TIMEOUT_SECONDS = 5.0;

    private JCaPut() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar jcaget.jar put <PVName> <Value> [Value ...]");
            System.exit(2);
        }
        String pv = args[0];
        String[] values = Arrays.copyOfRange(args, 1, args.length);
        try {
            put(pv, values);
            System.out.println(pv + " " + String.join(" ", values));
        } catch (TimeoutException e) {
            System.err.println("Timeout: PV '" + pv + "' not reachable");
            System.exit(1);
        } catch (CAException | RuntimeException e) {
            System.err.println("Channel Access error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Writes the given values (as strings, converted by the IOC's native type) to the PV. */
    public static void put(String pvName, String... values) throws CAException, TimeoutException {
        Context ctxt = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
        try {
            Channel channel = ctxt.createChannel(pvName);
            ctxt.pendIO(TIMEOUT_SECONDS);
            if (values.length == 1) {
                channel.put(values[0]);
            } else {
                channel.put(values);
            }
            ctxt.pendIO(TIMEOUT_SECONDS);
        } finally {
            ctxt.destroy();
        }
    }
}
