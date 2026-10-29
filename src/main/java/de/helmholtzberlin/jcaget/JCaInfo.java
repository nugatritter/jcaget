package de.helmholtzberlin.jcaget;

import gov.aps.jca.CAException;
import gov.aps.jca.CAStatus;
import gov.aps.jca.Channel;
import gov.aps.jca.Context;
import gov.aps.jca.JCALibrary;
import gov.aps.jca.TimeoutException;
import gov.aps.jca.dbr.CTRL;
import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_Enum;
import gov.aps.jca.dbr.GR;
import gov.aps.jca.dbr.LABELS;
import gov.aps.jca.dbr.PRECISION;
import gov.aps.jca.dbr.TIME;
import gov.aps.jca.dbr.TimeStamp;
import gov.aps.jca.event.GetEvent;
import gov.aps.jca.event.GetListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal cainfo: shows everything Channel Access provides about one or more EPICS PVs:
 * value with units, alarm state, timestamp, read/write access, enum states, display/control/alarm limits,
 * precision, native type, element count, server and, for IOC records, record type and description.
 * All PVs are connected and read in parallel; the output keeps the order of the arguments.
 * With --readonly, only PVs the user may read but not write are shown; with --invalid, only PVs with
 * severity INVALID_ALARM. Both together show only PVs that meet both conditions.
 *
 * Usage: java -jar jcaget.jar info [--readonly] [--invalid] PVName [PVName ...]
 */
public final class JCaInfo {

    private static final double TIMEOUT_SECONDS = 5.0;
    /** Short wait for the record fields RTYP and DESC: servers without records never answer for them. */
    private static final double FIELD_TIMEOUT_SECONDS = 1.0;
    private static final int LABEL_WIDTH = 15;
    private static final int LINE_WIDTH = 80;
    private static final String INDENT = "    ";
    private static final String NO_ALARM = "NO_ALARM";
    private static final String INVALID_ALARM = "INVALID_ALARM";
    private static final String READ_ONLY_OPTION = "--readonly";
    private static final String INVALID_OPTION = "--invalid";

    /** Reads per PV, in this order: value with timestamp, metadata, record type, description. */
    private static final int TIME = 0;
    private static final int CTRL_INFO = 1;
    private static final int RTYP = 2;
    private static final int DESC = 3;
    private static final int READS = 4;

    private JCaInfo() {
    }

    public static void main(String[] args) {
        boolean onlyReadOnly = false;
        boolean onlyInvalid = false;
        int options = 0;
        for (; options < args.length && args[options].startsWith("--"); options++) {
            if (args[options].equals(READ_ONLY_OPTION)) {
                onlyReadOnly = true;
            } else if (args[options].equals(INVALID_OPTION)) {
                onlyInvalid = true;
            } else {
                System.err.println("Unknown option: " + args[options]);
                usage();
            }
        }
        args = Arrays.copyOfRange(args, options, args.length);
        if (args.length < 1) {
            usage();
        }
        int failed = 0;
        Context ctxt = null;
        try {
            ctxt = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
            String[] blocks = read(ctxt, args, onlyReadOnly, onlyInvalid);
            boolean first = true;
            for (int i = 0; i < args.length; i++) {
                if (blocks[i] == null) {
                    System.err.println(args[i] + "  *** not connected (timeout)");
                    failed++;
                } else if (!blocks[i].isEmpty()) {
                    if (!first) {
                        System.out.println();
                    }
                    System.out.println(blocks[i]);
                    first = false;
                }
            }
        } catch (CAException e) {
            System.err.println("Channel Access error: " + e.getMessage());
            failed++;
        } finally {
            JCaGet.destroy(ctxt);
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void usage() {
        System.err.println("Usage: java -jar jcaget.jar info [" + READ_ONLY_OPTION + "] [" + INVALID_OPTION
                + "] <PVName> [PVName ...]");
        System.exit(2);
    }

    /** Reads everything available about the PV and returns it as a multi-line block like the command line tool. */
    public static String info(String pvName) throws CAException, TimeoutException {
        Context ctxt = JCALibrary.getInstance().createContext(JCALibrary.CHANNEL_ACCESS_JAVA);
        try {
            String block = read(ctxt, new String[] {pvName}, false, false)[0];
            if (block == null) {
                throw new TimeoutException("PV '" + pvName + "' not reachable");
            }
            return block;
        } finally {
            JCaGet.destroy(ctxt);
        }
    }

    /**
     * Connects all PVs, then the fields of their records, then reads everything at once.
     * Null if not connected, empty if left out by a filter: onlyReadOnly and the PV is not read only,
     * or onlyInvalid and the severity is not INVALID_ALARM (or unknown, because the value cannot be read).
     */
    private static String[] read(Context ctxt, String[] pvs, boolean onlyReadOnly, boolean onlyInvalid)
            throws CAException {
        Channel[] channels = new Channel[pvs.length];
        for (int i = 0; i < pvs.length; i++) {
            channels[i] = ctxt.createChannel(pvs[i]);
        }
        pendIO(ctxt, TIMEOUT_SECONDS);

        Channel[] sources = new Channel[pvs.length * READS];
        DBRType[] types = new DBRType[sources.length];
        boolean anyConnected = false;
        for (int i = 0; i < pvs.length; i++) {
            if (channels[i].getConnectionState() != Channel.CONNECTED || onlyReadOnly && !readOnly(channels[i])) {
                continue;
            }
            anyConnected = true;
            int r = i * READS;
            String record = recordName(pvs[i]);
            sources[r + TIME] = channels[i];
            types[r + TIME] = PvFormat.timeType(channels[i]);
            sources[r + CTRL_INFO] = channels[i];
            types[r + CTRL_INFO] = PvFormat.ctrlType(channels[i]);
            sources[r + RTYP] = ctxt.createChannel(record + ".RTYP");
            types[r + RTYP] = DBRType.STRING;
            sources[r + DESC] = ctxt.createChannel(record + ".DESC");
            types[r + DESC] = DBRType.STRING;
        }
        if (anyConnected) {
            pendIO(ctxt, FIELD_TIMEOUT_SECONDS);
        }

        DBR[] results = get(ctxt, sources, types);
        String[] blocks = new String[pvs.length];
        for (int i = 0; i < pvs.length; i++) {
            if (channels[i].getConnectionState() != Channel.CONNECTED) {
                continue;
            }
            DBR[] r = Arrays.copyOfRange(results, i * READS, (i + 1) * READS);
            boolean shown = (!onlyReadOnly || readOnly(channels[i])) && (!onlyInvalid || invalid(r[TIME]));
            blocks[i] = shown ? format(pvs[i], channels[i], r) : "";
        }
        return blocks;
    }

    private static void pendIO(Context ctxt, double timeout) throws CAException {
        try {
            ctxt.pendIO(timeout);
        } catch (TimeoutException ignored) {
            // unconnected channels are detected via their connection state
        }
    }

    /** Issues all readable gets at once and waits for them. Entries are null if not read. */
    private static DBR[] get(Context ctxt, Channel[] sources, DBRType[] types) throws CAException {
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] != null && types[i] != null
                    && sources[i].getConnectionState() == Channel.CONNECTED && sources[i].getReadAccess()) {
                todo.add(i);
            }
        }
        DBR[] results = new DBR[sources.length];
        CountDownLatch done = new CountDownLatch(todo.size());
        for (int i : todo) {
            try {
                sources[i].get(types[i], sources[i].getElementCount(), new GetListener() {
                    @Override
                    public void getCompleted(GetEvent ev) {
                        if (ev.getStatus() == CAStatus.NORMAL) {
                            results[i] = ev.getDBR();
                        }
                        done.countDown();
                    }
                });
            } catch (CAException | IllegalStateException e) {
                done.countDown(); // disconnected meanwhile
            }
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

    /**
     * Header "PV  (record type)  "description"", then one aligned "Label: value" line per item.
     * Items the server does not provide or that are not configured (empty limits) are left out.
     */
    private static String format(String pv, Channel channel, DBR[] r) {
        List<String> lines = new ArrayList<>();
        lines.add(header(pv, r[RTYP], r[DESC]));
        DBR meta = r[CTRL_INFO];
        String units = meta instanceof GR && ((GR) meta).getUnits() != null ? ((GR) meta).getUnits().trim() : "";

        String status = null;
        String severity = null;
        if (r[TIME] == null) {
            add(lines, "Value", channel.getReadAccess() ? "*** read failed" : "*** no read access");
        } else {
            TIME time = (TIME) r[TIME];
            status = time.getStatus().getName();
            severity = time.getSeverity().getName();
            add(lines, "Value", withUnits(PvFormat.value(r[TIME].getValue()), units));
            String alarm = NO_ALARM.equals(status) && NO_ALARM.equals(severity) ? NO_ALARM : status + "  " + severity;
            add(lines, "Alarm", PvFormat.colorAlarm(alarm, status, severity));
            add(lines, "Timestamp", timestamp(time.getTimeStamp()));
        }
        add(lines, "Access", access(channel));

        if (meta instanceof LABELS) {
            addWrapped(lines, "States", states(((LABELS) meta).getLabels(), ((DBR_Enum) meta).getEnumValue()[0]));
        }
        if (meta instanceof GR) {
            GR gr = (GR) meta;
            addRange(lines, "Display range", gr.getLowerDispLimit(), gr.getUpperDispLimit(), units);
            if (meta instanceof CTRL) {
                addRange(lines, "Control range", ((CTRL) meta).getLowerCtrlLimit(), ((CTRL) meta).getUpperCtrlLimit(),
                        units);
            }
            String limits = alarmLimits(gr, status, severity);
            if (limits != null) {
                add(lines, "Alarm limits", limits);
            }
            if (meta instanceof PRECISION) {
                add(lines, "Precision", String.valueOf(((PRECISION) meta).getPrecision()));
            }
        }

        int count = channel.getElementCount();
        add(lines, "Type", typeName(channel.getFieldType()) + (count == 1 ? "" : ", " + count + " elements"));
        add(lines, "Host", channel.getHostName());
        return String.join(System.lineSeparator(), lines);
    }

    /** PV name in bold, then record type and description if the PV belongs to an IOC record. */
    private static String header(String pv, DBR rtyp, DBR desc) {
        StringBuilder sb = new StringBuilder(PvFormat.bold(pv));
        if (rtyp != null) {
            sb.append("  (").append(PvFormat.value(rtyp.getValue())).append(')');
        }
        String description = desc == null ? "" : PvFormat.value(desc.getValue()).trim();
        if (!description.isEmpty()) {
            sb.append("  \"").append(description).append('"');
        }
        return sb.toString();
    }

    private static void add(List<String> lines, String label, String value) {
        String head = label.isEmpty() ? "" : label + ":";
        lines.add((INDENT + PvFormat.pad(head, LABEL_WIDTH) + value).stripTrailing());
    }

    /** Adds the items separated by two spaces, continuing on further lines below the value column if too long. */
    private static void addWrapped(List<String> lines, String label, List<String> items) {
        StringBuilder line = new StringBuilder();
        for (String item : items) {
            if (line.length() > 0 && INDENT.length() + LABEL_WIDTH + line.length() + 2 + item.length() > LINE_WIDTH) {
                add(lines, label, line.toString());
                label = "";
                line.setLength(0);
            }
            line.append(line.length() == 0 ? "" : "  ").append(item);
        }
        add(lines, label, line.toString());
    }

    /** A range with both ends 0 (or equal) is not configured and left out. */
    private static void addRange(List<String> lines, String label, Number low, Number high, String units) {
        if (low != null && high != null && low.doubleValue() != high.doubleValue()) {
            add(lines, label, withUnits(PvFormat.element(low) + " .. " + PvFormat.element(high), units));
        }
    }

    private static String withUnits(String value, String units) {
        return units.isEmpty() ? value : value + " " + units;
    }

    /** "0=Off  [1=On]": all enum states with the current one in brackets. */
    private static List<String> states(String[] labels, int current) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            String item = i + "=" + labels[i];
            items.add(i == current ? "[" + item + "]" : item);
        }
        return items;
    }

    /**
     * The set alarm limits (unset ones are NaN, or all 0 for integer types); the limit that causes
     * the current alarm is highlighted. Null if no limit is set.
     */
    private static String alarmLimits(GR gr, String status, String severity) {
        String[] names = {"LOLO", "LOW", "HIGH", "HIHI"};
        Number[] limits = {gr.getLowerAlarmLimit(), gr.getLowerWarningLimit(),
                gr.getUpperWarningLimit(), gr.getUpperAlarmLimit()};
        StringBuilder sb = new StringBuilder();
        boolean anySet = false;
        for (int i = 0; i < names.length; i++) {
            if (limits[i] == null || Double.isNaN(limits[i].doubleValue())) {
                continue;
            }
            anySet |= limits[i].doubleValue() != 0;
            String entry = names[i] + " " + PvFormat.element(limits[i]);
            if ((names[i] + "_ALARM").equals(status)) {
                entry = PvFormat.colorAlarm(entry, status, severity);
            }
            sb.append(sb.length() == 0 ? "" : "  ").append(entry);
        }
        return anySet ? sb.toString() : null;
    }

    /** Local time with its age, or "undefined" for a record that was never processed. */
    private static String timestamp(TimeStamp ts) {
        if (ts == null || ts.secPastEpoch() == 0 && ts.nsec() == 0) {
            return "undefined";
        }
        long seconds = Duration.between(PvFormat.instant(ts), Instant.now()).getSeconds();
        return seconds < 0 ? PvFormat.timestamp(ts) : PvFormat.timestamp(ts) + "  (" + age(seconds) + " ago)";
    }

    private static String age(long seconds) {
        if (seconds < 120) {
            return seconds + " s";
        } else if (seconds < 120 * 60) {
            return seconds / 60 + " min";
        } else if (seconds < 48 * 3600) {
            return seconds / 3600 + " h";
        }
        return seconds / 86400 + " d";
    }

    private static String access(Channel channel) {
        boolean read = channel.getReadAccess();
        boolean write = channel.getWriteAccess();
        if (read && write) {
            return "read, write";
        }
        return read ? "read only" : write ? "write only" : "none";
    }

    /** True if the user may read but not write the PV, shown as "read only" under Access. */
    private static boolean readOnly(Channel channel) {
        return channel.getReadAccess() && !channel.getWriteAccess();
    }

    /** True if the value was read and has severity INVALID_ALARM. */
    private static boolean invalid(DBR time) {
        return time != null && INVALID_ALARM.equals(((TIME) time).getSeverity().getName());
    }

    /** EPICS name of the native field type as shown by cainfo; JCA calls DBF_LONG "INT" and DBF_CHAR "BYTE". */
    private static String typeName(DBRType type) {
        if (type == DBRType.INT) {
            return "DBF_LONG";
        } else if (type == DBRType.BYTE) {
            return "DBF_CHAR";
        }
        return type.getName().replace("DBR_", "DBF_");
    }

    /** The record name: the PV name without a field suffix like ".VAL". */
    private static String recordName(String pv) {
        int dot = pv.indexOf('.');
        return dot < 0 ? pv : pv.substring(0, dot);
    }
}
