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
import org.jfree.chart.renderer.xy.DefaultXYItemRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Monitor extends ApplicationFrame {

    private static final float USDA_FISH = 62.8F;

    private static final String TITLE = "Dishwasher Salmon Monitor";
    private static final String START = "Start";
    private static final String STOP = "Stop";

    private static final float TEMP_MIN = 0;
    private static final float TEMP_MAX = 100;
    public static final int TEMP_SAFE = 63;

    private static final int SECOND = 1;
    private static final int MINUTE = 60 * SECOND;

    private volatile boolean keepUpdating = true;

    private AtomicBoolean keepRunning = new AtomicBoolean(true);
    private AtomicReference<Thread> serialThread = new AtomicReference<>();
    private String csv;

    private XYSeriesCollection dataset;
    private XYSeries dataSeries;
    private NumberAxis domainAxis;
    private InputStream testInputStream;


    public Monitor(final String title) {
        super(title);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        if (System.getProperty("test", null) != null) {
            initTest();
        }

        JFreeChart chart = createChart();

        final JButton run = new JButton(STOP);
        run.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                String cmd = e.getActionCommand();
                if (STOP.equals(cmd)) {
                    keepRunning.set(false);
                    keepUpdating = false;
                    run.setText(START);
                } else {
                    keepRunning.set(true);
                    keepUpdating = false;
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
        final DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern("yyyy_MM_dd_HH_mm_ss")
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault());
        csv = System.getProperty("user.home") + "/Documents/dishwasher_" + formatter.format(now)
            + ".csv";
        try {
            Files.write(Paths.get(csv), "Time,Temperature °C\n".getBytes(), StandardOpenOption.CREATE_NEW);
            System.out.println("Logging to " + csv);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //        // Set up the timer for the live chart
        //        timer = new Timer(SECOND, new ActionListener() {
        //
        //            long now;
        //            float value;
        //            float[] data = new float[1];
        //
        //            @Override
        //            public void actionPerformed(ActionEvent e) {
        //                value = Float.intBitsToFloat(probe.get());
        //                now = System.currentTimeMillis();
        //                if (count < BUFFER) {
        //                    dataset.addValue(0, count, value);
        //                } else {
        //                    data[0] = value;
        //                    dataset.advanceTime();
        //                    dataset.appendData(data);
        //                }
        //                count++;
        //            }
        //        });
    }

    private void initTest() {
        try {

            testInputStream = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream((PipedInputStream) testInputStream);


            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {

                        try {
                            if (keepUpdating) {
                                Date date = new Date();
                                out.write(("" + (date.getTime() % 70000 / 1000 + 15)).getBytes());
                                out.write("\r\n".getBytes());
                                out.flush();
                            }
                            Thread.sleep(900);
                        } catch (Exception e) {
                            e.printStackTrace();
                            try {
                                out.close();
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                            return;
                        }
                    }
                }
            });
            thread.setName("TestGenerator");
            thread.start();

        } catch (Exception e) {
            e.printStackTrace();
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

    private void connect(final String portName) {
        if (testInputStream != null) {
            serialThread.set(new Thread(new SerialReader(csv, null, testInputStream, keepRunning, this)));
            serialThread.get().setName("serialThread");
            serialThread.get().start();
            return;
        }

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
                    serialThread.set(new Thread(new SerialReader(csv, port, in, keepRunning, this)));
                    serialThread.get().setName("serialThread");
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
        private AtomicBoolean keepRunning;
        private DateTimeFormatter formatter;
        private boolean heads = true;

        private final Monitor monitor;

        public SerialReader(final String csv, final CommPort port, final InputStream in, final AtomicBoolean keepRunning, Monitor monitor) {
            this.csv = csv;
            this.port = port;
            this.in = in;
            this.keepRunning = keepRunning;
            this.monitor = monitor;
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
                        System.out.println((value < USDA_FISH ? "⚠" : "✓") + " " + value);
                        Instant now = Instant.now();
                        final String timestamp = formatter.format(now);
                        final String line = timestamp + "," + Float.toString(value) + "\n";
                        Files.write(Paths.get(csv), line.getBytes(), StandardOpenOption.APPEND);


                        if (monitor.keepUpdating) {
                            long epochSecond = now.getEpochSecond();

                            SwingUtilities.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    monitor.dataSeries.add(epochSecond, value);
                                }
                            });
                        }
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

    @Override
    public void windowClosing(WindowEvent event) {
        try {
            keepUpdating = false;
            keepRunning.set(false);

            if (testInputStream != null) {
                testInputStream.close();
            }

            serialThread.get().interrupt();
            try {
                serialThread.get().join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {}

        super.windowClosing(event);

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

    private static class TimeSeriesFormatter extends NumberFormat {
        @Override
        public StringBuffer format(double number, StringBuffer toAppendTo, FieldPosition pos) {
            if (toAppendTo == null) {
                toAppendTo = new StringBuffer();
            }

            number %= (3600 * 24);

            int hours = (int) number / 3600;
            int minutes = (int) (number % 3600) / 60;
            int secs = (int) number % 60;

            toAppendTo.append(hours < 10 ? "0" : "").append(hours).append(":").append(
                minutes < 10 ? "0" : "").append(minutes).append(":").append(
                secs < 10 ? "0" : "").append(secs);

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
