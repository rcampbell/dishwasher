package dishwasher;

import gnu.io.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.DynamicTimeSeriesCollection;
import org.jfree.data.time.Second;
import org.jfree.data.xy.XYDataset;
import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Monitor extends ApplicationFrame {

    private static final float USDA_FISH = 62.8F;

    private static final String TITLE = "Dishwasher Salmon Monitor";
    private static final String START = "Start";
    private static final String STOP = "Stop";
    private static final float MIN = 0;
    private static final float MAX = 100;
    private static final int BUFFER = 2 * 60;
    private static final int SECOND = 1000;
    private static final int MINUTE = 60 * SECOND;
    private Timer timer;
    private int count = 0;
    private AtomicInteger probe = new AtomicInteger();
    private AtomicBoolean keepRunning = new AtomicBoolean(true);
    private AtomicReference<Thread> serialThread = new AtomicReference<>();
    private String csv;

    public Monitor(final String title) {
        super(title);
        final DynamicTimeSeriesCollection dataset =
                new DynamicTimeSeriesCollection(1, BUFFER, new Second());
        dataset.setTimeBase(new Second(0, 0, 0, 1, 1, 2017));
        dataset.addSeries(new float[0], 0, "probe data");
        JFreeChart chart = createChart(dataset);

        final JButton run = new JButton(START);
        run.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                String cmd = e.getActionCommand();
                if (STOP.equals(cmd)) {
                    keepRunning.set(false);
                    timer.stop();
                    run.setText(START);
                } else {
                    keepRunning.set(true);
                    timer.start();
                    run.setText(STOP);
                }
            }
        });

        final JComboBox<String> combo = new JComboBox<>();
        combo.addItem("Second");
        combo.addItem("Minute");
        combo.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                if ("Second".equals(combo.getSelectedItem())) {
                    timer.setDelay(SECOND);
                } else {
                    timer.setDelay(MINUTE);
                }
            }
        });

        this.setMinimumSize(new Dimension(1680, 1050));
        this.add(new ChartPanel(chart), BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout());
        btnPanel.add(run);
        btnPanel.add(combo);
        this.add(btnPanel, BorderLayout.SOUTH);

        // Set up CSV output
        final Instant now = Instant.now();
        final DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern("yyyy_MM_dd_HH_mm_ss")
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault());
        csv = "/Users/rrc/Documents/dishwasher_" + formatter.format(now) + ".csv";
        try {
            Files.write(Paths.get(csv), "Time,Temperature °C\n".getBytes(), StandardOpenOption.CREATE_NEW);
            System.out.println("Logging to " + csv);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Set up the timer for the live chart
        timer = new Timer(SECOND, new ActionListener() {

            long now;
            float value;
            float[] data = new float[1];

            @Override
            public void actionPerformed(ActionEvent e) {
                value = Float.intBitsToFloat(probe.get());
                now = System.currentTimeMillis();
                if (count < BUFFER) {
                    dataset.addValue(0, count, value);
                } else {
                    data[0] = value;
                    dataset.advanceTime();
                    dataset.appendData(data);
                }
                count++;
            }
        });
    }

    private JFreeChart createChart(final XYDataset dataset) {
        final JFreeChart result = ChartFactory.createTimeSeriesChart(
                TITLE, "hh:mm:ss", "Temperature °C", dataset, false, true, false);
        final XYPlot plot = result.getXYPlot();
        //plot.setRangePannable(true); ??
        ValueAxis domain = plot.getDomainAxis();
        domain.setAutoRange(true);
        ValueAxis range = plot.getRangeAxis();
        range.setRange(MIN, MAX);
        return result;
    }

    private void connect(final String portName) {
        try {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portName);
            if (portIdentifier.isCurrentlyOwned()) {
                System.out.println("Error: Port is currently in use");
            } else {
                CommPort port = portIdentifier.open(TITLE, 2000);
                if (port instanceof SerialPort) {
                    SerialPort serialPort = (SerialPort) port;
                    serialPort.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
                    InputStream in = serialPort.getInputStream();
                    serialThread.set(new Thread(new SerialReader(csv, port, in, probe, keepRunning)));
                    serialThread.get().start();
                } else {
                    System.out.println("Error: Only serial ports are handled by this example.");
                }
            }
        } catch (final PortInUseException | NoSuchPortException | IOException | UnsupportedCommOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static class SerialReader implements Runnable {

        private String csv;
        private CommPort port;
        private InputStream in;
        private AtomicInteger probe;
        private AtomicBoolean keepRunning;
        private DateTimeFormatter formatter;
        private boolean heads = true;

        public SerialReader(final String csv, final CommPort port, final InputStream in, final AtomicInteger probe, final AtomicBoolean keepRunning) {
            this.csv = csv;
            this.port = port;
            this.in = in;
            this.probe = probe;
            this.keepRunning = keepRunning;
            this.formatter = DateTimeFormatter
                    .ofPattern("HH:mm:ss")
                    .withLocale(Locale.US)
                    .withZone(ZoneId.systemDefault());
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int len;
            final StringBuilder segments = new StringBuilder();
            try {
                while (((len = this.in.read(buffer)) > -1) && (keepRunning.get())) {
                    final String segment = new String(buffer, 0, len);
                    segments.append(segment);
                    int delimiter = segments.lastIndexOf("\r\n");
                    if (delimiter == -1) continue;
                    if (heads) {
                        segments.setLength(0); // clear out incomplete readings
                        heads = false;
                        continue;
                    }
                    for (String l : segments.substring(0, delimiter).split("\r\n")) {
                        float value = Float.valueOf(l);
                        probe.set(Float.floatToIntBits(value));
                        System.out.println((value < USDA_FISH ? "⚠" : "✓") + " " + value);
                        final String timestamp = formatter.format(Instant.now());
                        final String line = timestamp + "," + Float.toString(value) + "\n";
                        Files.write(Paths.get(csv), line.getBytes(), StandardOpenOption.APPEND);
                    }
                    segments.delete(0, delimiter + 2);
                }
                port.close();
                System.out.println("Closed serial port.");
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Port errors can sometimes be solved by plugging into a different USB port
    public static void main(final String[] args) {
        EventQueue.invokeLater(() -> {
            Monitor demo = new Monitor(TITLE);
            demo.pack();
            RefineryUtilities.centerFrameOnScreen(demo);
            demo.setVisible(true);
            demo.connect("/dev/cu.usbmodemFA141");
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    demo.keepRunning.set(false);
                    try {
                        mainThread.join();
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        });
    }
}