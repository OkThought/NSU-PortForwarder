package ru.nsu.ccfit.bogush.net.forwarder;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Iterator;

import static java.nio.channels.SelectionKey.*;

public class PortForwarder {
    private static void usage() {
        System.out.print(
                "Usage\n\n\t" +

                        "<program_name> lport rhost rport\n\n" +

                        "Description\n\n\t" +

                        "A port forwarder is a program which lives between client\n\t" +
                        "connecting to some internet service and the service itself.\n\t" +
                        "It takes incoming bytes on from local tcp connection and\n\t" +
                        "forwards them to the requested service connection pretending\n\t" +
                        "the client\n\n" +

                        "Arguments\n\n\t" +

                        "lport - the port on which the forwarder works.\n\t" +
                        "It is a port which the client passes to address line \n\t" +
                        "(e.g. 'http://google.com:12345', the lport is 12345)\n\n\t" +

                        "rhost and rport together describe the requested service.\n\n");
    }

    private static final int REQUIRED_NUMBER_OF_ARGUMENTS = 3;
    private static final int LPORT_ARGUMENT_INDEX = 0;
    private static final int RHOST_ARGUMENT_INDEX = 1;
    private static final int RPORT_ARGUMENT_INDEX = 2;
    private static final int EXIT_FAILURE = 1;
    private static final int BUFF_SIZE = 8 << 10; // 8 kilobytes


    public static void main(String[] args) {
        if (args.length != REQUIRED_NUMBER_OF_ARGUMENTS) {
            usage();
            System.exit(EXIT_FAILURE);
        }

        int lport = 0;

        try {
            lport = Integer.parseInt(args[LPORT_ARGUMENT_INDEX]);
        } catch (NumberFormatException e) {
            System.err.println("lport must be a number");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        String rhostString = args[RHOST_ARGUMENT_INDEX];
        InetAddress rhost = null;
        try {
            rhost = InetAddress.getByName(rhostString);
        } catch (UnknownHostException e) {
            System.err.println("couldn't resolve rhost '" + rhostString + "'");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        int rport = 0;

        try {
            rport = Integer.parseInt(args[RPORT_ARGUMENT_INDEX]);
        } catch (NumberFormatException e) {
            System.err.println("rport must be a number");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        PortForwarder portForwarder = null;

        try {
            portForwarder = new PortForwarder(lport, rhost, rport);
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: 12/10/17 correctly handle exception
            System.exit(EXIT_FAILURE);
        }

        portForwarder.start();
    }
    private final Selector selector;
    private final ServerSocketChannel serverSocketChannel;
    private final InetSocketAddress localSocketAddress;
    private final InetSocketAddress remoteSocketAddress;
    private final String rhost;

    private PortForwarder(int lport, InetAddress rhost, int rport) throws IOException {
        System.out.format("Configure Port Forwarder with params:\n\t" +
                "lport: %s\n\t" +
                "rhost: %s\n\t" +
                "rport: %s\n\n", lport, rhost, rport);

        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        localSocketAddress = new InetSocketAddress(lport);
        remoteSocketAddress = new InetSocketAddress(rhost, rport);
        this.rhost = rhost.getHostName();

        System.out.format("Configured Port Forwarder\n\t" +
                "local: %s\n\tremote: %s\n\n", localSocketAddress, remoteSocketAddress);

        if (remoteSocketAddress.isUnresolved()) {
            System.err.println("Remote socket address is unresolved :(");
        }
    }

    private void start() {
        try {
            System.out.println("Starting Port Forwarder...");
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(localSocketAddress);
            serverSocketChannel.register(selector, OP_ACCEPT, serverSocketChannel);
            System.out.format("Started Port Forwarder:\n\t" +
                    "local: %s\n\tremote: %s\n\n", serverSocketChannel.getLocalAddress(), remoteSocketAddress);
        } catch (IOException e) {
            System.err.println("Start failed :(");
            e.printStackTrace();
            return;
        }

        /*
         * after we've prepared our  port forwarder  to start
         * add line '127.0.0.1 <rhost>' to /etc/hosts to  let
         * the client start using the forwarder without doing
         * that ^ himself.
         */
        addHostEntry();

        try {
            System.out.println("Start main loop\n\n");
            loop();
        } catch (IOException e) {
            System.err.println("Loop failed :(");
            e.printStackTrace();
        }
    }

    private boolean hostEntryAdded = false;
    private static final String HOSTS_FILE_NAME = "/etc/hosts";
    private static final Path HOSTS_FILE_PATH = Paths.get(HOSTS_FILE_NAME);
    private Path hostsTmpPath;

    private void addHostEntry() {
        String hostEntry = "127.0.0.1 " + rhost;
        try {
            File tmp = File.createTempFile(HOSTS_FILE_NAME, ".tmp");
            hostsTmpPath = tmp.toPath();
            Files.copy(HOSTS_FILE_PATH, hostsTmpPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try (FileWriter fileWriter = new FileWriter(HOSTS_FILE_NAME, true)) {
            System.out.format("Appending '%s' to %s...\t", hostEntry, HOSTS_FILE_NAME);
            BufferedWriter writer = new BufferedWriter(fileWriter);
            writer.write(hostEntry + System.lineSeparator());
            writer.close();
            System.out.println("done.");
            hostEntryAdded = true;
        } catch (IOException e) {
            System.err.format("Warning: Couldn't add host entry '%s': %s\n", hostEntry, e.getMessage());
            System.err.println("If this was due to lack of permissions you may rerun Port Forwarder\n" +
                    "as a super user (e.g. using sudo command, see man page sudo(1)).");
            System.err.println("Or you can do it manually before actually using Port Forwarder");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::removeHostEntry, "HostEntryRemover"));
    }

    private void removeHostEntry() {
        if (!hostEntryAdded) return;

        try {
            Files.copy(hostsTmpPath, HOSTS_FILE_PATH, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(hostsTmpPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loop() throws IOException {
        while (!Thread.interrupted()) {
            int numberReady = selector.select();
            while (numberReady <= 0) {
                numberReady = selector.select();
            }

            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                if (!key.isValid()) {
                    iterator.remove();
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        accept(key);
                        continue;
                    }

                    if (key.isConnectable()) {
                        connect(key);
                        continue;
                    }

                    if (key.isReadable()) {
                        int bytesRead = read(key);
                        if (bytesRead == -1) continue;
                    }

                    if (key.isWritable()) {
                        write(key);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    iterator.remove();
                }
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ForwardUnit unit = new ForwardUnit(BUFF_SIZE);
        ForwardUnit opp = null;
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        try {
            unit.socket = serverSocketChannel.accept();
            System.out.format("%-9s %s\n", "ACCEPT", unit);
            unit.socket.configureBlocking(false);
            opp = new ForwardUnit(unit.socket, BUFF_SIZE);
            opp.socket = SocketChannel.open();
            opp.socket.configureBlocking(false);
            opp.socket.connect(remoteSocketAddress);
            opp.opposite = unit;
            unit.opposite = opp;
            unit.socket.register(selector, OP_READ, unit);
            opp.socket.register(selector, OP_CONNECT, opp);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                if (unit.socket != null) {
                    unit.socket.close();
                }

                if (opp != null && opp.socket != null) {
                    opp.socket.close();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            // TODO: 12/10/17 correctly handle exception
            throw e;
        }
    }

    private void connect(SelectionKey key) throws IOException {
        ForwardUnit unit = (ForwardUnit) key.attachment();
        boolean connected;
        try {
            connected = unit.socket.finishConnect();
        } catch (IOException e) {
            e.printStackTrace();
            try {
                unit.opposite.socket.close();
                unit.socket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw e;
        }

        System.out.format("%-9s %s\n", "CONNECT", unit);
        if (connected) {
//            System.out.println("connected " + toString(pair.remote));
            removeOps(unit.socket.keyFor(selector), OP_CONNECT);
            addOps(unit.socket.keyFor(selector), OP_READ | OP_WRITE);
        } else {
//            System.err.println("connect not finished");
        }
    }

    private int write(SelectionKey key)
            throws IOException {
        ForwardUnit unit = (ForwardUnit) key.attachment();
        System.out.format("%-9s %s: ", "WRITE to", toString(unit.socket));
        return unit.write();
    }

    private int read(SelectionKey key)
            throws IOException {
        ForwardUnit unit = (ForwardUnit) key.attachment();
        System.out.format("%-9s %s: ", "READ from", toString(unit.socket));
        return unit.read();
    }


    private void addOps(SelectionKey key, int ops) {
        key.interestOps(key.interestOps() | ops);
    }

    private void removeOps(SelectionKey key, int ops) {
        key.interestOps(key.interestOps() & (~ops));
    }

    private static String toString(SocketAddress address) {
        if (address == null) return null;
        InetSocketAddress addr = (InetSocketAddress) address;
        String ip = addr.getAddress().getHostAddress();
        int port = addr.getPort();
        return String.format("%15s:%-5d", ip, port);
    }

    private static String toString(SelectableChannel s) {
        String str = s.toString();
        return str.substring(SocketChannel.class.getName().length());
    }

    private static String toString(SocketChannel channel) {
        String local = null;
        try {
            local = toString(channel.getLocalAddress());
        } catch (IOException ignored) { }
        String remote = null;
        try {
            remote = toString(channel.getRemoteAddress());
        } catch (IOException ignored) { }
        return String.format("[%s - %s %s]", local, remote,
                channel.isConnected() ? "connected" : channel.isConnectionPending() ? "connection pending" : "disconnected");
    }

    private class ForwardUnit {
        private SocketChannel socket;
        private ForwardUnit opposite;
        private boolean eof = false;
        private boolean outputIsShutdown = false;
        private ByteBuffer buf;

        private ForwardUnit(int bufferSize) {
            this(null, bufferSize);
        }

        private ForwardUnit(SocketChannel socket, int bufferSize) {
            this.socket = socket;
            this.buf = ByteBuffer.allocate(bufferSize);
        }

        private int read() throws IOException {
            int bytesRead = socket.read(buf);

            System.out.format("%d bytes\n", bytesRead);

            if (bytesRead > 0 && opposite.socket.isConnected()) {
                addOps(opposite.socket.keyFor(selector), OP_WRITE);
            }

            if (bytesRead == -1) {
                // eof
                eof = true;
                removeOps(socket.keyFor(selector), OP_READ);

                if (buf.position() == 0) {
                    opposite.shutdownOutput();
                    if (outputIsShutdown || opposite.buf.position() == 0) {
                        close();
                        opposite.close();
                    }
                }

            }

            if (!buf.hasRemaining()) {
                removeOps(socket.keyFor(selector), OP_READ);
            }
            return bytesRead;
        }

        private int write() throws IOException {
            opposite.buf.flip();

            int bytesWritten = socket.write(opposite.buf);

            if (bytesWritten > 0) {
                opposite.buf.compact();
                addOps(opposite.socket.keyFor(selector), OP_READ);
            }

            System.out.format("%d bytes\n", bytesWritten);

            if (opposite.buf.position() == 0) {
                // wrote everything from opposite.buf
                removeOps(socket.keyFor(selector), OP_WRITE);
                if (opposite.eof) {
                    shutdownOutput();
                    if (opposite.outputIsShutdown) {
                        close();
                        opposite.close();
                    }
                }
            }

            return bytesWritten;
        }

        private void shutdownOutput()
                throws IOException {
            socket.shutdownOutput();
            outputIsShutdown = true;
        }

        private void close()
                throws IOException {
            System.out.format("%-9s %s", "CLOSE", PortForwarder.toString(socket));
            socket.close();
            socket.keyFor(selector).cancel();
        }

        @Override
        public String toString() {
            return PortForwarder.toString(socket);
        }
    }
}
