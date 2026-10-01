package net.minecraft.bootstrap;

import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraft.hopper.HopperService;

/**
 * Minecraft bootstrap v5 reconstructed from the original launcher bootstrap,
 * modernized for JDK 21.
 *
 * <p>The original bootstrap downloaded launcher.pack.lzma and then used the
 * JDK's removed Pack200 API. This development version simply loads the local
 * launcher.jar produced by the IntelliJ launcher artifact.</p>
 */
public class Bootstrap extends JFrame {
    private static final Font MONOSPACED = new Font("Monospaced", Font.PLAIN, 12);
    private static final String BOOTSTRAP_VERSION = "5-jdk21";

    private final File workDir;
    private final Proxy proxy;
    private final File launcherJar;
    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private final java.net.PasswordAuthentication proxyAuth;
    private final String[] remainderArgs;
    final StringBuilder outputBuffer = new StringBuilder();

    public Bootstrap(File workDir, Proxy proxy, java.net.PasswordAuthentication proxyAuth, String[] remainderArgs) {
        super("Minecraft Launcher");
        this.workDir = workDir;
        this.proxy = proxy;
        this.proxyAuth = proxyAuth;
        this.remainderArgs = remainderArgs;
        this.launcherJar = new File(workDir, "launcher.jar");

        setSize(854, 480);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setFont(MONOSPACED);
        ((DefaultCaret) textArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane);

        setLocationRelativeTo(null);
        setVisible(true);

        println("Bootstrap (" + BOOTSTRAP_VERSION + ")");
        println("Current time is " + DateFormat.getDateTimeInstance(2, 2, Locale.US).format(new Date()));
        println("System.getProperty('os.name') == '" + System.getProperty("os.name") + "'");
        println("System.getProperty('os.version') == '" + System.getProperty("os.version") + "'");
        println("System.getProperty('os.arch') == '" + System.getProperty("os.arch") + "'");
        println("System.getProperty('java.version') == '" + System.getProperty("java.version") + "'");
        println("System.getProperty('java.vendor') == '" + System.getProperty("java.vendor") + "'");
        println("System.getProperty('sun.arch.data.model') == '" + System.getProperty("sun.arch.data.model") + "'");
        println("Work directory == '" + workDir.getAbsolutePath() + "'");
        println("");
    }

    public void execute() {
        println("Using local launcher.jar; bootstrap self-update checks are disabled.");

        if (!launcherJar.isFile()) {
            throw new FatalBootstrapError(
                    "No launcher.jar exists at " + launcherJar.getAbsolutePath()
                            + ". Build the IntelliJ 'launcher-runtime' artifact first.");
        }

        startLauncher(launcherJar);
    }

    public void println(String string) {
        print(string + "\n");
    }

    public void print(String string) {
        System.out.print(string);
        outputBuffer.append(string);

        final Document document = textArea.getDocument();
        final JScrollBar scrollBar = scrollPane.getVerticalScrollBar();
        boolean shouldScroll =
                scrollBar.getValue()
                        + scrollBar.getSize().getHeight()
                        + MONOSPACED.getSize() * 2
                        > scrollBar.getMaximum();

        try {
            document.insertString(document.getLength(), string, null);
        } catch (BadLocationException ignored) {
        }

        if (shouldScroll) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    scrollBar.setValue(Integer.MAX_VALUE);
                }
            });
        }
    }

    /**
     * Loads the built/downloaded launcher in its own class loader.
     *
     * <p>Using the platform class loader as parent prevents the bootstrap's
     * bundled dependency versions from shadowing the dependency versions
     * contained in launcher.jar.</p>
     */
    public void startLauncher(File launcherJar) {
        println("Starting launcher from " + launcherJar.getAbsolutePath());

        try {
            URLClassLoader launcherLoader = new URLClassLoader(
                    new URL[]{launcherJar.toURI().toURL()},
                    ClassLoader.getPlatformClassLoader());

            // Old Log4j 2.x and a few other bundled libraries use the thread
            // context class loader to discover providers/resources. On modern
            // Java the launcher is intentionally isolated from the bootstrap,
            // so make its loader the context loader before any launcher class
            // initializes.
            Thread.currentThread().setContextClassLoader(launcherLoader);
            if (SwingUtilities.isEventDispatchThread()) {
                Thread.currentThread().setContextClassLoader(launcherLoader);
            } else {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        Thread.currentThread().setContextClassLoader(launcherLoader);
                    }
                });
            }

            Class<?> launcherClass = launcherLoader.loadClass("net.minecraft.launcher.Launcher");
            Constructor<?> constructor = launcherClass.getConstructor(
                    JFrame.class,
                    File.class,
                    Proxy.class,
                    java.net.PasswordAuthentication.class,
                    String[].class,
                    Integer.class);

            constructor.newInstance(
                    this,
                    workDir,
                    proxy,
                    proxyAuth,
                    remainderArgs,
                    Integer.valueOf(5));
        } catch (Exception e) {
            throw new FatalBootstrapError("Unable to start launcher: " + e, e);
        }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.net.preferIPv4Stack", "true");

        OptionParser optionParser = new OptionParser();
        optionParser.allowsUnrecognizedOptions();
        optionParser.accepts("help", "Show help").forHelp();
        OptionSpec<Void> offlineOption = optionParser.accepts(
                "offline", "Start the launcher in local/offline mode");
        OptionSpec<String> offlineUserOption = optionParser.accepts(
                "offlineUser", "Offline player name (1-16 letters, numbers or underscores)")
                .withRequiredArg().defaultsTo("Player");

        OptionSpec<String> proxyHostOption = optionParser.accepts("proxyHost", "Optional SOCKS proxy host").withRequiredArg();
        OptionSpec<Integer> proxyPortOption = optionParser.accepts("proxyPort", "Optional SOCKS proxy port")
                .withRequiredArg().defaultsTo("8080").ofType(Integer.class);
        OptionSpec<String> proxyUserOption = optionParser.accepts("proxyUser", "Optional proxy username").withRequiredArg();
        OptionSpec<String> proxyPassOption = optionParser.accepts("proxyPass", "Optional proxy password").withRequiredArg();

        // The original launcher defaulted to the OS .minecraft directory.
        // This project defaults to a local ./minecraft directory so development
        // does not modify the user's normal Minecraft installation.
        OptionSpec<File> workingDirectoryOption = optionParser.accepts("workDir", "Launcher working directory")
                .withRequiredArg().ofType(File.class).defaultsTo(new File("minecraft"));

        OptionSpec<String> nonOptions = optionParser.nonOptions();

        OptionSet optionSet;
        try {
            optionSet = optionParser.parse(args);
        } catch (OptionException e) {
            optionParser.printHelpOn(System.out);
            System.out.println("(to pass arguments to Minecraft directly use '--' followed by those arguments)");
            return;
        }

        if (optionSet.has("help")) {
            optionParser.printHelpOn(System.out);
            return;
        }

        String hostName = optionSet.valueOf(proxyHostOption);
        Proxy proxy = Proxy.NO_PROXY;
        if (hostName != null) {
            try {
                proxy = new Proxy(
                        Proxy.Type.SOCKS,
                        new InetSocketAddress(hostName, optionSet.valueOf(proxyPortOption).intValue()));
            } catch (Exception ignored) {
            }
        }

        String proxyUser = optionSet.valueOf(proxyUserOption);
        String proxyPass = optionSet.valueOf(proxyPassOption);
        java.net.PasswordAuthentication passwordAuthentication = null;
        if (!proxy.equals(Proxy.NO_PROXY) && stringHasValue(proxyUser) && stringHasValue(proxyPass)) {
            passwordAuthentication = new java.net.PasswordAuthentication(proxyUser, proxyPass.toCharArray());
            final java.net.PasswordAuthentication auth = passwordAuthentication;
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    return auth;
                }
            });
        }

        File workingDirectory = optionSet.valueOf(workingDirectoryOption);
        if (workingDirectory.exists() && !workingDirectory.isDirectory()) {
            throw new FatalBootstrapError("Invalid working directory: " + workingDirectory);
        }
        if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
            throw new FatalBootstrapError("Unable to create directory: " + workingDirectory);
        }

        List<String> strings = new ArrayList<>(optionSet.valuesOf(nonOptions));
        if (optionSet.has(offlineOption) || optionSet.has(offlineUserOption)) {
            strings.add("--offline");
            strings.add("--offlineUser");
            strings.add(optionSet.valueOf(offlineUserOption));
        }
        String[] remainderArgs = strings.toArray(new String[strings.size()]);

        Bootstrap frame = new Bootstrap(
                workingDirectory,
                proxy,
                passwordAuthentication,
                remainderArgs);

        try {
            frame.execute();
        } catch (Throwable t) {
            ByteArrayOutputStream stacktrace = new ByteArrayOutputStream();
            t.printStackTrace(new PrintStream(stacktrace));

            StringBuilder report = new StringBuilder();
            report.append(stacktrace)
                    .append("\n\n-- Head --\nStacktrace:\n")
                    .append(stacktrace)
                    .append("\n\n")
                    .append(frame.outputBuffer)
                    .append("\tMinecraft.Bootstrap Version: ")
                    .append(BOOTSTRAP_VERSION);

            try {
                HopperService.submitReport(proxy, report.toString(), "Minecraft.Bootstrap", BOOTSTRAP_VERSION);
            } catch (Throwable ignored) {
            }

            frame.println("FATAL ERROR: " + stacktrace);
            frame.println("\nPlease fix the error and restart.");
        }
    }

    public static boolean stringHasValue(String string) {
        return string != null && !string.trim().isEmpty();
    }
}
