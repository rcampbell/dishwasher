package dishwasher;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class Monitor extends ApplicationFrame {

    private static final float TEMP_SAFE = 62.8F;

    private static final String TITLE = "Dishwasher Salmon Monitor";
    private static final String START = "Start";
    private static final String STOP = "Stop";

    private static final float TEMP_MIN = 0;
    private static final float TEMP_MAX = 100;

    private static final int SECOND = 1;
    private static final int MINUTE = 60 * SECOND;

    private volatile boolean keepUpdating = true;
    private volatile boolean keepRunning = true;

    private AtomicReference<Thread> serialThread = new AtomicReference<>();
    private String csvFilePath;

    private XYSeriesCollection dataset;
    private XYSeries dataSeries;
    private NumberAxis domainAxis;
    private SerialPort serialPort;

    // Port errors can sometimes be solved by plugging into a different USB port
    public static void main(final String[] args) {
        EventQueue.invokeLater(() -> {
            Monitor monitor = new Monitor(TITLE);
            monitor.pack();
            RefineryUtilities.centerFrameOnScreen(monitor);

            monitor.setVisible(true);

            if (System.getProperty("test", null) != null) {
                monitor.connectTest();
            } else {
                monitor.connect("/dev/cu.usbmodemFA141");
            }

            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    monitor.close();
                }
            });
        });
    }

    public Monitor(final String title) {
        super(title);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JFreeChart chart = createChart();

        final JButton run = new JButton(STOP);
        run.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                String cmd = e.getActionCommand();
                if (STOP.equals(cmd)) {
                    keepUpdating = false;
                    run.setText(START);

                } else {
                    keepUpdating = true;
                    run.setText(STOP);
                }
            }
        });

        final JComboBox<String> combo = new JComboBox<>();
        combo.addItem("10 Seconds");
        combo.addItem("10 Minutes");
        combo.addItem("All");
        combo.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                int selectedIndex = combo.getSelectedIndex();

                if (selectedIndex == 0) {
                    domainAxis.setFixedAutoRange(10 * SECOND);

                } else if (selectedIndex == 1) {
                    domainAxis.setFixedAutoRange(10 * MINUTE);

                } else {
                    domainAxis.setFixedAutoRange(0);

                }
            }
        });

        combo.setSelectedIndex(2);

        this.setMinimumSize(new Dimension(1680, 1050));
        this.add(new ChartPanel(chart), BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout());
        btnPanel.add(run);
        btnPanel.add(combo);
        this.add(btnPanel, BorderLayout.SOUTH);

        // Set up CSV output
        final Instant now = Instant.now();
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss").withLocale(Locale.US).withZone(ZoneId.systemDefault());
        csvFilePath = System.getProperty("user.home") + "/Documents/dishwasher_"
            + formatter.format(now) + ".csvFilePath";
        try {
            Files.write(Paths.get(csvFilePath), "Time,Temperature °C\n".getBytes(), StandardOpenOption.CREATE_NEW);
            System.out.println("Logging to " + csvFilePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private JFreeChart createChart() {
        dataSeries = new XYSeries("probe");
        dataset = new XYSeriesCollection(dataSeries);

        final JFreeChart toReturn = ChartFactory.createXYLineChart(TITLE, "Time", "Temperature °C", dataset, PlotOrientation.VERTICAL, false, true, false);

        final XYPlot plot = toReturn.getXYPlot();
        plot.setRenderer(new CustomXYRenderer());

        domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setAutoRange(true);
        domainAxis.setLowerMargin(0);
        domainAxis.setUpperMargin(0);
        domainAxis.setAutoRangeMinimumSize(10 * SECOND);
        domainAxis.setNumberFormatOverride(new TimeSeriesFormatter());

        ValueAxis range = plot.getRangeAxis();
        range.setRange(TEMP_MIN, TEMP_MAX);
        plot.addRangeMarker(new ValueMarker(TEMP_SAFE, Color.DARK_GRAY, new BasicStroke(1.0f)));

        return toReturn;
    }

    private void connectTest() {

        serialThread.set(new Thread(new InputStreamLoop(csvFilePath, startTestGeneration())));
        serialThread.get().setName("serialThread");
        serialThread.get().start();

    }

    private PipedInputStream startTestGeneration() {
        PipedInputStream pipedInputStream = new PipedInputStream();
        try {
            PipedOutputStream out = new PipedOutputStream(pipedInputStream);


            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Date date = new Date();
                            out.write(("" + (date.getTime() % 70000 / 1000 + 15)).getBytes());
                            out.write("\r\n".getBytes());
                            out.flush();

                            Thread.sleep(911);
                            if (!serialThread.get().isAlive()) {
                                return;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();

                    } finally {
                        try {
                            System.out.println("Stopping test data generation");
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            thread.setName("TestGenerator");
            thread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return pipedInputStream;
    }

    private void connect(final String portName) {

        try {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portName);
            if (portIdentifier.isCurrentlyOwned()) {
                System.out.println("Error: Port is currently in use");
            } else {
                CommPort port = portIdentifier.open(TITLE, 2000);
                if (port instanceof SerialPort) {
                    serialPort = (SerialPort) port;
                    serialPort.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
                    InputStream in = serialPort.getInputStream();
                    serialThread.set(new Thread(new InputStreamLoop(csvFilePath, in)));
                    serialThread.get().setName("serialThread");
                    serialThread.get().start();
                } else {
                    System.out.println("Error: Only serial ports are handled by this example.");
                    port.close();
                }
            }
        } catch (final PortInUseException | NoSuchPortException | IOException | UnsupportedCommOperationException e) {
            throw new RuntimeException(e);
        }
    }

    class InputStreamLoop implements Runnable {

        private String csv;
        private InputStream in;

        private DateTimeFormatter formatter;

        private boolean heads = true;

        InputStreamLoop(final String csv, final InputStream in) {
            this.csv = csv;
            this.in = in;
            this.formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US).withZone(ZoneId.systemDefault());
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int len;
            final StringBuilder segments = new StringBuilder();
            try {
                while ((len = this.in.read(buffer)) > -1 && keepRunning) {
                    final String segment = new String(buffer, 0, len);
                    segments.append(segment);
                    int delimiter = segments.lastIndexOf("\r\n");
                    if (delimiter == -1)
                        continue;
                    if (heads) {
                        segments.setLength(0); // clear out incomplete readings
                        heads = false;
                        continue;
                    }
                    for (String l : segments.substring(0, delimiter).split("\r\n")) {
                        float value = Float.valueOf(l);
                        Instant now = Instant.now();

                        saveIntoFile(value, now);
                        addToChart(value, now);
                    }
                    segments.delete(0, delimiter + 2);
                }

            } catch (final IOException e) {
                throw new RuntimeException(e);
            }

            System.out.println("InputStreamLoop finished");
        }

        private void addToChart(final float value, Instant now) {
            if (keepUpdating) {
                long epochSecond = now.toEpochMilli();

                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        dataSeries.add(epochSecond, value);
                    }
                });
            }
        }

        private void saveIntoFile(float value, Instant now) throws IOException {
            System.out.println((value < TEMP_SAFE ? "⚠" : "✓") + " " + value);
            final String timestamp = formatter.format(now);
            final String line = timestamp + "," + Float.toString(value) + "\n";
            Files.write(Paths.get(csv), line.getBytes(), StandardOpenOption.APPEND);
        }
    }

    private void close() {
        System.out.println("Closing resoucres");

        keepRunning = false;

        try {
            Thread serialThread = this.serialThread.get();


            serialThread.join(2000);

            if (serialThread.isAlive()) {
                //if thread parked and waiting for input that might never come
                serialThread.interrupt();
                //no sense to wait longer than 30 sec
                serialThread.join(30000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (serialPort != null) {
                serialPort.close();
                System.out.println("Serial port closed");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static class TimeSeriesFormatter extends NumberFormat {
        @Override
        public StringBuffer format(double number, StringBuffer toAppendTo, FieldPosition pos) {
            if (toAppendTo == null) {
                toAppendTo = new StringBuffer();
            }

            number %= (3600 * 24 * 1000);

            int hours = (int) number / 3600000;
            int minutes = (int) (number % 3600000) / 60000;
            int secs = (int) (number % 60000) / 1000;
            int millies = (int) number % 1000;

            toAppendTo.append(hours < 10 ? "0" : "").append(hours).append(":").append(
                minutes < 10 ? "0" : "").append(minutes).append(":").append(
                secs < 10 ? "0" : "").append(secs);

            if (millies != 0) {
                String paddedMillies = ("" + (1000 + millies)).substring(1);
                toAppendTo.append(".").append(paddedMillies);
            }

            return toAppendTo;
        }

        @Override
        public StringBuffer format(long number, StringBuffer toAppendTo, FieldPosition pos) {
            return null;
        }

        @Override
        public Number parse(String source, ParsePosition parsePosition) {
            return null;
        }
    }


    private static class CustomXYRenderer extends XYLineAndShapeRenderer {

        public CustomXYRenderer() {
            super(true, false);
        }

        @Override
        public Paint getItemPaint(int row, int col) {

            double value = getPlot().getDataset(0).getYValue(row, col);
            return value >= TEMP_SAFE ? Color.GREEN : Color.RED;

        }

    }
}
