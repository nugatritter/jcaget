package de.helmholtzberlin.jcaget;

import gov.aps.jca.CAException;
import gov.aps.jca.Channel;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.STS;
import gov.aps.jca.dbr.TIME;
import gov.aps.jca.dbr.TimeStamp;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Shared request type selection and output formatting for get, monitor and info. */
final class PvFormat {

    /** Seconds between 1970-01-01 (Unix) and 1990-01-01 (EPICS epoch). */
    private static final long EPICS_EPOCH_OFFSET = 631152000L;
    private static final int MAX_ARRAY_ITEMS = 10;
    private static final MathContext DOUBLE_PRECISION = new MathContext(12);
    private static final int VALUE_WIDTH = 12;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String BOLD_YELLOW = "[1;33m";
    private static final String BOLD_RED = "[1;31m";
    private static final String BOLD_MAGENTA = "[1;35m";
    private static final String NO_ALARM = "NO_ALARM";
    private static final int STATUS_WIDTH = 12;

    /** Color mode: "auto" (terminal only), "always" or "never". */
    private static volatile String colorMode = "auto";

    private PvFormat() {
    }

    static void setColorMode(String mode) {
        colorMode = mode;
    }

    private static boolean useColor() {
        switch (colorMode) {
            case "always":
                return true;
            case "never":
                return false;
            default:
                return System.getenv("NO_COLOR") == null && isTerminal();
        }
    }

    /** Console.isTerminal() exists since Java 22; before that a non-null console implies a terminal. */
    private static boolean isTerminal() {
        java.io.Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    /** The DBR_TIME_* type matching the channel's native type; enums are requested as their label. */
    static DBRType timeType(Channel channel) throws CAException {
        DBRType t = channel.getFieldType();
        if (t == DBRType.DOUBLE) {
            return DBRType.TIME_DOUBLE;
        } else if (t == DBRType.FLOAT) {
            return DBRType.TIME_FLOAT;
        } else if (t == DBRType.INT) {
            return DBRType.TIME_INT;
        } else if (t == DBRType.SHORT) {
            return DBRType.TIME_SHORT;
        } else if (t == DBRType.BYTE) {
            return DBRType.TIME_BYTE;
        }
        return DBRType.TIME_STRING;
    }

    /** The DBR_CTRL_* type with units, precision, limits or enum labels; null for strings, which have none. */
    static DBRType ctrlType(Channel channel) throws CAException {
        DBRType t = channel.getFieldType();
        if (t == DBRType.DOUBLE) {
            return DBRType.CTRL_DOUBLE;
        } else if (t == DBRType.FLOAT) {
            return DBRType.CTRL_FLOAT;
        } else if (t == DBRType.INT) {
            return DBRType.CTRL_INT;
        } else if (t == DBRType.SHORT) {
            return DBRType.CTRL_SHORT;
        } else if (t == DBRType.BYTE) {
            return DBRType.CTRL_BYTE;
        } else if (t == DBRType.ENUM) {
            return DBRType.CTRL_ENUM;
        }
        return null;
    }

    /** One line: name (padded to nameWidth), timestamp, value, status, severity. */
    static String line(String pv, int nameWidth, DBR dbr) {
        TIME time = (TIME) dbr;
        STS sts = time;
        String status = sts.getStatus().getName();
        String severity = sts.getSeverity().getName();
        String alarm = colorAlarm(pad(status, STATUS_WIDTH) + "  " + severity, status, severity);
        return pad(pv, nameWidth) + "  " + timestamp(time.getTimeStamp()) + "  "
                + pad(value(dbr.getValue()), VALUE_WIDTH) + "  " + alarm;
    }

    /** Colors text by severity if colors are on and status or severity differ from NO_ALARM. */
    static String colorAlarm(String text, String status, String severity) {
        if (useColor() && !(NO_ALARM.equals(status) && NO_ALARM.equals(severity))) {
            return alarmColor(severity) + text + RESET;
        }
        return text;
    }

    /** EPICS convention: MINOR yellow, MAJOR red, INVALID magenta. */
    private static String alarmColor(String severity) {
        switch (severity) {
            case "MAJOR_ALARM":
                return BOLD_RED;
            case "INVALID_ALARM":
                return BOLD_MAGENTA;
            default:
                return BOLD_YELLOW;
        }
    }

    static int maxLength(String... names) {
        int w = 0;
        for (String n : names) {
            w = Math.max(w, n.length());
        }
        return w;
    }

    static String bold(String text) {
        return useColor() ? BOLD + text + RESET : text;
    }

    static String timestamp(TimeStamp ts) {
        return TIME_FORMAT.format(instant(ts));
    }

    static Instant instant(TimeStamp ts) {
        return Instant.ofEpochSecond(EPICS_EPOCH_OFFSET + ts.secPastEpoch(), ts.nsec());
    }

    static String value(Object value) {
        if (value == null || !value.getClass().isArray()) {
            return element(value);
        }
        int n = Array.getLength(value);
        if (n == 1) {
            return element(Array.get(value, 0));
        }
        StringBuilder sb = new StringBuilder("[").append(n).append("] ");
        for (int i = 0; i < Math.min(n, MAX_ARRAY_ITEMS); i++) {
            sb.append(i == 0 ? "" : " ").append(element(Array.get(value, i)));
        }
        return n > MAX_ARRAY_ITEMS ? sb.append(" ...").toString() : sb.toString();
    }

    /** Doubles are rounded to 12 significant digits to hide binary noise like 297.13292559999996. */
    static String element(Object o) {
        if (o instanceof Double && !((Double) o).isNaN() && !((Double) o).isInfinite()) {
            BigDecimal d = new BigDecimal((Double) o).round(DOUBLE_PRECISION).stripTrailingZeros();
            if (d.signum() == 0) {
                return "0";
            }
            return d.precision() - d.scale() > 15 || d.precision() - d.scale() < -4
                    ? d.toString() : d.toPlainString();
        }
        return String.valueOf(o);
    }

    static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
