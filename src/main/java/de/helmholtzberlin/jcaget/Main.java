package de.helmholtzberlin.jcaget;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of the JAR: dispatches to get, put or monitor.
 *
 * Usage: java -jar jcaget.jar [get|put|monitor] PVName [PVName ...] | put PVName Value [Value ...]
 * Without a command, get is assumed. Option --color=auto|always|never (default auto) before the command.
 */
public final class Main {

    /** Strong reference, otherwise the logger level may be garbage collected. */
    private static final Logger CAJ_LOGGER = Logger.getLogger("com.cosylab.epics.caj");

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith("--color=")) {
            PvFormat.setColorMode(args[0].substring("--color=".length()));
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length == 0) {
            System.err.println("Usage: java -jar jcaget.jar [--color=auto|always|never] [get] <PVName> [PVName ...]\n"
                    + "       java -jar jcaget.jar monitor <PVName> [PVName ...]\n"
                    + "       java -jar jcaget.jar put <PVName> <Value> [Value ...]");
            System.exit(2);
        }
        CAJ_LOGGER.setLevel(Level.WARNING); // hide beacon INFO messages
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "get":
                JCaGet.main(rest);
                break;
            case "monitor":
                JCaMonitor.main(rest);
                break;
            case "put":
                JCaPut.main(rest);
                break;
            default:
                JCaGet.main(args);
        }
    }
}
